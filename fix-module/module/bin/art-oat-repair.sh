#!/system/bin/sh
# ============================================================================
# 检测并修复 system_server 的 AOT 产物失配（services.jar）
#
# 移植 ROM 常见问题：services.jar 被换过但配套 oat 产物没重建，ART 因
# location checksum 不符整份拒绝，system_server 主体退回解释执行 + JIT。
# odrefresh 修不了——它只看文件存在性与 apex 版本，且会把自己编到
# /data/misc/apexdata 的产物在下次开机当多余清掉。
#
# 本脚本在 boot_completed 之后后台运行：判据是 system_server 有没有真的
# 映射 .odex。没映射就重编到 $MODDIR/payload/oat/，由 post-fs-data 在
# **下次开机**绑定生效。编译约需数分钟且占满 CPU，因此只在确实需要时做，
# 并用状态文件限制重试次数，避免每次开机重编。
#
# 不硬编码机型：boot 镜像链、BOOTCLASSPATH、class loader context 全部从
# 运行中的系统读取，任何存在同类失配的移植系统都适用。
# ============================================================================
MODDIR=${1:-${0%/*}/..}
. "$MODDIR/common.sh" 2>/dev/null || { log_msg() { :; }; }

OUT="$MODDIR/payload/oat"
STAMP="$MODDIR/.art-oat-repair"
MAX_TRY=3
DEX2OAT=/apex/com.android.art/bin/dex2oat64
JAR=/system/framework/services.jar
DST=/system/framework/oat/arm64

art_oat_needed() {
    _p=$(pidof system_server 2>/dev/null)
    [ -n "$_p" ] || return 1
    # 三个产物都被映射才算生效；只映射 vdex 属于 class loader context 不匹配
    grep -q "$DST/services.odex" "/proc/$_p/maps" 2>/dev/null && return 1
    return 0
}

art_oat_repair() {
    [ -x "$DEX2OAT" ] || { log_msg "art-oat: dex2oat 不存在，跳过"; return 0; }
    [ -f "$JAR" ] || { log_msg "art-oat: $JAR 不存在，跳过"; return 0; }
    [ -f "$DST/services.odex" ] || { log_msg "art-oat: 目标位置无原产物，跳过"; return 0; }

    if ! art_oat_needed; then
        log_msg "art-oat: system_server 已映射 .odex，无需处理"
        rm -f "$STAMP"
        return 0
    fi

    # 有界重试：连续失败 MAX_TRY 次后不再尝试，避免每次开机空烧 CPU。
    # 记录 services.jar 的大小，ROM 更新换了 jar 会重新计数。
    _sig=$(stat -c %s "$JAR" 2>/dev/null)
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

    # boot 镜像链必须与运行时逐项一致：单组件会让 odex 头部的
    # bootclasspath-checksums 结构对不上。从 system_server 的 maps 里读实际值。
    _p=$(pidof system_server)
    _img=$(grep -oE '/[^ ]*/boot[a-z-]*\.art' "/proc/$_p/maps" 2>/dev/null \
           | grep -v '/arm64/' | sort -u | tr '\n' ':' | sed 's/:$//')
    [ -n "$_img" ] || { log_msg "art-oat: 读不到 boot 镜像链，跳过"; return 0; }

    _bcp=$(grep -ao 'BOOTCLASSPATH=[^ ]*' "/proc/$_p/environ" 2>/dev/null | head -1)
    _bcp=${_bcp#BOOTCLASSPATH=}
    [ ${#_bcp} -gt 100 ] || { log_msg "art-oat: 读不到 BOOTCLASSPATH，跳过"; return 0; }

    # class loader context = SYSTEMSERVERCLASSPATH 里排在 services.jar 前面的
    # 那些 jar。缺这一项时 ART 的表现是收下 vdex 却拒绝 odex。
    _ssc=$(grep -ao 'SYSTEMSERVERCLASSPATH=[^ ]*' "/proc/$_p/environ" 2>/dev/null | head -1)
    _ssc=${_ssc#SYSTEMSERVERCLASSPATH=}
    _ctx=${_ssc%%:$JAR*}
    [ "$_ctx" = "$_ssc" ] && _ctx=""
    log_msg "art-oat: 开始重编 (boot-image=$_img, context=PCL[$_ctx])"

    _tmp=/data/local/tmp/.art-oat-$$
    rm -rf "$_tmp"; mkdir -p "$_tmp" || return 0
    if "$DEX2OAT" --android-root=out/empty \
        --instruction-set=arm64 --instruction-set-features=default \
        --compiler-filter=speed \
        --no-abort-on-hard-verifier-error --no-abort-on-soft-verifier-error \
        --dex-file="$JAR" \
        --oat-file="$_tmp/services.odex" --app-image-file="$_tmp/services.art" \
        --boot-image="$_img" \
        --class-loader-context="PCL[$_ctx]" \
        --runtime-arg -Xbootclasspath:"$_bcp" \
        --runtime-arg -Xbootclasspath-locations:"$_bcp" >/dev/null 2>&1 \
       && [ -s "$_tmp/services.odex" ] && [ -s "$_tmp/services.vdex" ]; then
        mkdir -p "$OUT"
        for _f in services.odex services.vdex services.art; do
            [ -f "$_tmp/$_f" ] && cp -f "$_tmp/$_f" "$OUT/$_f"
        done
        chown 0:0 "$OUT"/services.* 2>/dev/null
        chmod 0644 "$OUT"/services.* 2>/dev/null
        chcon u:object_r:system_file:s0 "$OUT"/services.* 2>/dev/null
        log_msg "art-oat: 重编完成，产物已就位；下次开机由 post-fs-data 绑定生效"
    else
        log_msg "WARN art-oat: dex2oat 失败（第 $((_n + 1)) 次）"
    fi
    rm -rf "$_tmp"
}

art_oat_repair
