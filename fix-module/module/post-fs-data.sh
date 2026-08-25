#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

# ============================================================================
# 联想平板 Pro GT - ColorOS 基础修复 · post-fs-data 阶段
# 实际修复与作用：
#   1) 显示色域映射：生动=原生色域(256)、自然=标准 sRGB(0)，修正移植 ROM
#      色温标签映射错误导致的偏暗；
#   2) 音频效果类型修正为 Dolby（移植 ROM 广告 AudioX 但效果表是 Dolby）；
#   3) Tango 32 位 zygote 兼容：停止与 32 位 libdl 不兼容的 tango 进程，
#      并 bind 翻译过的 32 位 libdl；
#   4) AON 原生运行时：把保留的 AIBoost/QNN(HTP) 运行时 bind 进 AON 应用
#      的链接器命名空间，并启动命名空间加载器，恢复真实 NPU 推理生命周期；
#   5) AON 原始 QNN ODM 配置：补齐移植 ROM 缺失的 /odm/etc/camera 配置；
#   6) 环境光能力：恢复 oplus.product.display_features.xml，使环境光
#      自适应（色温）功能生效；
#   7) 144Hz 刷新率策略：提供更高版本 TB710FU 策略，覆盖云端 60/90/120；
#   8) 录音增益：HAL 级绑定 speaker-mic 采集增益（TX_DEC 96 / ADC 16），
#      修正小布唤醒与录音音量偏小、破音问题；
#   9) 序列号补齐：缺失的 ro.serialno 等属性从真实属性继承。
# 仅适用于 SM8650Q / pineapple 平台；LSPosed Hook APK 独立安装。
# ============================================================================

log_msg "post-fs-data start"

if ! is_supported_device; then
    log_msg "unsupported device; skipped Lenovo Pad Pro GT fixes"
    exit 0
fi

# ============================================================================
# 内存扩展（RAM expansion）：接上原厂的真实权威
#
# 本机跑的是标准 zram（zram0，8 GiB），而 ColorOS 的「内存扩展」走 OPlus 自己的
# nandswap —— 它要 /proc/nandswap、/proc/nandswap_vnd 与 zram 的 hybridswap_*
# sysfs，联想内核上一个都没有。
#
# 反编译 Athena（u3/i1.smali）确认了真正的数据流向，此前几版都搞反了：
#
#   Settings 的开关  ->  Settings.Secure.customize_ram_swap_state   (0/1)
#   Settings 的档位  ->  Settings.Secure.customize_ram_swap_value   (档位下标)
#         |
#         v  u3.i1$a / u3.i1$b 两个 ContentObserver
#   persist.sys.oplus.nandswap / .swapsize     <- 派生镜像，由 observer 写
#
# 也就是说 persist.sys.oplus.nandswap **不是**开关，只是 Secure 设置的镜像。
# 早先几版直接写这个属性，等于跟镜像较劲：observer 一被触发就按 Secure 里的值
# 重写回去，表现就是「开机后好的、过一会自动关闭」。
#
# 因此这里改为：以两个 Secure 键为唯一权威，在开机时按 observer 完全相同的规则
# 把镜像属性推导出来，再把 zram 调成用户选的档位。用户之后在 UI 里改动，observer
# 立刻更新镜像、Secure 落盘，下次开机我们再按新值推导 —— 单一事实来源，不打架。
#
#   persist.sys.oplus.nandswap.cfg        档位表（GB 列表），本机为 "4,6,8"
#   persist.sys.oplus.nandswap.swapsize.curr   当前真实生效的大小，如实回读
#
# 安全边界：
#   * 只在 swap 已用量很小的开机早期动手（RAM_EXPAND_MAX_USED_KB）。运行时
#     swapoff 需要把已压缩的页全部换回内存，实测本机常驻占用 7.4/7.6 GiB、
#     swap 内压着 3 GiB 以上，那样做必然 OOM。
#   * 目标值必须来自 cfg 档位表，且不超过物理内存，杜绝任意值。
#   * 每一步都校验；任何一步失败就把 disksize 恢复原值并重新 swapon，
#     失败路径下宁可保持原状也不留下一个没有 swap 的系统。
#   * Secure 键从未被写过时（本机出厂即如此）不猜、也不动 zram，沿用 init 建好
#     的状态 —— 「保留默认」就是字面意义上的什么都不改。
# ============================================================================
# 开机早期 swap 应当几乎是空的。超过这个已用量说明我们来晚了，届时 swapoff
# 需要把已压缩的页全部换回内存 —— 实测本机常驻 7.4/7.6GiB，那样做必然 OOM。
RAM_EXPAND_MAX_USED_KB=131072
ZRAM_DEV=/dev/block/zram0
ZRAM_SYS=/sys/block/zram0
SECURE_SETTINGS_XML=/data/system/users/0/settings_secure.xml

# post-fs-data 阶段 system_server 还没起来，settings 命令不可用；但 SettingsProvider
# 的 DE 存储此刻已经解密可读，直接从 XML 取值。属性顺序不固定，按标签整体匹配。
secure_setting_early() {
    key="$1"
    [ -r "$SECURE_SETTINGS_XML" ] || return 1
    awk -v RS='>' -v k="name=\"$key\"" '
        index($0, k) && match($0, /value="[^"]*"/) {
            print substr($0, RSTART + 7, RLENGTH - 8)
            exit
        }' "$SECURE_SETTINGS_XML" 2>/dev/null
}

set_persist_prop() {
    key="$1"; value="$2"
    [ "$(getprop "$key")" = "$value" ] && return 0
    # persist.* 必须用 -p（同时落盘 /data/property）。-n 只改内存，重启后会被
    # 磁盘上的旧值覆盖，早期实现正是因此让开关一直显示为关。
    resetprop -p "$key" "$value" 2>/dev/null
    [ "$(getprop "$key")" = "$value" ] && return 0
    log_msg "WARN: ram-expand: failed to set $key=$value (still '$(getprop "$key")')"
    return 1
}

ram_expand_gb_for_level() {
    cfg="$1"; lvl="$2"
    case "$lvl" in ''|*[!0-9]*) return 1 ;; esac
    echo "$cfg" | tr ',' '\n' | awk -v want="$lvl" '
        NR - 1 == want { gsub(/[^0-9]/, "", $0); if ($0 != "") print $0 }'
}

zram_swap_is_on() { grep -q "^$ZRAM_DEV " /proc/swaps 2>/dev/null; }

zram_swap_used_kb() {
    awk -v d="$ZRAM_DEV" '$1 == d { print $4; found = 1 } END { if (!found) print 0 }' /proc/swaps 2>/dev/null
}

# 把 swap 摘下来。开机早期用量应当极小；超限就拒绝，宁可维持现状。
zram_swapoff_if_safe() {
    zram_swap_is_on || return 0
    used=$(zram_swap_used_kb)
    case "$used" in ''|*[!0-9]*) used=0 ;; esac
    if [ "$used" -gt "$RAM_EXPAND_MAX_USED_KB" ]; then
        log_msg "ram-expand: zram already holds ${used}KB; too late to reconfigure safely"
        return 1
    fi
    swapoff "$ZRAM_DEV" 2>/dev/null && return 0
    log_msg "WARN: ram-expand: swapoff failed"
    return 1
}

zram_apply_size_gb() {
    target_gb="$1"
    # disksize 是 64 位字节数；设备 shell 的 $(( )) 只有 32 位会溢出，交给 awk。
    target_bytes=$(awk -v g="$target_gb" 'BEGIN{ printf "%.0f", g * 1073741824 }')
    current_bytes=$(cat "$ZRAM_SYS/disksize" 2>/dev/null)
    case "$current_bytes" in ''|*[!0-9]*) current_bytes=0 ;; esac
    if [ "$target_bytes" = "$current_bytes" ] && zram_swap_is_on; then
        return 0
    fi
    zram_swapoff_if_safe || return 1
    if echo 1 >"$ZRAM_SYS/reset" 2>/dev/null &&
       echo "$target_bytes" >"$ZRAM_SYS/disksize" 2>/dev/null &&
       [ "$(cat "$ZRAM_SYS/disksize" 2>/dev/null)" = "$target_bytes" ] &&
       mkswap "$ZRAM_DEV" >/dev/null 2>&1 &&
       swapon "$ZRAM_DEV" 2>/dev/null; then
        log_msg "ram-expand: zram enabled at ${target_gb}GiB ($current_bytes -> $target_bytes)"
        return 0
    fi
    # 回滚：恢复原尺寸并把 swap 装回去，绝不留下一个没有 swap 的系统。
    echo 1 >"$ZRAM_SYS/reset" 2>/dev/null
    echo "$current_bytes" >"$ZRAM_SYS/disksize" 2>/dev/null
    mkswap "$ZRAM_DEV" >/dev/null 2>&1
    swapon "$ZRAM_DEV" 2>/dev/null
    log_msg "WARN: ram-expand: failed to apply ${target_gb}GiB; restored $current_bytes"
    return 1
}

zram_disable() {
    zram_swap_is_on || {
        [ "$(cat "$ZRAM_SYS/disksize" 2>/dev/null)" = 0 ] && return 0
    }
    zram_swapoff_if_safe || return 1
    echo 1 >"$ZRAM_SYS/reset" 2>/dev/null
    log_msg "ram-expand: zram disabled (swap off, disksize $(cat "$ZRAM_SYS/disksize" 2>/dev/null))"
    return 0
}

# ============================================================================
# 按 Athena observer 的规则，在开机时把 Secure 权威推导成镜像与真实 zram
#
# 之前 UI 与实际对不上，是两层原因叠在一起：
#   1. 开关压根没接到底层 —— 无论开还是关，init 都会照常把 zram0 建成 8GiB 并
#      swapon，于是「关」的状态下 UI 说没开、实际有 8GiB 压缩交换在跑；
#   2. 模块写的是 persist.sys.oplus.nandswap，而它只是 Secure 设置的派生镜像，
#      Athena 的 ContentObserver 随时会按 Secure 里的值把它改回去。
#
# 这里两层一起解决：读 Secure，按 observer 的同一套规则推导镜像，并把 zram 调成
# 用户选的档位。
#
#   state=1 + 档位 N  ->  zram 就是该档位大小、swap 挂载，镜像写 true/N GB
#   state=0           ->  swapoff 并把 disksize 归零，真正没有 swap，镜像写 false/0
#   state 未写过      ->  沿用默认（Athena 的 getInt 默认值也是 1），不动 zram
#
# 主开关始终由用户掌握：模块只按他在 UI 里留下的 Secure 值执行，不替他打开、也
# 不在他关掉之后改回来。原厂 nandswap 切档同样要求重启，因此在 post-fs-data 应用
# 与原生语义一致，而且这正是 swap 尚空、可以安全重建的唯一窗口。
# ============================================================================
# ============================================================================
# OPlus sched_assist / oplus_binder 的 /proc ABI
#
# 移植过来的 ColorOS 会持续标记「这个线程/场景对交互延迟敏感」，路径是
# /proc/oplus_scheduler/sched_assist/* 与 /proc/oplus_binder/ux_flag。联想内核
# 没有这套接口，于是每一次标记都失败——空闲时约每秒一次，从桌面开关应用时更密
# 集（实测一次开关三个应用产生 30 次失败，binder/ux_flag 单独就有 86 次）。
#
# 模块把这套 ABI 建起来，并且对逐线程的标记应用真实的 uclamp 下限（实测
# surfaceflinger 主线程 uclamp.min 0 -> 200 -> 0）。ROM 自带的 sepolicy 已经为
# 这些路径写好 genfscon，节点一建出来就拿到正确标签，无需额外规则。
#
# 诚实的边界：**没有实测到它减少卡顿**。有无模块、以及提升幅度 200/512 两组
# 交叉 A/B 的差异都被运行顺序漂移淹没，不能算作已验证的收益。装它的理由是补齐
# 一个真实缺口并消除错误刷屏；apply_boost=0 可以只记录不影响调度。
# ============================================================================
SCHED_ASSIST_KO="$MODDIR/bin/oplus_sched_assist.ko"
if [ -f "$SCHED_ASSIST_KO" ] && ! lsmod 2>/dev/null | grep -q '^oplus_sched_assist '; then
    if insmod "$SCHED_ASSIST_KO" 2>/dev/null; then
        log_msg "sched-assist: OPlus sched_assist/binder proc ABI provided"
    else
        log_msg "WARN: sched-assist: insmod failed"
    fi
fi

# ============================================================================
# 带写回的压缩交换（aclswap）
#
# 联想内核的 zram 没有 writeback：`# CONFIG_ZRAM_WRITEBACK is not set`，
# /proc/nandswap 与 hybridswap_* 也全部缺失。而移植来的 ColorOS 内存策略是按
# 「压缩池能排空到闪存」标定的——LMKD 把 file cache 算作可用就不杀，正是因为
# 原厂机器上池子会自己泄压。这里池子只进不出，实测 mem_used_max 达到 1.70GB，
# 连开 24 个应用有三次启动超过 40 秒，MemFree 全程钉在 85–260MB。
#
# bin/aclswap.ko 是同一份 GKI zram 源码开启 writeback 后改名重编的外挂模块，
# 与在用的 GKI zram.ko 共存而非替换（同名替换会被 CONFIG_MODULE_SIG_PROTECT
# 拒绝）。详见 kernel-compat/aclswap/README.md。
#
# backing 文件必须放在 /data/vendor：loop 的内核工作线程写 backing 文件要过
# SELinux，放在 /data/local/tmp 或 /data/adb 时每次写都失败，而且报的是
# "loop: Write error" + I/O error 而不是权限错误，极难排查。
#
# 任何一步失败都完整回滚到原厂标准 zram，宁可没有写回也不能留下没有 swap 的系统。
# ============================================================================
ACLSWAP_KO="$MODDIR/bin/aclswap.ko"
ACLSWAP_DEV=/dev/block/aclswap0
ACLSWAP_SYS=/sys/block/aclswap0
ACLSWAP_BACKING_DIR=/data/vendor/aclswap
ACLSWAP_BACKING="$ACLSWAP_BACKING_DIR/backing.img"
# 压缩池能占用的物理内存上限。写回把冷页挪到闪存，这个上限决定热数据能留多少。
ACLSWAP_MEM_LIMIT_MB=1536
# backing 文件大小。写回存的是解压后的页，所以最坏情况需要与 disksize 等量；
# 这里按档位取值并设上限，避免为了 8GiB 档吃掉 8GB 存储。
ACLSWAP_BACKING_MAX_GB=4

aclswap_teardown() {
    swapoff "$ACLSWAP_DEV" 2>/dev/null
    [ -d "$ACLSWAP_SYS" ] && echo 1 >"$ACLSWAP_SYS/reset" 2>/dev/null
    rmmod aclswap 2>/dev/null
    [ -n "$ACLSWAP_LOOP" ] && losetup -d "$ACLSWAP_LOOP" 2>/dev/null
    ACLSWAP_LOOP=""
}

# 建立 backing 文件。用 fallocate 真实占块而不是稀疏文件：swap 正在使用时文件
# 系统写满会让写回失败，那时丢的是已经换出去的页。
aclswap_prepare_backing() {
    want_bytes="$1"
    mkdir -p "$ACLSWAP_BACKING_DIR" 2>/dev/null || return 1
    restorecon -R "$ACLSWAP_BACKING_DIR" 2>/dev/null
    have=$(stat -c %s "$ACLSWAP_BACKING" 2>/dev/null)
    case "$have" in ''|*[!0-9]*) have=0 ;; esac
    if [ "$have" != "$want_bytes" ]; then
        rm -f "$ACLSWAP_BACKING" 2>/dev/null
        if ! fallocate -l "$want_bytes" "$ACLSWAP_BACKING" 2>/dev/null; then
            log_msg "WARN: aclswap: fallocate ${want_bytes}B failed"
            return 1
        fi
    fi
    restorecon "$ACLSWAP_BACKING" 2>/dev/null
    have=$(stat -c %s "$ACLSWAP_BACKING" 2>/dev/null)
    [ "$have" = "$want_bytes" ] || { log_msg "WARN: aclswap: backing size $have != $want_bytes"; return 1; }
    return 0
}

aclswap_setup() {
    target_gb="$1"
    [ -f "$ACLSWAP_KO" ] || return 1
    [ -x /system/bin/losetup ] || return 1
    case "$target_gb" in ''|0|*[!0-9]*) return 1 ;; esac

    backing_gb="$target_gb"
    [ "$backing_gb" -gt "$ACLSWAP_BACKING_MAX_GB" ] && backing_gb=$ACLSWAP_BACKING_MAX_GB
    backing_bytes=$(awk -v g="$backing_gb" 'BEGIN{ printf "%.0f", g * 1073741824 }')
    aclswap_prepare_backing "$backing_bytes" || return 1

    # post-fs-data 跑在 ueventd 建出 /dev/loop-control 的同一瞬间，先到先输：
    # 首次实测就是在这里失败的（losetup -f 返回空），而同样的命令开机后手动跑
    # 一切正常。等一小会儿而不是直接放弃；等不到就走回退路径。
    aclswap_wait=0
    while [ ! -e /dev/loop-control ] && [ "$aclswap_wait" -lt 30 ]; do
        toybox sleep 0.1 2>/dev/null || sleep 1
        aclswap_wait=$((aclswap_wait + 1))
    done
    [ "$aclswap_wait" -gt 0 ] && log_msg "aclswap: waited $((aclswap_wait * 100))ms for /dev/loop-control"
    if [ ! -e /dev/loop-control ]; then
        log_msg "WARN: aclswap: /dev/loop-control never appeared"
        return 1
    fi

    ACLSWAP_LOOP=""
    aclswap_try=0
    while [ "$aclswap_try" -lt 10 ]; do
        candidate=$(losetup -f 2>/dev/null)
        if [ -n "$candidate" ] && losetup "$candidate" "$ACLSWAP_BACKING" 2>/dev/null; then
            ACLSWAP_LOOP="$candidate"
            break
        fi
        aclswap_try=$((aclswap_try + 1))
        toybox sleep 0.1 2>/dev/null || sleep 1
    done
    if [ -z "$ACLSWAP_LOOP" ]; then
        log_msg "WARN: aclswap: could not attach a loop device after $aclswap_try tries"
        return 1
    fi
    [ "$aclswap_try" -gt 0 ] && log_msg "aclswap: loop attached after $aclswap_try retries"

    if ! lsmod 2>/dev/null | grep -q '^aclswap '; then
        insmod "$ACLSWAP_KO" 2>/dev/null || {
            log_msg "WARN: aclswap: insmod failed"; aclswap_teardown; return 1; }
    fi
    [ -d "$ACLSWAP_SYS" ] || { log_msg "WARN: aclswap: no $ACLSWAP_SYS"; aclswap_teardown; return 1; }

    # 顺序有要求：backing_dev 必须在 disksize 之前设，之后设会被拒绝。
    #
    # 这一步在开机时会瞬态失败而运行时永远成功：zram 用 FMODE_EXCL 独占打开
    # backing 设备，而刚由 loop-control 建出来的设备此刻可能还被内核的分区扫描
    # 占着（这些 loop 都是 partscan=1）。重试而不是放弃，并把真实错误留在日志里，
    # 免得下次又只能看到一句“失败”。
    #
    # 实测补充：3 秒的重试预算不够。有一次开机 15 次全部 EBUSY 而回滚到原厂
    # zram，同一串命令在开机后手动跑第一次就成功——说明是那一个 loop 设备当时
    # 正被独占，而不是普遍性的失败。所以除了把预算放宽到 10 秒，每 8 次还换一个
    # loop 设备重试：EBUSY 针对的是具体设备，换一个通常立刻就成。
    aclswap_bind=0
    while [ "$aclswap_bind" -lt 40 ]; do
        if echo "$ACLSWAP_LOOP" >"$ACLSWAP_SYS/backing_dev" 2>"$MODDIR/.aclswap.err"; then
            break
        fi
        aclswap_bind=$((aclswap_bind + 1))
        toybox sleep 0.25 2>/dev/null || sleep 1
        if [ "$((aclswap_bind % 8))" -eq 0 ]; then
            losetup -d "$ACLSWAP_LOOP" 2>/dev/null
            candidate=$(losetup -f 2>/dev/null)
            if [ -n "$candidate" ] &&
               losetup "$candidate" "$ACLSWAP_BACKING" 2>/dev/null; then
                log_msg "aclswap: backing_dev busy on $ACLSWAP_LOOP; retrying on $candidate"
                ACLSWAP_LOOP="$candidate"
            fi
        fi
    done
    if [ "$(cat "$ACLSWAP_SYS/backing_dev" 2>/dev/null)" = none ] ||
       [ -z "$(cat "$ACLSWAP_SYS/backing_dev" 2>/dev/null)" ]; then
        log_msg "WARN: aclswap: backing_dev bind failed after $aclswap_bind tries: $(cat "$MODDIR/.aclswap.err" 2>/dev/null | tr -d '\n')"
        rm -f "$MODDIR/.aclswap.err"
        aclswap_teardown
        return 1
    fi
    rm -f "$MODDIR/.aclswap.err"
    [ "$aclswap_bind" -gt 0 ] && log_msg "aclswap: backing_dev bound after $aclswap_bind retries"
    target_bytes=$(awk -v g="$target_gb" 'BEGIN{ printf "%.0f", g * 1073741824 }')
    echo "$target_bytes" >"$ACLSWAP_SYS/disksize" 2>/dev/null
    [ "$(cat "$ACLSWAP_SYS/disksize" 2>/dev/null)" = "$target_bytes" ] || {
        log_msg "WARN: aclswap: disksize rejected"; aclswap_teardown; return 1; }
    mem_limit_bytes=$(awk -v m="$ACLSWAP_MEM_LIMIT_MB" 'BEGIN{ printf "%.0f", m * 1048576 }')
    echo "$mem_limit_bytes" >"$ACLSWAP_SYS/mem_limit" 2>/dev/null

    mkswap "$ACLSWAP_DEV" >/dev/null 2>&1 || {
        log_msg "WARN: aclswap: mkswap failed"; aclswap_teardown; return 1; }
    # 优先级必须压过 init 之后重新挂上的 zram0（实测它用 32758）。默认的 -2 会让
    # 内核把所有换出都送去 zram0，我们这个带写回的设备一页都用不上。
    #
    # 必须显式走 toybox：KernelSU 的 post-fs-data 环境里 PATH 上是它自带的
    # busybox，而 busybox 的 swapon 没有 -p，静默退化成默认优先级——首次实测就是
    # 这样，日志显示 aclswap 挂上了却一页没用。
    if ! /system/bin/toybox swapon -p 32767 "$ACLSWAP_DEV" 2>/dev/null; then
        swapon "$ACLSWAP_DEV" 2>/dev/null || {
            log_msg "WARN: aclswap: swapon failed"; aclswap_teardown; return 1; }
        log_msg "WARN: aclswap: swapon without priority; stock zram may outrank it"
    fi
    grep -q "^$ACLSWAP_DEV " /proc/swaps 2>/dev/null || {
        log_msg "WARN: aclswap: not present in /proc/swaps"; aclswap_teardown; return 1; }

    # 我们的设备已经顶上了，这才把原厂 zram 摘掉；顺序反过来会出现无 swap 窗口。
    zram_disable
    log_msg "aclswap: ${target_gb}GiB swap with ${backing_gb}GiB writeback backing, pool capped at ${ACLSWAP_MEM_LIMIT_MB}MB"
    return 0
}

bridge_ram_expansion_ui() {
    [ -d "$ZRAM_SYS" ] && [ -b "$ZRAM_DEV" ] || return 0
    cfg=$(getprop persist.sys.oplus.nandswap.cfg)
    [ -n "$cfg" ] || { log_msg "ram-expand: no gear table; skipped"; return 0; }

    state=$(secure_setting_early customize_ram_swap_state)
    case "$state" in ''|*[!0-9]*) state="" ;; esac
    gear=$(secure_setting_early customize_ram_swap_value)
    case "$gear" in ''|*[!0-9]*) gear="" ;; esac

    # 权威顺序：Secure 键 > 已落盘的镜像属性。两个 Secure 键在本机出厂即为 null，
    # 而 Athena 的 getInt 默认值恰好是 state=1、value=-1（「开、档位未指定」）——
    # 它自己在 value=-1 时也是回落到别处取档位。所以这里同样回落到 ROM 持久化的
    # .lvl / .swapsize：那就是上一次实际生效的档位，既不是凭空捏造，也正是用户在
    # UI 上会看到的值。用户真正拨过开关之后，Secure 有了值，就以 Secure 为准。
    if [ "$state" = 0 ]; then
        enabled=false
        source=secure
    else
        enabled=true
        source=secure
        [ -n "$state" ] || source=prop
    fi

    want_gb=""
    if [ "$enabled" = true ]; then
        want_gb=$(ram_expand_gb_for_level "$cfg" "$gear")
        if [ -z "$want_gb" ]; then
            lvl=$(getprop persist.sys.oplus.nandswap.lvl)
            want_gb=$(ram_expand_gb_for_level "$cfg" "$lvl")
        fi
        if [ -z "$want_gb" ]; then
            want_gb=$(getprop persist.sys.oplus.nandswap.swapsize)
            case "$want_gb" in ''|0|*[!0-9]*) want_gb="" ;; esac
        fi
    fi

    if [ "$enabled" = false ]; then
        zram_disable
    elif [ -n "$want_gb" ]; then
        # MemTotal 永远略小于标称容量，按标称向上取整比较；否则本机
        # MemTotal=7762656kB 向下取整得 7，会把唯一正确的 8GiB 档挡在门外。
        mem_gb=$(awk '/MemTotal:/ { printf "%d", ($2 + 1048575) / 1048576; exit }' /proc/meminfo)
        case "$mem_gb" in ''|*[!0-9]*) mem_gb=0 ;; esac
        if [ "$mem_gb" -gt 0 ] && [ "$want_gb" -le "$mem_gb" ]; then
            # 先试带写回的 aclswap；不可用或任何一步失败都回落到标准 zram，
            # 那条路径与本次改动前完全一致。
            if ! aclswap_setup "$want_gb"; then
                zram_apply_size_gb "$want_gb"
            fi
        else
            log_msg "ram-expand: requested ${want_gb}GiB exceeds ${mem_gb}GiB RAM; ignored"
        fi
    else
        log_msg "ram-expand: on, but no usable gear anywhere (secure=${gear:-unset}, gears=$cfg); left as-is"
    fi

    # 这里刻意**不**回写那几个镜像属性。此刻 init 还没跑完自己的 swapon，读回来的
    # 「实际大小」可能是 0，据此推导会得出「已关闭」这个错误结论 —— 上一版正是这样
    # 把开关写成了 false。镜像统一交给 service.sh 在 boot_completed 之后按最终状态
    # 推导一次，那时看到的才是事实。
    log_msg "ram-expand: intent=$enabled gear=${want_gb:-none}GiB (source=$source, secure state=${state:-unset} value=${gear:-unset}, gears $cfg)"
    return 0
}

bridge_ram_expansion_ui

# OPD2513 Horae publishes its three calculated shell temperatures through the
# official /proc/shell-temp ABI.  Lenovo's otherwise matching GKI omits only
# that small OPlus module, causing a failed open every five seconds.  Load the
# exact-build compatibility module once, before Horae starts; no userspace
# watchdog is needed.  The module has been built against GKI build 13606743
# and all CONFIG_MODVERSIONS CRCs are checked during the repository build.
# ============================================================================
# vendor 配置覆盖：必须走 bind mount，不能走 KernelSU 的 system 覆盖
#
# KernelSU 的 system/ 覆盖把模块内的文件原样挂到 /vendor/etc，而模块 zip 里的
# 文件带的是 u:object_r:system_file:s0，且挂载点只读、事后 chcon 无效。
# thermal-engine 跑在 vendor 域，读不了 system_file，于是**整份配置被静默丢弃**
# ——策略不生效、不报错、日志里没有任何痕迹。
#
# 实测（向外壳温热区注入 47.5°C）：context 为 system_file 时 GPU 毫无反应；
# 换成 vendor_configs_file 后立刻正确降到 720MHz、撤掉后回满 903MHz。
#
# 同一问题也适用于 /vendor/etc/perf/targetconfig.xml：perf HAL 同为 vendor 域
# （vendor_hal_perf_default / vendor_perfservice），同目录其余配置全部是
# vendor_configs_file，只有我们覆盖的那份是 system_file——即为 SoC ID 696 补的
# 那份 6 核 4 簇拓扑也从未被读到过。实测该拓扑与硬件一致（cpu0-5、policy
# 0 / 1-2 / 3-4 / 5，capacity 379/867×4/1024），配置本身是对的，只是没生效。
#
# 因此改用本项目已验证可行的 bind mount 方式：源文件在 payload/ 内，先 chcon
# 成 vendor_configs_file，再 bind 到原路径。
# ============================================================================
bind_vendor_config() {
    src="$1"
    dst="$2"
    [ -f "$src" ] || return 0
    [ -f "$dst" ] || return 0
    chown 0:0 "$src"
    chmod 0644 "$src"
    chcon u:object_r:vendor_configs_file:s0 "$src" 2>/dev/null
    if mount --bind "$src" "$dst" 2>/dev/null; then
        log_msg "vendor config bind mounted $dst"
    else
        log_msg "WARN: vendor config bind failed for $dst"
    fi
}

for conf in "$MODDIR"/payload/thermal/thermal-engine_*.conf; do
    bind_vendor_config "$conf" "/vendor/etc/$(basename "$conf")"
done
bind_vendor_config "$MODDIR/payload/perf/targetconfig.xml" \
    /vendor/etc/perf/targetconfig.xml

SHELL_TEMP_KO="$MODDIR/bin/oplus_shell_temp_compat.ko"
if ! grep -q '^oplus_shell_temp_compat ' /proc/modules 2>/dev/null; then
    # Also publishes the skin-msm-therm-usr thermal zone that this board's
    # device tree omits, carrying Horae's real shell temperature. Both
    # thermal-engine and the thermal HAL look their skin sensor up by that
    # name; without it they fall back to the board sensor next to the SoC,
    # which measured up to 19 C hotter than the shell.
    if [ -f "$SHELL_TEMP_KO" ] && insmod "$SHELL_TEMP_KO" 2>>"$LOGFILE"; then
        log_msg "OPlus Horae shell-temp compatibility loaded"
    else
        log_msg "WARN: OPlus Horae shell-temp compatibility load failed"
    fi
fi

# Restore only the externally supportable part of the OPlus memory ABI on this
# exact Lenovo GKI. This reports the real standard-zram backend and clamps
# OPlus tuning requests to values proven safe on the 8 GB tablet. It does not
# claim HybridSwap/writeback support; optional hooks stay disabled normally.
MM_COMPAT_KO="$MODDIR/bin/oplus_mm_compat.ko"
if ! grep -q '^oplus_mm_compat ' /proc/modules 2>/dev/null; then
    if [ -f "$MM_COMPAT_KO" ] && insmod "$MM_COMPAT_KO" 2>>"$LOGFILE"; then
        log_msg "OPlus standard-zram memory compatibility loaded"
    else
        log_msg "WARN: OPlus memory compatibility load failed"
    fi
fi
if [ -d /proc/oplus_mem ]; then
    [ -w /proc/oplus_mem/swappiness_para ] && {
        echo 'vm_swappiness=50' >/proc/oplus_mem/swappiness_para 2>/dev/null
        echo 'direct_swappiness=10' >/proc/oplus_mem/swappiness_para 2>/dev/null
    }
    [ -w /proc/oplus_mem/dynamic_swappiness ] && \
        echo '50 1024 30 512' >/proc/oplus_mem/dynamic_swappiness 2>/dev/null
    [ -w /proc/oplus_mem/alloc_adjust_ctrl ] && \
        echo 0 >/proc/oplus_mem/alloc_adjust_ctrl 2>/dev/null
    [ -w /proc/oplus_mem/kswapd_debug ] && \
        echo 0 >/proc/oplus_mem/kswapd_debug 2>/dev/null
    [ -w /proc/oplus_mem/kswapd_load_stat ] && \
        echo 0 >/proc/oplus_mem/kswapd_load_stat 2>/dev/null
    log_msg "OPlus memory compatibility configured with all optional hooks disabled"
fi

# LSPosed starts parsing enabled modules before PackageManager has restored the
# random /data/app path on this ROM.  Keep a signed copy inside the KernelSU
# module and atomically pin LSPosed to that stable, early-visible path before
# zygote/system_server requests its module list.
LSP_DB=/data/adb/lspd/config/modules_config.db
LSP_APK="$MODDIR/hook/BaseFix-Hook.apk"
LSP_SYNC="$MODDIR/bin/lsposed-path-sync.jar"
if [ -f "$LSP_DB" ] && [ -f "$LSP_APK" ] && [ -f "$LSP_SYNC" ]; then
    chown 0:0 "$LSP_APK" "$LSP_SYNC"
    chmod 0644 "$LSP_APK" "$LSP_SYNC"
    chcon u:object_r:system_file:s0 "$LSP_APK" "$LSP_SYNC" 2>/dev/null
    if CLASSPATH="$LSP_SYNC" app_process /system/bin \
            com.aclaniakea.tools.LsposedPathSync "$LSP_DB" "$LSP_APK" \
            >>"$LOGFILE" 2>&1; then
        log_msg "LSPosed hook path pinned before zygote"
    else
        log_msg "ERROR: failed to pin LSPosed hook path"
    fi
else
    log_msg "ERROR: stable LSPosed hook payload is incomplete"
fi

# ============================================================================
# 调优部分（原 coloros_port_tuning）：post-fs-data 阶段
#   1) 全局/根 memcg 使用 CMA 修复后交叉实测最优值 50；8GB watermark=10，
#      12GB 保留 watermark=20。
#      普通/冷后台 app 子组=50，只有 active/systemserver/launcher=0。修正 zsmalloc
#      消耗 CMA 后，实机 40/50/60 交叉测试证明50可保留适量冷匿名页，减少EROFS
#      文件页回填和direct reclaim；60会重新放大压缩、换入与扫描，兼容层
#      因此把kswapd硬上限固定为50，direct swappiness仍限在10。
#   2) bind mount 覆盖 /my_stock 的 osense 配置：关闭主动后台换出，避免实测
#      应用切换期间出现压缩/换入风暴；ZRAM 仍由内核在真实压力下按需使用。
#      不创建、不扩容 ZRAM，兼容 8/12GB RAM 及未启用 ZRAM 的同型号设备。
#   3) root/apps 父 memcg 从启动起即为50，native system/active/systemserver/
#      launcher=0；有界窗口捕获首次解锁新建的子组并按类型写0/50。
# ============================================================================

ram_kb=$(awk '/MemTotal:/{print $2; exit}' /proc/meminfo 2>/dev/null)
case "$ram_kb" in ''|*[!0-9]*) ram_kb=0 ;; esac
vm_swappiness=50
if [ "$ram_kb" -gt 0 ] && [ "$ram_kb" -le 9437184 ]; then
    vm_watermark=10
else
    vm_watermark=20
fi

echo "$vm_swappiness" >/proc/sys/vm/swappiness 2>/dev/null
echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
echo "$vm_watermark" >/proc/sys/vm/watermark_scale_factor 2>/dev/null
echo 0 >/proc/sys/vm/watermark_boost_factor 2>/dev/null
# The transplanted phone script lets the KGSL shrinker reclaim 38,400 pages
# (about 150 MB) in one call. Extended cold-boot A/B/A found that even 4,096
# pages can retrigger multi-GB reclaim; 1,024 pages (4 MB) keeps the same total
# reclaim limit while splitting work into animation-friendly batches.
if [ -w /sys/class/kgsl/kgsl/page_reclaim_per_call ]; then
    echo 1024 >/sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null
fi
if [ -w /dev/memcg/memory.swappiness ]; then
    # On this cgroup-v1 kernel the root memcg node aliases the global value;
    # always keep both writes identical.
    echo "$vm_swappiness" >/dev/memcg/memory.swappiness 2>/dev/null
fi
# Create the standard ColorOS app groups before zygote/system_server. Without
# this, the port creates systemserver roughly 25 seconds after Android is up;
# its pages are charged to swappable root in the meantime and mode=0 cannot
# undo that existing swap later. Match the OEM ownership and modes exactly.
if [ -d /dev/memcg ]; then
    if [ ! -d /dev/memcg/apps ]; then
        mkdir /dev/memcg/apps 2>/dev/null
        chown system:system /dev/memcg/apps 2>/dev/null
        chmod 0755 /dev/memcg/apps 2>/dev/null
    fi
    for protected_group in active systemserver launcher; do
        protected_path="/dev/memcg/apps/$protected_group"
        if [ ! -d "$protected_path" ]; then
            mkdir "$protected_path" 2>/dev/null
            chown system:system "$protected_path" 2>/dev/null
            chmod 0700 "$protected_path" 2>/dev/null
        fi
        [ -w "$protected_path/memory.swappiness" ] && \
            echo 0 >"$protected_path/memory.swappiness" 2>/dev/null
        [ -w "$protected_path/memory.move_charge_at_immigrate" ] && \
            echo 0 >"$protected_path/memory.move_charge_at_immigrate" 2>/dev/null
    done
fi
if [ -w /dev/memcg/apps/memory.swappiness ]; then
    echo "$vm_swappiness" >/dev/memcg/apps/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/system/memory.swappiness ]; then
    # SurfaceFlinger and native framework daemons never enter the app active
    # group. Keep their wake-critical anonymous pages resident as well.
    echo 0 >/dev/memcg/system/memory.swappiness 2>/dev/null
fi

# First unlock creates CE app memcgs in a burst and ColorOS can write its high
# defaults into each new child. Clamp new groups to the same stable 0/50 policy
# across that window. Bounded to four minutes and always exits; it is not a
# resident tuning daemon.
#
# There used to be a .memcg_settle_ready handoff flag here, meant to let
# service.sh cut this short. Nothing ever created it -- both boots logged
# handoff=0 with attempts=240 -- and wiring it up would have been wrong anyway:
# service.sh runs at boot completion, normally *before* the first unlock, which
# is precisely the window this guard exists to cover. The bound is the exit
# condition; there is no handoff.
#
# Note this runs as a plain `( ... ) &` child and does survive: post-fs-data's
# children are not reaped, while service.sh's are, which is why the aclswap
# writeback driver there needs setsid. The asymmetry is real, not an oversight.
(
    attempt=0
    assigned_system_server=0
    assigned_surfaceflinger=0
    assigned_systemui=0
    assigned_launcher=0
    while [ "$attempt" -lt 240 ]; do
        # On this port AMS creates active/systemserver tens of seconds after
        # system_server starts. By then hundreds of MB can already be swapped
        # from first-unlock code. Create the stock-named groups as soon as the
        # apps parent exists, with the same ownership/mode as ColorOS, so the
        # later allocations are charged directly to the protected group.
        if [ -d /dev/memcg/apps ]; then
            for protected_group in active systemserver launcher; do
                protected_path="/dev/memcg/apps/$protected_group"
                if [ ! -d "$protected_path" ]; then
                    mkdir "$protected_path" 2>/dev/null
                    chown system:system "$protected_path" 2>/dev/null
                    chmod 0700 "$protected_path" 2>/dev/null
                fi
                [ -w "$protected_path/memory.swappiness" ] && \
                    echo 0 >"$protected_path/memory.swappiness" 2>/dev/null
            done
        fi
        # Assign wake-critical processes to protected groups as soon as they
        # appear, but deliberately DO NOT migrate pages already charged to the
        # root memcg.  memory.move_charge_at_immigrate is deprecated upstream
        # and moving several hundred MB of existing charges during first unlock
        # caused a kswapd/swap-in storm on this port's incomplete OPlus memory
        # stack.  Future allocations inherit the protected group without that
        # one-time bulk page migration.
        if [ "$assigned_system_server" -eq 0 ] && \
                [ -w /dev/memcg/apps/systemserver/cgroup.procs ]; then
            critical_pid=$(pidof system_server 2>/dev/null | awk '{print $1}')
            if [ -n "$critical_pid" ]; then
                echo 0 >/dev/memcg/apps/systemserver/memory.move_charge_at_immigrate 2>/dev/null
                echo "$critical_pid" >/dev/memcg/apps/systemserver/cgroup.procs 2>/dev/null
                grep -q 'memory:/apps/systemserver$' "/proc/$critical_pid/cgroup" 2>/dev/null && \
                    assigned_system_server=1
            fi
        fi
        if [ "$assigned_surfaceflinger" -eq 0 ] && \
                [ -w /dev/memcg/system/cgroup.procs ]; then
            critical_pid=$(pidof surfaceflinger 2>/dev/null | awk '{print $1}')
            if [ -n "$critical_pid" ]; then
                echo 0 >/dev/memcg/system/memory.move_charge_at_immigrate 2>/dev/null
                echo "$critical_pid" >/dev/memcg/system/cgroup.procs 2>/dev/null
                grep -q 'memory:/system$' "/proc/$critical_pid/cgroup" 2>/dev/null && \
                    assigned_surfaceflinger=1
            fi
        fi
        if [ "$assigned_systemui" -eq 0 ] && \
                [ -w /dev/memcg/apps/active/cgroup.procs ]; then
            critical_pid=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
            if [ -n "$critical_pid" ]; then
                echo 0 >/dev/memcg/apps/active/memory.move_charge_at_immigrate 2>/dev/null
                echo "$critical_pid" >/dev/memcg/apps/active/cgroup.procs 2>/dev/null
                grep -q 'memory:/apps/active$' "/proc/$critical_pid/cgroup" 2>/dev/null && \
                    assigned_systemui=1
            fi
        fi
        # ColorOS leaves Launcher in the root memory cgroup even when
        # SystemUI/system_server are protected.  On the 8 GB tablet it had
        # accumulated heavy swap after standby. Put future allocations in its
        # protected group without bulk-moving existing charges at unlock.
        if [ "$assigned_launcher" -eq 0 ] && \
                [ -w /dev/memcg/apps/launcher/cgroup.procs ]; then
            critical_pid=$(pidof com.android.launcher 2>/dev/null | awk '{print $1}')
            if [ -n "$critical_pid" ]; then
                echo 0 >/dev/memcg/apps/launcher/memory.move_charge_at_immigrate 2>/dev/null
                echo "$critical_pid" >/dev/memcg/apps/launcher/cgroup.procs 2>/dev/null
                grep -q 'memory:/apps/launcher$' "/proc/$critical_pid/cgroup" 2>/dev/null && \
                    assigned_launcher=1
            fi
        fi
        # Several late vendor init actions overwrite the early VM values after
        # post-fs-data (observed at boot as an unwanted late overwrite). Re-apply
        # only when a value differs during this bounded first-unlock window.
        # This process exits at service handoff or after four minutes.
        [ "$(cat /proc/sys/vm/swappiness 2>/dev/null)" = "$vm_swappiness" ] || \
            echo "$vm_swappiness" >/proc/sys/vm/swappiness 2>/dev/null
        [ "$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null)" = "$vm_watermark" ] || \
            echo "$vm_watermark" >/proc/sys/vm/watermark_scale_factor 2>/dev/null
        [ "$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)" = 65536 ] || \
            echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
        if [ -w /sys/class/kgsl/kgsl/page_reclaim_per_call ] && \
                [ "$(cat /sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null)" != 1024 ]; then
            echo 1024 >/sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null
        fi
        for early_memcg in /dev/memcg/memory.swappiness \
                /dev/memcg/system/memory.swappiness \
                /dev/memcg/apps/memory.swappiness \
                /dev/memcg/apps/*/memory.swappiness; do
            [ -w "$early_memcg" ] || continue
            case "$early_memcg" in
                /dev/memcg/memory.swappiness)
                    early_value=$vm_swappiness
                    ;;
                /dev/memcg/system/memory.swappiness)
                    early_value=0
                    ;;
                */active/memory.swappiness|*/systemserver/memory.swappiness|*/launcher/memory.swappiness)
                    early_value=0
                    ;;
                */inactive/memory.swappiness)
                    early_value=$vm_swappiness
                    ;;
                *)
                    early_value=$vm_swappiness
                    ;;
            esac
            [ "$(cat "$early_memcg" 2>/dev/null)" = "$early_value" ] || \
                echo "$early_value" >"$early_memcg" 2>/dev/null
        done
        sleep 1
        attempt=$((attempt + 1))
    done
    log_msg "early first-unlock guard finished attempts=$attempt assigned=system_server:$assigned_system_server,surfaceflinger:$assigned_surfaceflinger,systemui:$assigned_systemui,launcher:$assigned_launcher"
) &

wait_count=0
while [ ! -f /my_stock/etc/extension/sys_osense_memory_config.xml ] &&
      [ "$wait_count" -lt 30 ]; do
    sleep 1
    wait_count=$((wait_count + 1))
done

bind_over() {
    src="$MODDIR/payload/osense/$1"
    dst="/my_stock/etc/extension/$1"
    if [ -f "$src" ] && [ -f "$dst" ]; then
        mount --bind "$src" "$dst" 2>/dev/null && \
            log_msg "osense config bind mounted $1"
    fi
}

bind_over sys_osense_memory_config.xml
bind_over sys_osense_io_decisionmaker_config.xml
bind_over sys_osense_memory_decisionmaker_config.xml
bind_over sys_osense_feature_common_config.xml
bind_over sys_mm_swap_config.xml
bind_over sys_memory_nirvana_config.xml

# ============================================================================
# OPlus 特性表覆盖（增量派生，不再 bind 手工快照）
#
# 需要两类改动：
#   a) 补 oplus.software.audio.dolby_support —— 原厂表缺这一条，导致设置内
#      “声音与振动→音效”页的杜比区域被隐藏；补上后走原生 DMS HAL 链路。
#   b) 删掉四条依赖本机不存在的内核控制面的特性（Nirvana / HybridSwap 高载
#      暂停 / OSense 压缩），以及两条源手机 PSI 主动清理策略；它们对应的
#      OSense 场景规则已在 sys_osense_memory_decisionmaker_config.xml 置空。
#
# 旧实现是 bind 一份仓库内手工维护的 51 条快照。等价于把原厂表里所有未被
# 收录的条目一并删除（原厂 52 条，快照 48 条，差额恰好是上面这 6 条的净值），
# 且 OTA 换了原厂表之后会静默回退到旧快照、丢掉新增特性。
# 现在改为运行时读原厂表 + 声明式增删，只动这几条，其余原样保留。
# ============================================================================
FEATURE_TARGET=/my_stock/etc/extension/com.oplus.oplus-feature.xml
FEATURE_RUNTIME_DIR=/dev/coloros_port_fix
FEATURE_RUNTIME="$FEATURE_RUNTIME_DIR/com.oplus.oplus-feature.xml"

# 需要补上的特性
FEATURE_ADD='oplus.software.audio.dolby_support'
# 需要移除的特性：缺内核接口的三条 + 源手机 PSI 主动清理两条
FEATURE_DROP='oplus.software.memory_nirvana.enable
oplus.software.highload_pause_hyb_swapd
oplus.software.osense.compress.enable
oplus.software.psi_multi_window_clean
oplus.software.psi_miniprogram_clean'

apply_feature_override() {
    [ -f "$FEATURE_TARGET" ] || return 0
    grep -q '</oplus-config>' "$FEATURE_TARGET" || {
        log_msg "WARN: OPlus feature file has no </oplus-config>; override skipped"
        return 0
    }

    mkdir -p "$FEATURE_RUNTIME_DIR" 2>/dev/null
    if ! printf '%s\n' "$FEATURE_DROP" | awk -v add="$FEATURE_ADD" '
        NR == FNR { if ($0 != "") drop[$0] = 1; next }
        {
            for (name in drop) {
                if (index($0, "\"" name "\"") > 0) { next }
            }
            if ($0 ~ /<\/oplus-config>/ && !inserted) {
                printf "\t<oplus-feature name=\"%s\"/>\n", add
                inserted = 1
            }
            print
        }
    ' - "$FEATURE_TARGET" >"$FEATURE_RUNTIME" 2>/dev/null; then
        rm -f "$FEATURE_RUNTIME"
        log_msg "WARN: OPlus feature derive failed; keeping stock feature set"
        return 0
    fi

    # 健壮性闸门：原厂表可能已经含有某几条待删项、也可能已含 dolby，
    # 因此只校验方向与幅度，不写死数字。任一条件不满足就完全不 bind。
    src_count=$(grep -c '<oplus-feature ' "$FEATURE_TARGET" 2>/dev/null)
    out_count=$(grep -c '<oplus-feature ' "$FEATURE_RUNTIME" 2>/dev/null)
    case "$src_count$out_count" in *[!0-9]*) src_count=0; out_count=0 ;; esac
    if [ "$src_count" -lt 20 ] || [ "$out_count" -lt "$((src_count - 6))" ] ||
       [ "$out_count" -gt "$((src_count + 1))" ] ||
       ! grep -q "$FEATURE_ADD" "$FEATURE_RUNTIME"; then
        rm -f "$FEATURE_RUNTIME"
        log_msg "WARN: OPlus feature sanity check failed ($src_count -> $out_count); keeping stock"
        return 0
    fi

    chown 0:0 "$FEATURE_RUNTIME"
    chmod 0644 "$FEATURE_RUNTIME"
    chcon u:object_r:system_file:s0 "$FEATURE_RUNTIME" 2>/dev/null
    if mount --bind "$FEATURE_RUNTIME" "$FEATURE_TARGET" 2>/dev/null; then
        log_msg "OPlus feature set derived from stock ($src_count -> $out_count entries)"
    else
        log_msg "WARN: OPlus feature bind failed; keeping stock feature set"
    fi
}

apply_feature_override

# ============================================================================
# 一次性覆盖高通开机脚本的 swappiness=100：/vendor/bin/init.qcom.post_boot.sh
# 与 init.kernel.post_boot.sh 会在开机阶段把全局 swappiness 硬编码写回 100，
# 覆盖 post-fs-data 早期的写入。把补丁版 bind 到原路径，开机脚本
# 实际执行时写的就是 50，属于源头修复而非事后轮询。
# ============================================================================
bind_postboot_script() {
    name="$1"
    context="$2"
    src="$MODDIR/payload/bin/$name"
    dst="/vendor/bin/$name"
    if [ -f "$src" ] && [ -f "$dst" ]; then
        chown 0:0 "$src"
        chmod 0755 "$src"
        chcon "$context" "$src" 2>/dev/null
        mount --bind "$src" "$dst" 2>/dev/null && \
            log_msg "post_boot swappiness patch bind mounted $name"
    fi
}

bind_postboot_script init.qcom.post_boot.sh u:object_r:vendor_file:s0
bind_postboot_script init.kernel.post_boot.sh u:object_r:vendor_qti_init_shell_exec:s0

if [ -w /dev/memcg/system/memory.swappiness ]; then
    echo 0 >/dev/memcg/system/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/apps/active/memory.swappiness ]; then
    echo 0 >/dev/memcg/apps/active/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
    echo 0 >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
fi
log_msg "tuning early: global=$(cat /proc/sys/vm/swappiness 2>/dev/null) apps=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null) active=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null)"

# Bridge the ported ColorOS labels to this tablet's real Qualcomm/Oplus
# display-color manager before system_server loads OplusFeatureColorMode.
# Its native-gamut translator accepts render intents 256/259 (both become
# hardware mode 4); 0 is the standard/sRGB path.  The source-phone defaults
# are the reverse for the three visible labels, which makes 生动 land in sRGB
# and appear abnormally dim.
resetprop ro.oplus.display.colormode.vivid.renderintent 256
resetprop ro.oplus.display.colormode.soft.renderintent 0
log_msg "display color bridge: vivid=256(native) soft=0(standard)"

# The port advertises AudioX while its effect table provides Dolby.
resetprop ro.oplus.audio.effect.type dolby

# Dolby DAP is a global session-0 effect on the deep-buffer output.  PiliPlus
# explicitly requests AUDIO_OUTPUT_FLAG_FAST, which leaves its PCM stream on
# the primary output and bypasses DAP even though the UI and DAX parameter
# writes succeed.  Use OPlus' own fast-audio-effects policy (value 2 means
# force deep buffer) so the app's real playback stream shares the DAP chain.
DOLBY_ROUTE_TARGET=/system_ext/etc/Multimedia_Daemon_List.xml
DOLBY_ROUTE_RUNTIME="$MODDIR/runtime/Multimedia_Daemon_List.xml"
if grep -q '<name>com.example.piliplus</name>' "$DOLBY_ROUTE_TARGET" 2>/dev/null; then
    log_msg "Dolby route: PiliPlus already uses OEM policy"
elif grep -q '</fast-audio-effects>' "$DOLBY_ROUTE_TARGET" 2>/dev/null; then
    mkdir -p "${DOLBY_ROUTE_RUNTIME%/*}"
    sed '/<\/fast-audio-effects>/i\
        <name>com.example.piliplus</name>\
        <attribute>2</attribute>
' "$DOLBY_ROUTE_TARGET" >"$DOLBY_ROUTE_RUNTIME"
    chown 0:0 "$DOLBY_ROUTE_RUNTIME"
    chmod 0644 "$DOLBY_ROUTE_RUNTIME"
    chcon u:object_r:system_file:s0 "$DOLBY_ROUTE_RUNTIME" 2>/dev/null
    mount --bind "$DOLBY_ROUTE_RUNTIME" "$DOLBY_ROUTE_TARGET" 2>/dev/null && \
        log_msg "Dolby route: PiliPlus forced through OEM deep-buffer DAP chain"
else
    log_msg "ERROR: Dolby route policy target missing or incompatible"
fi

resetprop -p persist.sys.horae.enable 1

# Tango 32-bit compatibility. Bind only the translated 32-bit libdl entry.
resetprop -p persist.sys.tango_zygote32.start 0
stop zygote_tango

# AON's JNI originally hard-codes an ODM path, but Android's app linker
# namespace forbids that path. Bind the retained native stack plus the
# matching AIBoost and AIUnit QNN runtime into the AON app's permitted library
# directory. The QNN bundle includes the SM8650 HTP V75 unsigned skeleton,
# which is required for genuine NPU model initialization.
# The JNI is the recovered 2.4.59 Lenovo binary.  Inference and result
# callbacks remain entirely in the original AON implementation.  The root
# namespace bind below is only a seed; AON itself receives a private mount
# namespace, so the same files are attached again after its PID appears.
AON_LIB_TARGET=/my_product/app/AONService/lib/arm64
AON_LIB_PAYLOAD="$MODDIR/payload/aon-libs"
if [ -d "$AON_LIB_TARGET" ] && [ -f "$AON_LIB_PAYLOAD/libaiboost_jni.so" ] && [ -f "$AON_LIB_PAYLOAD/libaiboost.so" ] && [ -f "$AON_LIB_PAYLOAD/libQnnHtpV75Stub.so" ] && [ -f "$AON_LIB_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so" ]; then
    chown -R 0:0 "$AON_LIB_PAYLOAD"
    find "$AON_LIB_PAYLOAD" -type d -exec chmod 0755 {} \;
    find "$AON_LIB_PAYLOAD" -type f -name '*.so' -exec chmod 0644 {} \;
    chcon -R u:object_r:system_file:s0 "$AON_LIB_PAYLOAD" 2>/dev/null
    mount --bind "$AON_LIB_PAYLOAD" "$AON_LIB_TARGET" &&
        log_msg "AON native runtime mounted in app linker namespace"

    # AON is launched in an isolated mount namespace by the port.  Stage the
    # verified runtime in a namespace-neutral location, then stop each newly
    # created AON process for a moment and bind the complete directory (and
    # every child file) inside that process namespace before nativeCreate.
    # This fixes the real loader visibility problem; no model, frame, result,
    # or attention event is synthesized.
    AON_RUNTIME_STAGING=/data/local/tmp/coloros-aon-runtime-v2459
    AON_EXPECTED_JNI=80aedb964ca38112a003a8f77b72bca0bbf37ac221017e678e031f09cde428fc
    # Do not let the persistent staging directory retain the rejected alias
    # chain from older recovery builds. That chain loaded libaibstx.so from
    # init_qnn_delegate() and caused the AON null-PC crash seen in tombstone.
    rm -f "$AON_RUNTIME_STAGING/libaibstx.so" \
        "$AON_RUNTIME_STAGING/libaiboost_jni.so.pre-alias" 2>/dev/null
    staged_jni=$(sha256sum "$AON_RUNTIME_STAGING/libaiboost_jni.so" 2>/dev/null | awk '{print $1}')
    if [ "$staged_jni" != "$AON_EXPECTED_JNI" ] ||
       [ ! -r "$AON_RUNTIME_STAGING/libaiboost.so" ] ||
       [ ! -r "$AON_RUNTIME_STAGING/libaiboost_qnn_external_delegate.so" ] ||
       [ ! -r "$AON_RUNTIME_STAGING/libQnnHtpV75Stub.so" ] ||
       [ ! -r "$AON_RUNTIME_STAGING/cdsp/unsigned/libQnnHtpV75Skel.so" ]; then
        mkdir -p "$AON_RUNTIME_STAGING" 2>/dev/null
        cp -af "$AON_LIB_PAYLOAD/." "$AON_RUNTIME_STAGING/" 2>/dev/null
    fi
    chown -R 0:0 "$AON_RUNTIME_STAGING" 2>/dev/null
    find "$AON_RUNTIME_STAGING" -type d -exec chmod 0755 {} \; 2>/dev/null
    find "$AON_RUNTIME_STAGING" -type f -exec chmod 0644 {} \; 2>/dev/null
    chcon -R u:object_r:system_file:s0 "$AON_RUNTIME_STAGING" 2>/dev/null
    AON_LOADER_PIDFILE="$MODDIR/aon-namespace-loader.pid"
    old_aon_loader_pid=$(cat "$AON_LOADER_PIDFILE" 2>/dev/null)
    if [ -z "$old_aon_loader_pid" ] || ! kill -0 "$old_aon_loader_pid" 2>/dev/null; then
        "$MODDIR/bin/aon-namespace-loader.sh" "$MODDIR" &
        echo $! >"$AON_LOADER_PIDFILE"
        log_msg "AON private mount namespace loader started"
    fi
else
    log_msg "ERROR: AON native runtime payload or target missing"
fi

# Retain the two versioned QNN configuration records shipped beside the
# original AON stack. The port lacks /odm/etc/camera entirely; KernelSU's
# module overlay exposes this payload at that same read-only ODM path. No
# model, inference, or attention result is supplied by this module.
AON_QNN_CONFIG=/odm/etc/camera
QNN_GRAPH_CONFIG="$AON_QNN_CONFIG/aiboost_qnn_htp2.7.2_828413902960689361.bin"
QNN_CAPABILITY_CONFIG="$AON_QNN_CONFIG/aiboost_qnn_htp2.7.2_16382673562495086299.bin"
if [ -r "$QNN_GRAPH_CONFIG" ] && [ -r "$QNN_CAPABILITY_CONFIG" ]; then
    chmod 0644 "$QNN_GRAPH_CONFIG" "$QNN_CAPABILITY_CONFIG" 2>/dev/null
    chcon u:object_r:vendor_app_file:s0 "$QNN_GRAPH_CONFIG" 2>/dev/null
    chcon u:object_r:vendor_configs_file:s0 "$QNN_CAPABILITY_CONFIG" 2>/dev/null
    log_msg "AON original QNN ODM configuration available"
else
    log_msg "ERROR: AON original QNN ODM configuration missing"
fi

LIBDL_TARGET=/apex/com.android.runtime/lib/bionic/libdl.so
LIBDL_PATCH="$MODDIR/payload/libdl32.tango-cfi.so"
if [ -f "$LIBDL_PATCH" ] && [ -e "$LIBDL_TARGET" ]; then
    chmod 0644 "$LIBDL_PATCH"
    chown root:root "$LIBDL_PATCH"
    chcon u:object_r:system_lib_file:s0 "$LIBDL_PATCH" 2>/dev/null
    mount --bind "$LIBDL_PATCH" "$LIBDL_TARGET"
    log_msg "bound Tango-compatible 32-bit libdl"
else
    log_msg "ERROR: Tango libdl payload or target missing"
fi

# Restore the real sensor capability file before system_server reads features.
AMBIENT_TARGET=/my_product/etc/permissions/oplus.product.display_features.xml
AMBIENT_PAYLOAD="$MODDIR/payload/oplus.product.display_features.xml"
if module_enabled oplus_ambient_color_capability_fix; then
    log_msg "ambient color: dedicated module enabled, skipped"
elif [ -f "$AMBIENT_TARGET" ] && [ -f "$AMBIENT_PAYLOAD" ]; then
    chown 0:0 "$AMBIENT_PAYLOAD"
    chmod 0644 "$AMBIENT_PAYLOAD"
    chcon u:object_r:system_file:s0 "$AMBIENT_PAYLOAD" 2>/dev/null
    mount --bind "$AMBIENT_PAYLOAD" "$AMBIENT_TARGET" &&
        log_msg "ambient color capability mounted"
else
    log_msg "ERROR: ambient color capability target or payload missing"
fi

# The source ROM capture path is tuned for the source phone's mics.  Pin the
# TB710FU capture gains (speaker-mic TX_DEC 96 / ADC 16) at HAL level so every
# recording session natively applies them; this must not depend on the
# module-overlay layer, which is not guaranteed to cover /vendor on every boot.
MIXER_TARGET=/vendor/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml
MIXER_PAYLOAD="$MODDIR/vendor/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml"
if [ -f "$MIXER_TARGET" ] && [ -f "$MIXER_PAYLOAD" ]; then
    chown 0:0 "$MIXER_PAYLOAD"
    chmod 0644 "$MIXER_PAYLOAD"
    chcon u:object_r:system_file:s0 "$MIXER_PAYLOAD" 2>/dev/null
    mount --bind "$MIXER_PAYLOAD" "$MIXER_TARGET" 2>/dev/null &&
        log_msg "TB710FU capture gain mixer policy mounted"
else
    log_msg "ERROR: capture mixer policy target or payload missing"
fi

apply_serial_fix

# ============================================================================
# 关闭移植 ColorOS 在启动/渲染/业务路径上的 DEBUG/INFO 日志刷屏。
# 实测冷启动一次会刷出数千行 D 级日志（isLandscapeFullscreenEnabled、
# SurfaceView/Region/ReflectedParamUpdater/CompositionEngine、nativeloader、
# OplusTransition*/Synergy*/CompactWindow 等），这些日志除了拖慢对应线程外没有
# 价值。只抬高 log.tag 级别（D/V/I -> WARN），不改任何系统逻辑；W/E 告警保留。
# 原生库直打（SoundPool/SchedAssist/gc_priority 等）不经过 log.tag，无法在此压制。
# ============================================================================
for _logtag in \
    ActivityAdapter ActivityTaskManager AHAL AONLog APM_AudioPolicyManager \
    AppSenseClient AtlasEventUploadUtils AudioBoost AudioFlinger AudioManager \
    AudioPolicyManager AudioPolicyManagerExtImpl AudioRecord AudioTrack \
    BackgroundInstallControlService Bluetooth BufferPoolAccessor2.0 CCodecBuffers \
    CCodecConfig CallDisablePromptDatabaseHelper CommCenterService \
    CompatChangeReporter CompactWindowManagerService CompositionEngine \
    ConnectivityService ContinuousTransitionController CoreBackPreview Dcp \
    DeviceStatisticsService ExtensionsLoader FlexibleTaskController \
    bt_device_interop \
    FlexibleTaskTransitionController FlexibleWindowManagerService GameAudioEffects \
    HARDCODER HMA-OSS HeadTrackingProcessor IntentAnalyzer KindaLib \
    LocationManagerExtImpl MMListService MediaBufferGroup \
    OplusAppSwitchRuleInfo OplusAtlasMapsUtil OplusFlexibleDragToSplitAnimController \
    OplusFloorRefreshRateController OplusInputMethodUtil \
    OplusLog_DeviceKit_com.oplus.linker OplusLog_IPe OplusMPEG4Extractor \
    OplusMediaMonitor OplusScrollToTopManager OplusStartingWindowManager \
    OplusSurfaceFlinger OplusTransitionAnimationManager OplusTransitionController \
    OplusWifiPowerStatsManager OplusPreferencesHelperExt Panorama PatchPanel \
    ReflectedParamUpdater Region ResourcesManagerExtImpl SatelliteController \
    ScoreRequestHandler SensorPoseProvider SensorService \
    ShellTaskOrganizerExt ShortcutService SimpleC2Component skia SoundPool \
    Subsys-ScoreAppManager SurfaceView Synergy_EngineProcessor \
    Synergy_SynergyCoreService Task Transition ViewRootImplExtImpl \
    VirtualCommChannel WindowManager com.aiunit.aon jnicat nativeloader \
    vendor.oplus.hardware.wifi-aidl-service vendor.qti.camera.provider-service_64; do
    resetprop "log.tag.$_logtag" WARN 2>/dev/null
done
log_msg "debug/verbose log suppression applied ($(getprop 'log.tag.ActivityTaskManager'))"

log_msg "post-fs-data end"
