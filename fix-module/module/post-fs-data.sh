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
#   1) 开机阶段把系统根 swappiness 暂设为 5：zygote 进程迁入最终 memcg 前若仍为
#      ROM 默认 100，会把 system_server/launcher/systemui 也压进 zram；
#      此时先降全局，后续创建的 app cgroup 也会继承较低基线。
#   2) bind mount 覆盖 /my_stock 的 osense 配置：关闭主动后台换出，避免实测
#      应用切换期间出现压缩/换入风暴；ZRAM 仍由内核在真实压力下按需使用。
#      不创建、不扩容 ZRAM，兼容 8/12GB RAM 及未启用 ZRAM 的同型号设备。
#   3) 开机阶段先把 apps 父 memcg 设为 5，显著压低早期换出但保留 OOM 缓冲；
#      系统稳定后后台应用由 service 放宽到 10。
# ============================================================================

echo 5 >/proc/sys/vm/swappiness 2>/dev/null
echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
# The transplanted ROM can leave this at 80/120. On the 8 GB tablet that
# keeps kswapd scanning while MemAvailable is still around 1.8 GB. A/B tests
# show 20 preserves an early reclaim margin without sustained kswapd stalls;
# it is ratio-based and remains suitable for the 12 GB variant.
echo 20 >/proc/sys/vm/watermark_scale_factor 2>/dev/null
echo 0 >/proc/sys/vm/watermark_boost_factor 2>/dev/null
if [ -w /dev/memcg/apps/memory.swappiness ]; then
    echo 5 >/dev/memcg/apps/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/system/memory.swappiness ]; then
    echo 5 >/dev/memcg/system/memory.swappiness 2>/dev/null
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

bind_over sys_osense_memory_config.xml
bind_over sys_osense_io_decisionmaker_config.xml
bind_over sys_osense_memory_decisionmaker_config.xml

# ============================================================================
# 杜比音效特性开关（一次性覆盖）：原厂 OPlus 特性配置缺少
# oplus.software.audio.dolby_support，导致设置内“声音与振动→音效”页的
# 杜比区域被隐藏。补上该特性后，系统自带的 DolbyMainActivity/均衡器界面
# 恢复显示，走原生 DMS HAL 链路，无需第三方杜比 App。
# ============================================================================
FEATURE_TARGET=/my_stock/etc/extension/com.oplus.oplus-feature.xml
FEATURE_PAYLOAD="$MODDIR/payload/oplus/oplus-feature.xml"
if [ -f "$FEATURE_PAYLOAD" ] && [ -f "$FEATURE_TARGET" ]; then
    chown 0:0 "$FEATURE_PAYLOAD"
    chmod 0644 "$FEATURE_PAYLOAD"
    chcon u:object_r:system_file:s0 "$FEATURE_PAYLOAD" 2>/dev/null
    mount --bind "$FEATURE_PAYLOAD" "$FEATURE_TARGET" 2>/dev/null && \
        log_msg "oplus feature dolby_support bind mounted"
fi

# ============================================================================
# 一次性覆盖高通开机脚本的 swappiness=100：/vendor/bin/init.qcom.post_boot.sh
# 与 init.kernel.post_boot.sh 会在开机阶段把全局 swappiness 硬编码写回 100，
# 覆盖 post-fs-data 早期的写入。把补丁版 bind 到原路径，开机脚本
# 实际执行时写的就是 5，属于源头修复而非事后轮询。
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

wait_count=0
while [ ! -w /dev/memcg/apps/active/memory.swappiness ] &&
      [ "$wait_count" -lt 15 ]; do
    sleep 1
    wait_count=$((wait_count + 1))
done
if [ -w /dev/memcg/apps/active/memory.swappiness ]; then
    echo 5 >/dev/memcg/apps/active/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
    echo 5 >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
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
