#!/system/bin/sh
# ============================================================================
# 检测并修复 system_server 的 AOT 产物失配（整条 SYSTEMSERVERCLASSPATH）
#
# 移植 ROM 的典型病：jar 换过、或者当初是拿裸 `dex2oat --dex-file=X.jar` 批量
# 重编的（不带 --class-loader-context），ART 于是收下 vdex、拒掉 odex，那一段
# 代码在 system_server 里退回解释执行 + JIT。判据就是 system_server 到底有没有
# 映射对应的 .odex——只映射 .vdex 加裸 .jar 就是中招了。
#
# odrefresh 修不了：它只看文件存在性与 apex 版本，而且会把自己编到
# /data/misc/apexdata 的产物在下次开机当多余清掉。
#
# 本脚本在 boot_completed 之后后台运行，重编到 $MODDIR/payload/oat/，
# 由 post-fs-data 在**下次开机**绑定生效。编译占 CPU，因此只在确实需要时做，
# 并用状态文件限制重试次数。
#
# 不硬编码机型：boot 镜像链、BOOTCLASSPATH、class loader context 全部从运行中
# 的 system_server 读取，任何存在同类失配的移植系统都适用。
#
# 2026-09-12 三处订正（前一版只修 services.jar，且这两条 bug 会产出被 ART 拒收
# 的产物，只是当时 services.odex 是用别的途径编出来的才没暴露）：
#   1. BOOTCLASSPATH 原先用 `grep -ao 'BOOTCLASSPATH=[^ ]*'` 取，environ 是
#      NUL 分隔的，`[^ ]*` 不在 NUL 处停，会把后面的环境变量一起吞进来
#      （实测 5052 字符 vs 真实 2652）。改成按 NUL 切分再取值。
#   2. boot 镜像链原先 `sort -u`，按字母序 `boot.art` 会排到最后（'-' 0x2D 小于
#      '.' 0x2E），而主镜像必须在最前，否则 dex2oat 静默不用镜像，产物头部是
#      requires-image=false + bootclasspath-checksums=d/...，运行时必被拒。
#      改成保持 maps 里的出现顺序（=加载顺序）只去重。
#   3. 镜像名正则原先 `boot[a-z-]*\.art`，漏掉 boot-QPerformance.art 这类含大写
#      的扩展镜像，21 项只取到 18 项。放宽字符集。
#   产出后用 oatdump 自检 requires-image/classpath，这三类错误不会再悄悄溜过去。
# ============================================================================
MODDIR=${1:-${0%/*}/..}
. "$MODDIR/common.sh" 2>/dev/null || { log_msg() { :; }; }

OUT="$MODDIR/payload/oat"
MANIFEST="$OUT/oat-manifest.txt"
FINGERPRINT="$OUT/oat-fingerprint.txt"
STAMP="$MODDIR/.art-oat-repair"
MAX_TRY=3
DEX2OAT=/apex/com.android.art/bin/dex2oat64
OATDUMP=/apex/com.android.art/bin/oatdump

_p=""; _img=""; _bcp=""; _ssc=""

# 产物的有效性绑死三样东西：被编的 jar 本身、启动镜像、以及 class loader context
# 里引用的那些 jar。任何一样变了（换移植包、ROM 升级、odrefresh 重建镜像），
# 旧产物就会被 ART 拒收。post-fs-data 在开机早期没有 system_server 可问，
# 所以这里把这些输入的体量落成一行指纹，绑定前比一比，不一致就别绑——
# 宁可这一轮不生效（由本脚本重编，下次开机再绑），也不要拿一份不匹配的产物
# 去盖掉别的移植包里本来好好的原厂产物。
compute_fingerprint() {
    _fp=""
    for _f in /system/framework/arm64/boot.art /system/framework/boot.vdex; do
        [ -f "$_f" ] && _fp="$_fp ${_f##*/}:$(stat -c %s "$_f" 2>/dev/null)"
    done
    _old=$IFS; IFS=:
    for _j in $1; do
        IFS=$_old
        case "$_j" in
            /system/*|/system_ext/*) [ -f "$_j" ] && _fp="$_fp ${_j##*/}:$(stat -c %s "$_j" 2>/dev/null)" ;;
        esac
        IFS=:
    done
    IFS=$_old
    echo "$_fp"
}

read_runtime() {
    _p=$(pidof system_server 2>/dev/null)
    [ -n "$_p" ] || { log_msg "art-oat: system_server 未运行，跳过"; return 1; }

    # 镜像链必须与运行时逐项同序：从 maps 的 [anon:dalvik-<location>.art] 取，
    # 这些名字就是 boot image 的 location，按地址出现顺序即加载顺序。
    _img=$(grep -oE '/[^ ]*/boot[a-zA-Z0-9_.-]*\.art' "/proc/$_p/maps" 2>/dev/null \
           | grep -v '/arm64/' | awk '!seen[$0]++' | tr '\n' ':' | sed 's/:$//')
    case "$_img" in
        /system/framework/boot.art:*|/apex/*/javalib/boot.art:*) ;;
        *) log_msg "art-oat: 镜像链首项不是 boot.art（[$_img]），跳过"; return 1 ;;
    esac

    _bcp=$(tr '\0' '\n' <"/proc/$_p/environ" 2>/dev/null | sed -n 's/^BOOTCLASSPATH=//p' | head -1)
    [ ${#_bcp} -gt 100 ] || { log_msg "art-oat: 读不到 BOOTCLASSPATH，跳过"; return 1; }

    _ssc=$(tr '\0' '\n' <"/proc/$_p/environ" 2>/dev/null | sed -n 's/^SYSTEMSERVERCLASSPATH=//p' | head -1)
    [ ${#_ssc} -gt 20 ] || { log_msg "art-oat: 读不到 SYSTEMSERVERCLASSPATH，跳过"; return 1; }
    return 0
}

# 产出自检：形态必须与 ART 会接受的一致，否则丢弃，免得白绑一份废产物
check_artifact() {
    [ -x "$OATDUMP" ] || return 0
    _kv=$("$OATDUMP" --header-only --oat-file="$1" 2>/dev/null | \
          awk '/^KEY VALUE STORE/{f=1} f{print} /^SIZE/{exit}')
    case "$_kv" in
        *"requires-image = true"*) ;;
        *) log_msg "WARN art-oat: $1 的 requires-image 不为 true，丢弃"; return 1 ;;
    esac
    case "$_kv" in
        *"bootclasspath-checksums = i;"*) ;;
        *) log_msg "WARN art-oat: $1 的 bootclasspath-checksums 不是镜像形式，丢弃"; return 1 ;;
    esac
    return 0
}

# $1 = jar 绝对路径
repair_one() {
    _jar=$1
    _base=${_jar##*/}; _base=${_base%.jar}
    _dir=${_jar%/*}
    _dst="$_dir/oat/arm64"

    [ -f "$_jar" ] || return 0
    # 目标位置没有原产物就没有可绑定的挂载点，绑不上去
    [ -f "$_dst/$_base.odex" ] || return 0
    # 原产物就是 verify 档的，本来就没有编译代码，重编无收益
    if [ -x "$OATDUMP" ]; then
        _f=$("$OATDUMP" --header-only --oat-file="$_dst/$_base.odex" 2>/dev/null | \
             awk '/^KEY VALUE STORE/{f=1} f{print} /^SIZE/{exit}' | sed -n 's/^compiler-filter = //p')
        [ "$_f" = "verify" ] && return 0
    fi
    # 已经被 system_server 映射 = ART 收了，无需处理
    if grep -q "$_dst/$_base\.odex" "/proc/$_p/maps" 2>/dev/null; then
        return 0
    fi
    # 本模块已经产出过同名产物且已绑定生效的，也跳过
    [ -f "$OUT/$_base.odex" ] && grep -q "$_dst/$_base\.odex" "/proc/$_p/maps" 2>/dev/null && return 0

    # class loader context = SYSTEMSERVERCLASSPATH 里排在本 jar 前面的那些。
    # 缺这一项（或里面某个 jar 的校验和对不上）时，ART 的表现就是收 vdex 拒 odex。
    _ctx=${_ssc%%:$_jar*}
    [ "$_ctx" = "$_ssc" ] && _ctx=""

    log_msg "art-oat: 重编 $_base（context=PCL[$_ctx]）"
    _tmp=/data/local/tmp/.art-oat-$$-$_base
    rm -rf "$_tmp"; mkdir -p "$_tmp" || return 0
    if "$DEX2OAT" --android-root=out/empty \
        --instruction-set=arm64 --instruction-set-features=default \
        --compiler-filter=speed \
        --no-abort-on-hard-verifier-error --no-abort-on-soft-verifier-error \
        --dex-file="$_jar" \
        --oat-file="$_tmp/$_base.odex" --app-image-file="$_tmp/$_base.art" \
        --boot-image="$_img" \
        --class-loader-context="PCL[$_ctx]" \
        --runtime-arg -Xbootclasspath:"$_bcp" \
        --runtime-arg -Xbootclasspath-locations:"$_bcp" >/dev/null 2>&1 \
       && [ -s "$_tmp/$_base.odex" ] && [ -s "$_tmp/$_base.vdex" ] \
       && check_artifact "$_tmp/$_base.odex"; then
        mkdir -p "$OUT"
        for _e in odex vdex art; do
            [ -s "$_tmp/$_base.$_e" ] && cp -f "$_tmp/$_base.$_e" "$OUT/$_base.$_e"
        done
        chown 0:0 "$OUT/$_base".* 2>/dev/null
        chmod 0644 "$OUT/$_base".* 2>/dev/null
        chcon u:object_r:system_file:s0 "$OUT/$_base".* 2>/dev/null
        grep -qs "^$_base $_dst\$" "$MANIFEST" || echo "$_base $_dst" >>"$MANIFEST"
        log_msg "art-oat: $_base 重编完成，下次开机由 post-fs-data 绑定生效"
        _did=$((_did + 1))
    else
        log_msg "WARN art-oat: $_base 重编失败"
    fi
    rm -rf "$_tmp"
}

art_oat_repair() {
    [ -x "$DEX2OAT" ] || { log_msg "art-oat: dex2oat 不存在，跳过"; return 0; }
    read_runtime || return 0

    # 先看有没有活要干，没有就别动状态文件
    _need=0
    _old_IFS=$IFS; IFS=:
    for _j in $_ssc; do
        case "$_j" in /system/*|/system_ext/*) ;; *) continue ;; esac
        _b=${_j##*/}; _b=${_b%.jar}; _d=${_j%/*}/oat/arm64
        [ -f "$_d/$_b.odex" ] || continue
        grep -q "$_d/$_b\.odex" "/proc/$_p/maps" 2>/dev/null || _need=1
    done
    IFS=$_old_IFS
    if [ "$_need" = 0 ]; then
        # 都已被接受。若 payload 里有产物却没有指纹（旧版本升上来的情况），
        # 这里补记一次，否则下次开机 post-fs-data 无从判断该不该绑。
        if ls "$OUT"/*.odex >/dev/null 2>&1 && [ ! -s "$FINGERPRINT" ]; then
            compute_fingerprint "$_ssc" >"$FINGERPRINT"
            log_msg "art-oat: 产物均已生效，补记指纹"
        else
            log_msg "art-oat: SYSTEMSERVERCLASSPATH 的 odex 全部已被 ART 接受，无需处理"
        fi
        rm -f "$STAMP"
        return 0
    fi

    # 有界重试：连续失败 MAX_TRY 次后不再尝试，避免每次开机空烧 CPU。
    # 签名取 SSCP 全文，ROM 更新换了 jar 组成会重新计数。
    _sig=${#_ssc}
    _n=0
    if [ -f "$STAMP" ]; then
        read -r _oldsig _n 2>/dev/null <"$STAMP"
        [ "$_oldsig" = "$_sig" ] || _n=0
        case "$_n" in ''|*[!0-9]*) _n=0 ;; esac
        [ "$_n" -ge "$MAX_TRY" ] && {
            log_msg "art-oat: 已连续失败 ${_n} 次，不再重试（删除 $STAMP 可重置）"
            return 0
        }
    fi
    echo "$_sig $((_n + 1))" >"$STAMP"

    _did=0
    _old_IFS=$IFS; IFS=:
    for _j in $_ssc; do
        case "$_j" in /system/*|/system_ext/*) ;; *) continue ;; esac
        IFS=$_old_IFS
        repair_one "$_j"
        IFS=:
    done
    IFS=$_old_IFS
    if [ "$_did" -gt 0 ]; then
        compute_fingerprint "$_ssc" >"$FINGERPRINT"
        log_msg "art-oat: 本轮产出 $_did 份产物，指纹已记录"
    else
        log_msg "art-oat: 本轮产出 0 份产物"
    fi
}

art_oat_repair
