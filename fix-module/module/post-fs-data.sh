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
# 内存扩展 / 压缩交换：已交还给原厂
#
# 这里原本有三段替代层，全部因为自建 GKI 内核缺少一加 BSP 能力而存在：
#   1. bridge_ram_expansion_ui + zram_* 助手 —— 自己读 Secure 键推导档位、
#      自己 reset/disksize/mkswap/swapon 标准 zram；
#   2. aclswap —— 开了 writeback 的 GKI zram 改名重编，外加 loop backing、
#      优先级 32767 抢占、以及最后把原厂 zram0 关掉；
#   3. oplus_sched_assist 壳 —— 伪造 /proc/oplus_scheduler/sched_assist ABI。
#
# 现在 aclaniakea_oplus_bsp 模块会在 post-fs-data 更早的时候装上真正的
# oplus_cpu_sched_sched_assist 与 oplus_mm_hybridswap_zram，
# /product/bin/init.oplus.nandswap.sh 在 boot_completed 时靠
# `[ -f /sys/block/zram0/hybridswap_core_enable ]` 自行探测并接管全部启用逻辑。
# 这个模块不该再碰 zram0 一根手指——碰了就是和原厂脚本抢同一个设备。
#
# 拆除记录见 kernel-compat/替代层拆除清单；aclswap 的历史实现留在
# post-fs-data.sh.orig_acl 与 git 历史里。
# ============================================================================

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

# oplus_mm_compat 壳已移除：它伪造 /proc/oplus_mem 并对外宣称"标准 zram 后端、
# 不支持 HybridSwap"，而现在真正的 hybridswap 已经在跑，这套说法本身就是错的。
# 一加原厂的 swappiness 调参走 /proc/oplus_healthinfo/swappiness_para，属于
# oplus_bsp_healthinfo，不在我们拿到的 23 个 ko 里；缺就让它缺，
# 原厂脚本的 configure_swappiness 静默跳过即可，不再自造一套假 ABI。

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
# 内存调优：已整体交还原厂
#
# 这里原本有一大段 VM/memcg 调参，全部是在「自建 GKI 上没有一加内存栈」的
# 前提下反复交叉实测调出来的：
#   * 全局与各级 memcg 的 swappiness 固定 50 / 保护组 0；
#   * min_free_kbytes=65536、watermark_scale_factor=10、boost_factor=0；
#   * KGSL shrinker 的 page_reclaim_per_call 从 38400 压到 1024；
#   * 一个 240 秒的首次解锁守卫，逐秒把上述值写回、并把 system_server /
#     surfaceflinger / systemui / launcher 塞进保护组。
#
# 前提已经不成立。22 个一加 BSP ko 现已开机自动加载，
# /product/bin/init.oplus.nandswap.sh 会自行接管 hybridswap 与整套
# /dev/memcg/* 参数（app_score、avail_buffers、zram_wm_ratio、
# swapd_shrink_parameter……），OSense 也重新拿回了按场景抬换出额度的权力。
# 我们那套手调值是在跟原厂策略抢同一批旋钮，留着只会让两边互相覆盖。
#
# 因此全部撤销，只保留下面一件原厂在本移植上确实做晚了的事：提前建出
# /dev/memcg/apps 及其三个标准子组。原因与内核无关——本移植的 AMS 要在
# Android 起来约 25 秒后才建 systemserver，而 nandswap 脚本在 boot_completed
# 时就要往 /dev/memcg/apps/memory.app_score 写值。只建目录、不写任何策略值，
# swappiness 一律留给原厂/OSense 决定。
# ============================================================================

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
    done
fi

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

# OSense 配置覆盖。这一批当初全是为了「本机没有 hybridswap/NAND 回写节点」而
# 把主动换出整体关掉。现在 oplus_mm_hybridswap_zram 已开机自动加载、
# /dev/memcg/memory.force_shrink_file 等节点实机确认齐全，凡是纯内核缺口的
# 覆盖一律不再 bind，整份交还原厂（原文件移到 payload/osense/
# .retired-handed-back-to-stock/ 备查，stock 原件在 .stock-baseline/）：
#   * sys_mm_swap_config.xml —— 唯一改动就是把 enabled/nandSwap/adaptiveOffset/
#     workStation/mmMiniPSwap 全关、disableHybSwapd 打开。原厂 nandswap 脚本把
#     /dev/memcg/memory.swapd_memcgs_param 的 ub_zram2ufs 额度写成 0，本来就是
#     留给 OSense 按场景抬的；我们关掉 OSense，等于亲手把 zram→UFS 回写卡死在 0。
#   * sys_osense_io_decisionmaker_config.xml —— 删的是 SCENE_RES_IO_PSI 的唯一
#     规则，理由「避免换出-换入循环」，同一个前提，一并撤。
#   * sys_osense_feature_common_config.xml —— 唯一改动是关 osenseCompressConfig
#     .Enable，后端就是 hybridswap，同一个前提。
#   * sys_osense_memory_config.xml —— 改的全是压缩/回写阈值，同一个前提。
# 只剩下面两份继续 bind，且都以 stock 为基线重做过最小 patch，
# 改动一律与内核无关，纯机型差异：
#   * sys_memory_nirvana_config.xml —— 只翻了 SupportMultiWin true→false
#     （平板分屏是常态，源手机的多窗口涅槃策略会误杀前台侧应用）。
#     EnableNoSwap / EnableLightNoSwap / EnableKswapdLoadDetect 全部改回 stock。
#   * sys_osense_memory_decisionmaker_config.xml —— 只删了
#     SCENE_RES_MEM_PSI_MULTIWINDOW 与 SCENE_RES_MEM_PSI_MINIPROGRAM 两组
#     共 5 条 rule（33→28），与下面 FEATURE_DROP 的两条特性成对生效。
#     其余 8 组曾被删掉的 compress/idle/frag/zram-clear/kswapd-highload 规则
#     已全部恢复。注意：这份**不要**用 ElementTree 重写，会丢掉原厂注释并把
#     CRLF 改成 LF；要改就从 .stock-baseline/ 拿原件做文本级 patch。
bind_over sys_memory_nirvana_config.xml
bind_over sys_osense_memory_decisionmaker_config.xml

# ============================================================================
# OPlus 特性表覆盖（增量派生，不再 bind 手工快照）
#
# 需要两类改动：
#   a) 补 oplus.software.audio.dolby_support —— 原厂表缺这一条，导致设置内
#      “声音与振动→音效”页的杜比区域被隐藏；补上后走原生 DMS HAL 链路。
#   b) 删掉两条源手机 PSI 主动清理策略。
#
# 旧实现是 bind 一份仓库内手工维护的 51 条快照。等价于把原厂表里所有未被
# 收录的条目一并删除（原厂 52 条，快照 48 条），且 OTA 换了原厂表之后会静默
# 回退到旧快照、丢掉新增特性。
# 现在改为运行时读原厂表 + 声明式增删，只动这几条，其余原样保留。
#
# 曾经还删过三条「依赖本机不存在的内核控制面」的特性，现已全部放回：
#   * oplus.software.highload_pause_hyb_swapd —— 前提是本机没有 HybridSwap。
#     oplus_mm_hybridswap_zram 已加载，原厂 init.oplus.nandswap.sh 自行接管，
#     mem→zram→UFS 三层实测全通，这条理由已彻底失效。
#   * oplus.software.osense.compress.enable —— OSense 压缩换出的后端就是
#     hybridswap，同上。与 sys_osense_feature_common_config.xml 里改回
#     true 的 osenseCompressConfig.Enable 成对生效。
#   * oplus.software.memory_nirvana.enable —— 内存涅槃走的是 memcg 的
#     force_shrink_file / force_shrink_anon / force_swapin / force_swapout
#     那组节点，实机确认现已全部存在（/dev/memcg/memory.force_shrink_file
#     可读写）。与 sys_memory_nirvana_config.xml 的 EnableKswapdLoadDetect
#     以及 decisionmaker 的 scene 1010 三者必须同进同退。
# 这三条是本轮最需要盯的回归点：若出现切换应用卡顿或换入风暴，先回滚它们。
# ============================================================================
FEATURE_TARGET=/my_stock/etc/extension/com.oplus.oplus-feature.xml
FEATURE_RUNTIME_DIR=/dev/coloros_port_fix
FEATURE_RUNTIME="$FEATURE_RUNTIME_DIR/com.oplus.oplus-feature.xml"

# 需要补上的特性
FEATURE_ADD='oplus.software.audio.dolby_support'
# 需要移除的特性：仅剩源手机 PSI 主动清理两条，与内核无关
FEATURE_DROP='oplus.software.psi_multi_window_clean
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
    # 现在只删 2 条、补 1 条，正常应为 52 -> 51；下限留到 -4 容错。
    src_count=$(grep -c '<oplus-feature ' "$FEATURE_TARGET" 2>/dev/null)
    out_count=$(grep -c '<oplus-feature ' "$FEATURE_RUNTIME" 2>/dev/null)
    case "$src_count$out_count" in *[!0-9]*) src_count=0; out_count=0 ;; esac
    if [ "$src_count" -lt 20 ] || [ "$out_count" -lt "$((src_count - 4))" ] ||
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
# /vendor/bin/init.qcom.post_boot.sh 与 init.kernel.post_boot.sh 的 bind 补丁
# 已撤销。当初补丁把开机脚本里硬编码的 swappiness=100 改成 50、
# watermark_scale_factor 改成 10，为的是压住「本机没有一加内存栈」时的
# 换入风暴。现在这层前提不存在了，原厂脚本写什么就是什么——
# 那才是 ColorOS 自己的策略基线，OSense 与 hybridswap 都是按它调的。
# 补丁副本仍留在 payload/bin/ 下以备回滚，只是不再挂载。
# ============================================================================


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
