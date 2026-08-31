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
# IRQ 默认亲和掩码：必须在 post-fs-data 就写，写晚了对已注册的中断毫无作用。
#
# /proc/irq/default_smp_affinity 只影响**此后**才注册的中断；已经存在的
# /proc/irq/N/smp_affinity 一个都不会被它改动。service.sh 在 boot_completed
# 之后再写，等于绝大多数驱动早就注册完了，这一行基本是空转。
#
# 为什么要改：源机 SM8850 的 bootargs 带 irqaffinity=0-1，在本机 1+4+1 的
# 六核上会把可迁移中断压到唯一那颗弱小核 CPU0（容量 379，中核 867、X4 1024）。
# 1e = CPU1~CPU4，即四颗 A720 中核。
#
# 原厂 init.kernel.post_boot-pineapple.sh 从不碰这个节点，所以不存在抢写。
# service.sh 里那一行保留作兜底（幂等，值相同时直接 return）。
# 撤销：写回 3f 即恢复"任意核"，写回 03 即恢复源机 bootargs 的行为。
# ============================================================================
if [ -w /proc/irq/default_smp_affinity ]; then
    _irqaff_before=$(cat /proc/irq/default_smp_affinity 2>/dev/null)
    echo 1e >/proc/irq/default_smp_affinity 2>/dev/null
    log_msg "irq default_smp_affinity ${_irqaff_before} -> $(cat /proc/irq/default_smp_affinity 2>/dev/null)"
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

# ----------------------------------------------------------------------------
# 小布语音唤醒：把 CUSTOM_VOICE_UI 的 vendor_uuid 对上 OVMS 实际发的那个
#
# OVMS（com.oplus.ovoicemanager.wakeup）按 /my_product/etc/OVMS_settings.xml
# 里的 <vender_uuid> 发起 loadPhraseSoundModel，值是
#   68ac2d41-e861-11e4-95ed-0002a5d5c51c
# 而本机 vendor 侧这份 resourcemanager 的 CUSTOM_VOICE_UI 段挂的是
#   dc90a99a-0aba-500e-81b5-79830d49d398
# （另外四份 sku 用的是高通出厂占位 c51c508a-…，只有 mtp 这份被改过。）
# uuid 对不上 ⇒ PAL 查不到平台信息 ⇒ -22 ⇒ STHAL 抛异常 ⇒
# SoundTriggerHalEnforcer 重启 HAL。ST HAL 就活在 audioserver 里，
# 每 5~6 秒重启一次，用户侧表现为**音频播放每隔几秒断一下**。
#
# ---- 走过的弯路（留着当反例）----
# 先前的版本是复刻 QC_VOICE_UI 新增一段 OPLUS_VOICE_UI。那条路必然失败：
# QC_VOICE_UI 走高通 SVA 解析器，会去解 SML 头，而小布的
# /my_product/etc/OVMS_1st_wakeup.bin 是 BreenoSpeech 私有格式
# （头 `20 00 00 00 …` / `33 00 00 00 …`，不是 SML 的 `c8 0c 18 00 …`），
# 于是卡在 `SVAInterface: QuerySoundModel: GetSoundModelHeader_ failed, err 6`。
# 当时据此断言「缺中文高通 SML 模型，DSP 不可能通」——**结论是错的**。
#
# ---- 2026-08-29 用一台 OPPO 手机（PKX110 / sun）实机对照推翻 ----
# 那台机上 DSP 完全跑通：
#   LOAD_PHRASE_MODEL ("7d5b82e9-…") -> 0 / START_RECOGNITION -> ok / RECOGNITION
# 而它送进去的模型 md5 e49c092d618ad9eeb2f993070355e93c、119663 字节，
# **和本机 /my_product/etc/breenospeech2/OVMS_1st_wakeup.bin 完全同一份**。
# 差别不在模型，在**走哪条解析路**：OPPO 走的是 CUSTOM_VOICE_UI ——
#   interface_plugin_lib="libcustomva_intf.so" + module_type="CUSTOM1"
# 这条路压根不解析 SML 头，模型直接交给 ADSP 里的 CUSTOM1 模块。
# 而 libcustomva_intf.so、libvui_{intf,dmgr,dmgr_client}.so、那四个 module id
# （0x0800104C/0x08001049/0x08001044/0x08001051）本机 vendor 一件不缺。
#
# 于是当时的修法是：把本机 CUSTOM_VOICE_UI 整段换成 OPPO 手机上那段
# （DUAL_MIC profile + vendor_uuid 改成 OVMS 实际发的 68ac2d41-…）。
#
# ---- 2026-08-29 晚：这个修法必须撤掉，方向是反的 ----
# 两家的 ADSP 各带各的自定义唤醒模块，不通用：
#   平板(联想固件)  capi_aispeech_wakeup / capi_aispeech_ecns
#   手机(OPPO 固件) capi_breeno_ecns、"Breeno Wakeup load model version V5.0"
# 把手机那份配置灌到平板，等于让联想的 aispeech 模块去吃 breeno 的图，
# 结果是 ADSP 每 1.2 秒崩一次：
#   qcom_q6v5_pas: fatal error received: EX:audio_process:0x2:GC_E00001D1
#   remoteproc0: handling crash #286
# 用户侧同样表现为播放每隔几秒断一下（成因和 uuid 不匹配那条不同，症状一样）。
#
# 另外「dc90a99a-… 是移植包留的占位、全机无人引用」也是错的：
# 它出现在联想原厂 vendor dump 的同一个文件里，是**联想自己唤醒词的 uuid**。
#
# 所以这里改成不动 PAL 配置，保持联想原样（dc90a99a + aispeech + SINGLE_MIC）。
# 要走通 DSP 的正确方向是反过来——改 OVMS 让它发 dc90a99a，
# 即 bind /my_product/etc/OVMS_settings.xml 的 <vender_uuid>。
# 那件事得在这里做（见下方 bind_app_config），因为 zygote 有独立的挂载
# 命名空间，开机后再 mount --bind 应用进程永远看不见。
# ----------------------------------------------------------------------------
# bind_vendor_config "$MODDIR/payload/audio/resourcemanager_pineapple_mtp.xml" \
#     /vendor/etc/audio/sku_pineapple/resourcemanager_pineapple_mtp.xml

# ----------------------------------------------------------------------------
# 让 OVMS 发联想自己的 vendor uuid，走联想 ADSP 里的 aispeech 唤醒模块
#
# ★ 这里不再有代码，而且 2.11.2 写在这里的"正确投递方式"也是错的，一并纠正。
#
# ---- 2026-08-29 实测：三条投递路子全废 ----
#   1) 开机后运行时 mount --bind —— zygote 有独立挂载命名空间，应用永远看不见；
#   2) post-fs-data 里 bind —— bind 本身成功，但 KernelSU 随后在整个
#      /my_product/etc 目录上盖 overlay，文件级 bind 被整体遮住；
#   3) 直接改本模块自带的 my_product/etc/OVMS_settings.xml（2.11.2 认为可行）——
#      **也是错的**。KernelSU 对所有普通应用进程卸载模块挂载：
#          system_server        my_product/etc overlay = 1
#          com.android.settings                        = 0
#          OVMS                                        = 0
#      应用读到的永远是 /my_product 分区原件，而它在只读 dm 上，写不了。
#      判据要读目标进程视角：grep -c "my_product/etc " /proc/<pid>/mounts。
#
# ---- DSP 一阶段已实测判死，这条线到此为止 ----
# 只换 vendor_uuid、保留联想全部图和 SINGLE_MIC profile（最小改动），
# PAL 全线打通：ParseSoundModel status 0 / VUI module type:CUSTOM1 /
# LoadSoundModel status 0 —— 模型确实加载成功了。
# 紧接着 ADSP 连环崩，20 秒 15 次：
#   qcom_q6v5_pas: fatal error received: EX:audio_process:0x2:GC_E00001D1
# 即联想 ADSP 的 capi_aispeech_wakeup 收得下小布的 BreenoSpeech blob 但解不了。
# 除非拿到"小布小布"的 aispeech 格式模型，DSP 无解。
#
# 所以 uuid 相关的一切到 Hook 侧收口：base-fix hook 默认永久走 BWV，
# 并把 SoundTrigger 模块枚举掐成空表，OVMS 全程不碰 HAL。
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# force-BWV 标记：DSP 走不通时把小布唤醒钉在 CPU 路径
#
# /data/local/tmp/ovoice_force_bwv 是 ColorOSVoiceWakeupBridge 里的开关：
# 存在则 WakeupService.s() 直接 setResult(-1003) 转 BWV(CPU) 路径。
#
# 立着是安全默认值：uuid 对不上会每 5~6 秒重启一次 ST HAL，而 ST HAL 活在
# audioserver 里，用户侧就是播放每隔几秒断一下。要试 DSP 通路时手工删掉它
# 再重启 OVMS 即可，不用重启机器。
#
# 判定 DSP 是否真通：dumpsys soundtrigger_middleware 里 LOAD_PHRASE_MODEL
# 出现 `-> <数字>`，且 dmesg 里 ADSP 崩溃计数不涨。
#   注意那个数字是 model handle 不是状态码，判成功要 grep -E '\-> [0-9]+'，
#   失败长这样 `-> ERROR: android.os.ServiceSpecificException`。
#   曾经因为按 `-> 0` 判成功，把一次本来成功的部署自动回滚掉。
#
# ---- 2026-08-29 更正：这个文件开关**从来没生效过** ----
# 应用域读不到 shell_data_file，Hook 里的 File.exists() 恒为 false，
# 于是一直跑的是"DSP 优先、失败再降级"，白搭 9 秒探测窗口。
# base-fix hook v1.1.16 起改为默认恒走 BWV，不再依赖这个文件；
# 这里仍然立起它，只是给 root 侧脚本和人工排查留一个可见状态位。
# 要做 DSP 对照实验用：settings put global aclaniakea_ovoice_dsp 1
# ----------------------------------------------------------------------------
if [ ! -e /data/local/tmp/ovoice_force_bwv ]; then
    : > /data/local/tmp/ovoice_force_bwv 2>/dev/null && \
        log_msg "ovoice force-BWV flag set (DSP first stage has no usable uuid)"
fi

# ============================================================================
# 改原厂 nandswap 脚本本身，取代 service.sh 里的运行时抢写
#
# /product/bin/init.oplus.nandswap.sh 的 configure_hybridswap_parameters() 有
# 两处对 8GB 机型不成立的地方，都是移植源机（一加 Pad 3 Pro 12/16G）的遗留：
#
#   A. 分档表最后一档是**开口**的（第 41-44 行）：
#          elif [ $mem_total -le 6291456 ]; then   "2000 1600 2000 1536"
#          else                                    "2200 1800 2200 1536"
#      8G 和 16G 共用 min=1800。16G 机空闲可用内存八九个 G，这门槛一辈子碰不到，
#      swapd 全程睡觉；本机 8G 实测空闲 MemAvailable 只有 1.78~2.13G，
#      连开六个应用的低点 1577M，**永远在门槛以下** —— swapd 进入永久追赶。
#
#   B. 第 212 行下发的是硬编码串，两列 ub_zram2ufs_ratio 全是 0：
#          "3 0 99 0 0 0 100 399 60 0 0 400 499 50 0 0 "
#      而同一个函数第 42 行明明按 MemTotal 算出了 zram2ufs_ratio=15，这个值
#      只被拿去算预留 dd 区大小（dd_mb_cnt），**从未写进内核**。后果是 8G eswap
#      挂上了、hybridswapd 也活着，却一页都没往 UFS 写过（ESU_C / reclaimin_cnt
#      恒为 0），zram 里的冷数据全程占着物理内存。这是原厂自己的漏发，不是我们
#      要"调优"什么 —— 补上等于让它按自己算出来的值执行。
#
# 为什么改脚本而不是事后写节点：
#   之前是在 service.sh 里等 nandswap 跑完再覆盖，但 perf HAL
#   （/odm/bin/hw/vendor-oplus-hardware-performance-V1-service）会在开机
#   +55~80 秒推它的场景值，而且是**整串重写** swapd_memcgs_param，把我们的 15
#   抹回 0。两边谁先谁后随机，v2.7.0 那次就输了。改脚本则是从一开始就正确，
#   而且开机头 50 秒（脚本执行前）之外的整个窗口都不再有竞争。
#   HAL 那一路无法用同样办法解决：它的参数硬编码在二进制里，没有配置文件
#   （strings 只有两个节点名，零个 .xml/.conf 路径），所以 service.sh 里那个
#   一次性补写块仍需保留作兜底。
#
# 安全措施：不预存整份副本（ROM 一更新就会脏），而是每次开机从**当前**原件
# 现场生成。两处特征串必须都能匹配上才动手，任一不中就整体跳过并记日志，
# 绝不盲改。撤销：删掉本函数调用，bind 消失后自动回到原件。
#
# ---- 2026-08-29 踩坑：改后的脚本**不能**直接从 /data 上 bind ----
# 第一版把产物放在 $MODDIR/payload/bin/ 里 bind 过去，bind 成功、权限和
# context 都对（-rwxr-xr-x u:object_r:nandswap_exec:s0），init 也确实找到了它，
# 但服务一次都没跑起来，整晚 hybridswap disable。审计日志：
#     avc: denied { execute_no_trans } path=".../init.oplus.nandswap.sh" dev="sda14"
#     avc: denied { nosuid_transition } scontext=u:r:init:s0 tcontext=u:r:nandswap:s0
#     op=security_bounded_transition seresult=denied
# sda14 就是 /data，挂载选项里带 **nosuid**。内核在 nosuid 文件系统上拒绝
# SELinux 域切换（除非新域被旧域 bound，这里不是），于是 init 转不进 nandswap 域。
#
# ⇒ **规律：init 要 exec 的文件，一律不能从 /data bind 过去。** 只被 read 的
#    配置文件（thermal conf、targetconfig.xml）不受影响，因为不涉及域切换。
#
# 解法：先挂一个我们自己的 tmpfs（手动 mount 默认不带 nosuid），产物放里面
# 再 bind。挂完立刻回读 /proc/mounts 确认没有 nosuid，带了就直接放弃并卸载，
# 宁可回到原厂脚本，也不能又白瞎一次开机。
# ============================================================================
patch_stock_nandswap() {
    _src=/product/bin/init.oplus.nandswap.sh
    _mnt=/dev/aclnsw
    _work="$_mnt/init.oplus.nandswap.sh"

    [ -f "$_src" ] || { log_msg "nandswap-patch: 原件不存在，跳过"; return 0; }

    # 特征 A：开口的最后一档。
    if ! grep -q 'threshold_wakeup_hybridswapd="2200 1800 2200 1536"' "$_src"; then
        log_msg "nandswap-patch: 未匹配到 8G/16G 共用档，原件已变，跳过"
        return 0
    fi
    # 特征 B：zram2ufs 两列写死为 0 的硬编码串。
    if ! grep -q '3 0 99 0 0 0 100 399 60 0 0 400 499 50 0 0 ' "$_src"; then
        log_msg "nandswap-patch: 未匹配到硬编码 swapd_memcgs_param，原件已变，跳过"
        return 0
    fi
    # 特征 C：内存扩展档位的 4/8/12 硬编码链。
    if ! grep -q 'if \[\[ "$prop_nandswap_size" == "4" \]\]; then' "$_src"; then
        log_msg "nandswap-patch: 未匹配到内存扩展档位链，原件已变，跳过 C"
    fi

    # ---- 载体：自建 tmpfs，必须不带 nosuid，否则 init 转不进 nandswap 域 ----
    mkdir -p "$_mnt" 2>/dev/null
    if ! grep -q " $_mnt " /proc/mounts 2>/dev/null; then
        mount -t tmpfs -o mode=0755 aclnsw "$_mnt" 2>/dev/null
    fi
    if ! grep -q " $_mnt " /proc/mounts 2>/dev/null; then
        log_msg "nandswap-patch: tmpfs 挂载失败，放弃（保持原厂脚本）"
        return 0
    fi
    if grep " $_mnt " /proc/mounts | grep -q nosuid; then
        log_msg "nandswap-patch: tmpfs 竟然带 nosuid，init 会转不进 nandswap 域，放弃并卸载"
        umount "$_mnt" 2>/dev/null
        return 0
    fi

    # A（已撤销，2026-08-30）：这里原本把 threshold_wakeup_hybridswapd 从原厂的
    #    "2200 1800 2200 1536" 降到 "1500 1200 1500 1536"，理由是"min 要低于本机
    #    满载低点才不会常驻触发"。三点法实测证明这是**长待机解锁卡顿的直接原因**，
    #    已改回原厂值。数据（息屏 320s / 解锁 128s 两段分开量）：
    #
    #      解锁段            1500/1200        2200/1800
    #      pgsteal_direct    332,747 (2599/s) 17,167 (188/s)   −93%
    #      allocstall          5,490   (43/s)    242 (2.7/s)   −94%
    #      pswpin            218,867          54,553           −75%
    #      息屏段
    #      pgsteal_kswapd    353,548          86,167           −76%
    #
    #    机制：pgsteal_direct 是**在分配路径上同步做的回收**，谁申请内存谁就卡在
    #    那里等。息屏段两种配置都是 0，解锁段桌面 +123MB、system_server +137MB
    #    一起要内存，缓冲垫只有 1200 时没有现成空闲页可给，只能自己下去刨 1.3GB。
    #    "少留缓冲少回收更省电"这个直觉在这里是反的：缓冲垫砍掉三分之一之后，
    #    息屏段的 kswapd 回收量反而涨了 4 倍（86k → 353k），因为水位一直贴着线
    #    在颠簸 —— 换出去马上又缺页换回来（息屏没人用却有 71,965 次 pswpin）。
    #
    #    连带修好的还有 zram→UFS 回写：它一直不触发不是"压力不够属正常"，而是
    #    MemAvailable(≈2.1G) 从来碰不到我们调低的那道闸。抬回 1800 之后
    #    reclaimin_cnt 0→13、ESU_C 0→38MB，zram 原始占用从 2.90G 降到 2.81G。
    # B：把 $zram2ufs_ratio 填进本该由它占据的两列。调用顺序已核对：
    #    main() 先跑 configure_zram_parameters（内含 configure_hybridswap_parameters）
    #    才跑 nandswap_init，所以第 212 行处这个变量一定有值；且第 18 行有默认 30 兜底。
    # C：内存扩展档位。原件只认 4/8/12：
    #        if   [[ "$prop_nandswap_size" == "4"  ]]; then swap_size_mb=4096
    #        elif [[ "$prop_nandswap_size" == "8"  ]]; then swap_size_mb=8192
    #        elif [[ "$prop_nandswap_size" == "12" ]]; then swap_size_mb=12288
    #        else swap_size_mb=4096; zram_increase_limit=2048; fi
    #    本机 persist.sys.oplus.nandswap.cfg 是 "4,6,8"，6 档没有对应分支；而且
    #    $prop_nandswap_size 是脚本**第 13 行、加载时**就 getprop 好的，真正干活
    #    的那次调用发生在开机 154ms 的 post-fs-data（第 123 行 sys.oplus.nandswap.init
    #    一次性闸门决定只有第一次生效，boot_completed 那次直接 exit）。结果无论
    #    用户在设置里选几 GB，都落进兜底分支 → disksize = 4096+2048 = 6144M，
    #    /proc/swaps 恒显示 6GB，而 eswap 那半边（第 142 行读 swapsize.curr）却
    #    老老实实按 8GB 走 —— 用户看到的"改成 8GB 重启后还是 6GB"就是这么来的。
    #    改法：在原链最前面插一段通用换算，2~16 GB 一律 size*1024；swapsize 读空
    #    时回落到 swapsize.curr（第 142 行证明这个属性在那一刻是读得到的）。
    #    zram_increase_limit 保持 0 不动 —— 原厂对"认识的档位"就是这么设计的，
    #    disksize 正好等于用户选的容量。
    sed -e 's|"3 0 99 0 0 0 100 399 60 0 0 400 499 50 0 0 "|"3 0 99 0 0 0 100 399 60 $zram2ufs_ratio 0 400 499 50 $zram2ufs_ratio 0 "|' \
        -e 's%if \[\[ "$prop_nandswap_size" == "4" \]\]; then%prop_nandswap_size=$(getprop persist.sys.oplus.nandswap.swapsize); if ! [ "$prop_nandswap_size" -ge 2 ] 2>/dev/null; then prop_nandswap_size=$(getprop persist.sys.oplus.nandswap.swapsize.curr); fi; if [ "$prop_nandswap_size" -ge 2 ] 2>/dev/null \&\& [ "$prop_nandswap_size" -le 16 ] 2>/dev/null; then swap_size_mb=$((prop_nandswap_size * 1024)); elif [[ "$prop_nandswap_size" == "4" ]]; then%' \
        "$_src" >"$_work" 2>/dev/null

    # 改完必须还是合法脚本，否则 nandswap 服务整个起不来，eswap 全没。
    # 注意用**相对**判据：原件第 30 行是 `function xxx()` 的 mksh/bash 写法，
    # 换成 dash 一类的 shell 检原件就会报错。只有"原件过、产物不过"才算是
    # 我们改坏了；原件本来就不过就别拿这个门去误杀好产物。
    if sh -n "$_src" 2>/dev/null && ! sh -n "$_work" 2>/dev/null; then
        log_msg "ERROR: nandswap-patch 产物语法检查未过，放弃 bind"
        rm -f "$_work"; umount "$_mnt" 2>/dev/null
        return 0
    fi
    # 改动必须真的在产物里，防止 sed 静默没匹配上。
    #
    # ★ 2026-08-30：这道门检的是**现在还在做的那几处改动**。A 段（改
    #   threshold_wakeup_hybridswapd）撤销之后，这里一度还在检 'mem_total -le 9437184'
    #   —— 它永远不可能出现在产物里，于是每次开机都判"缺少预期改动"、整个 bind
    #   被放弃，连带 B（zram2ufs 下发）和 C（内存扩展档位）一起失效。表现是
    #   "内存扩展改了重启没用"。改判据的时候，务必同步改这里。
    #
    # ★ 判据一律用 grep -F（固定串），别用正则：上一版写成
    #   grep -q 'prop_nandswap_size -ge 2'，而产物里实际是
    #   [ "$prop_nandswap_size" -ge 2 ] —— 中间夹着一个引号，永远匹配不上，
    #   于是又白白放弃了一次 bind。判据要从产物里原样抄，不要凭印象写。
    if ! grep -qF '399 60 $zram2ufs_ratio 0' "$_work" || \
       ! grep -qF 'prop_nandswap_size * 1024' "$_work"; then
        log_msg "ERROR: nandswap-patch 产物缺少预期改动，放弃 bind"
        rm -f "$_work"; umount "$_mnt" 2>/dev/null
        return 0
    fi
    # C 单独判：没匹配上只是内存扩展档位没修好，不该连累 A/B 一起放弃。
    if grep -qF 'swap_size_mb=$((prop_nandswap_size * 1024))' "$_work"; then
        _c_ok="档位通用换算已插入"
    else
        _c_ok="WARN 档位链未匹配，内存扩展仍会锁在 6144M"
    fi

    chown 0:0 "$_work"
    chmod 0755 "$_work"
    # 原件的 context 是 u:object_r:nandswap_exec:s0，init 靠它给这个服务定域，
    # 挂错 context 服务会直接起不来 —— 这里读原件的实际 context，不硬编码。
    _ctx=$(ls -Z "$_src" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if ($i ~ /^u:object_r:/) {print $i; exit}}')
    [ -n "$_ctx" ] || _ctx=u:object_r:nandswap_exec:s0
    if ! chcon "$_ctx" "$_work" 2>/dev/null; then
        log_msg "nandswap-patch: chcon $_ctx 失败，放弃（保持原厂脚本）"
        rm -f "$_work"; umount "$_mnt" 2>/dev/null
        return 0
    fi

    if mount --bind "$_work" "$_src" 2>/dev/null; then
        log_msg "nandswap-patch: 已 bind 改后脚本（tmpfs 载体，avail_buffers 保持原厂档；zram2ufs 由 \$zram2ufs_ratio 下发；$_c_ok；ctx=$_ctx）"
    else
        log_msg "WARN: nandswap-patch bind 失败，回落到 service.sh 的运行时写入"
        umount "$_mnt" 2>/dev/null
    fi
}

patch_stock_nandswap

# ---------------------------------------------------------------------------
# 无蜂窝机型的 telephony feature 排除清单 —— **2026-08-30 起停用**
#
# 原意：本机 ro.baseband=apq / ro.carrier=wifi-only，没有 modem，那套假 RIL
# （virtual-ril-daemon-0/1）永远无事可做，纯占内存；于是把
# payload/permissions/apq_excluded_telephony_features.xml 顶到 mbms 那份声明上，
# 把 android.hardware.telephony* 整组 feature 摘掉。
#
# ★ 代价出乎意料：**「通信共享」用不了了。**
# 那个功能是平板借手机的 SIM 打电话/收短信/上网，走蓝牙+WiFi 与手机通信，
# 本机确实不需要真 modem —— 但它需要 telephony **框架层**在册：
# feature 一摘，ColorOS 侧就认为本机没有电话能力，整条通信共享入口失效。
#
# 摘 feature 换来的收益是 com.android.phone + org.codeaurora.ims 那 225MB
# 冷内存（而且这俩因为 FLAG_PERSISTENT 根本杀不掉，见 memory: baseband-stock），
# 远不如通信共享值钱。故本行注释掉，feature 恢复在册。
#
# 连带效果：service.sh 的 stop_dead_telephony_stack() 有一道前置判据是
# 「telephony.ims feature 必须已消失」，feature 回来之后它会自动整段跳过，
# 不需要另外改开关。BASEBAND_STOP_LEVEL 同时也调回 0，双保险。
#
# 要再关掉：取消下面这行注释，并把 service.sh 的 BASEBAND_STOP_LEVEL 调回 2。
# ---------------------------------------------------------------------------
# bind_vendor_config "$MODDIR/payload/permissions/apq_excluded_telephony_features.xml" \
#     /vendor/etc/permissions/android.hardware.telephony.mbms.xml

# ----------------------------------------------------------------------------
# 手电亮度调节：这里**故意什么都不做**。
#
# 曾经在这里把 led:torch_0 / led:torch_3 的 brightness 重打标成
# vendor_sysfs_graphics 并 chmod 0666，想让 SystemUI 自己写。结论是走不通：
# 标签、DAC、sepolicy（file/dir/lnk_file 三类全放行，ksud 免重启生效）都到位
# 之后，platform_app 的 open() 依旧稳定 EACCES，而 dmesg 里一条 avc 都不落
# —— AOSP 对 appdomain 访问 sysfs 的这类拒绝有 dontaudit，日志上永远显得
# "策略没问题"，很容易把人带沟里。
#
# 现在的实现不需要动这两个节点的任何属性：Hook 把目标电流写进 SystemUI 自己
# 的 DE 数据目录，service.sh 挂的 inotifyd 听到后由 root 落笔。既然不需要，就
# 不留这层多余的重打标 —— 少改一处原厂状态，少一份出问题的可能。
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# 这里曾经拉起 bin/ui-memcg-protect.sh 去压 swappiness —— 已随 protect_ui_memcg()
# 一起删除，原因见 service.sh 里的说明：那是加法不是还原，而真正的病根
# （预建 memcg 目录导致 system_server EACCES）已经修掉了。
# ----------------------------------------------------------------------------


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

# ---------------------------------------------------------------------------
# per-app memcg 模式：本移植丢了 ro.config.per_app_memcg，导致 swapd 从不豁免前台
#
# ColorOS 的 memcg 打分有两条互斥的路，由 ro.config.per_app_memcg 二选一：
#
#   true  → AOSP libprocessgroup 的 UsePerAppMemcg() 成立，
#           createProcessGroup() 建出 /dev/memcg/apps/uid_<uid>/pid_<pid>；
#           MemcgControlManager.isFeatureSupport() = !mUsePerAppMemcg && mFeatureEnable
#           为 false，OSense 的包名管理器主动让位；
#           OplusOsenseCompressAction.setMemcgAppScore() 往
#           /dev/memcg/apps/uid_<uid>/memory.app_score 写 TOP=0 / BG=300。
#
#   空/false → 退回 OSense 包名模式，建出 /dev/memcg/apps/<pkgname>，
#           但 ROM 里**不存在**按包名路径写 app_score 的代码
#           （strings 扫过 classes{1,2,3}.dex 与 /system,/system_ext,/vendor,/odm
#             的 lib64 与 bin，唯一的拼接形式就是 "/dev/memcg/apps/uid_" + uid）。
#
# 本移植落在了后一条：99 个包名 memcg 建好了却没人打分，全员停在内核默认
# app_score=300，落进 swapd_memcgs_param 的 level 1，被按 ub_mem2zram_ratio=80
# 回收——正在用的前台应用也不例外。实测刚冷启的 QQ 被规划压掉 243620 页（950MB），
# 压完一滑动就 refault，这是掉帧的直接来源。
#
# 手工写 app_score=0 可让该 memcg 的 ub_mem2zram_ratio 立刻变 0、彻底退出
# calc_shrink_ratio 与 swapd_shrink_anon（已实测），所以链路本身是通的，
# 缺的只是这个开关。ro.* 属性只能在 post-fs-data 阶段 resetprop，
# 必须早于 zygote/system_server 起来。
#
# 配套的 persist 属性由下面一行保证（persist 的，写一次即可，但每次开机对齐更省心）。
# ---------------------------------------------------------------------------
resetprop ro.config.per_app_memcg true
resetprop -p persist.sys.oplus.hybridswap_app_uid_memcg true

# 第三道闸：setMemcgAppScore() 里 score==0（前台豁免）那一支还额外要求
# mFgMemcgScoreEnabled，链路是
#   NirvanaConfigHelper.<clinit>: sys.nirvana.enable_fg_memcg_score → DEFAULT_ENABLE_FG_MEMCG_SCORE（默认 false）
#   NirvanaManager.initCommon(): CompressAction.updateFgMemcgScoreEnable(isEnableFgMemcgScore())
# 不开这个，框架只会写 BG 的 300、永远不会写 FG 的 0（已实测：手工把 chrome 打成
# 999，切后台被框架改回 300，但切前台不会变成 0）。
# 静态初始化在 system_server 首次加载该类时发生，post-fs-data 阶段设置足够早。
# 注：/my_stock/etc/extension 里的 memoryReleasePolicyConfig 若显式给了
# EnableFgMemcgScore，会覆盖此属性。
resetprop sys.nirvana.enable_fg_memcg_score true

# ============================================================================
# 这里曾经预建 /dev/memcg/apps/{active,systemserver,launcher} 三个组 —— 已删除。
#
# ★ 那段代码正是"原厂分组从来没跑起来"的直接原因。
#
# 它以 root 身份 mkdir，然后只 chown 了**目录**，没管目录里的文件。cgroup 文件
# 的属主跟着创建进程走，于是 cgroup.procs 是 root:root、0644：
#
#   本机（预建后）  active/cgroup.procs        root:root      ← 写不进去
#   原厂           active/cgroup.procs        system:system
#   本机 inactive/ 与各包名组（system_server 自己建的）  system:system  ← 正常
#
# system_server 跑在 system uid，往 root 拥有的 0644 文件里写自然是 EACCES。
# logcat 里那条实锤：
#   E MemoryOperationUtils: Failed to update pid = 24825 in group = active,
#     msg:/dev/memcg/apps/active/cgroup.procs: open failed: EACCES
#
# 也就是说 OPlus 的分组机制一直在正常尝试按 sys_osense_memcg_config.xml 的规则
# 把桌面搬进 active、把 system_server 搬进 systemserver，**每次都被我们自己预建
# 的目录挡在门外**，只好留在根 memcg 里。
#
# 原厂根本不预建这三个组：需要的时候由 system_server 自己 mkdir，属主自然就对。
# （原厂上连 launcher 这个组都不存在 —— 配置里没有 groupName="launcher" 的规则，
# 那个组纯粹是我们凭空造的。）
#
# 所以正确做法是**什么都不做**。/dev/memcg/apps 由 init.rc 建好并 chown 成
# system:system，剩下的交给框架。
# ============================================================================

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

# AON 的两份 QNN 配置由模块以 /odm/etc/camera 覆盖提供（移植包整个缺这个目录）。
#
# ★ 这里**故意不再检查 /odm/etc/camera 是否存在**。
#   KernelSU（和 Magisk 一样）是先跑模块的 post-fs-data.sh、之后才挂模块文件，
#   所以在这个阶段 /odm/etc/camera 必然还不存在 —— 老代码在这里判断可读性，
#   于是每次开机都稳定打出一条 "ERROR: AON original QNN ODM configuration
#   missing"，而实际上文件挂载后一直是好的。那条 ERROR 是假警报，白白误导排查。
#   真正的校验挪到 service.sh（那时挂载已经完成）。
#
# ★ 也不再对这两个文件 chcon。
#   老代码想把它们改成 vendor_app_file / vendor_configs_file（对齐原厂 ODM 的
#   标签），但那次 chcon 打在还不存在的路径上，从来没生效过。实测挂载后的标签
#   是 system_file，AON 进程正常运行、dmesg 里 0 条相关 avc —— 也就是说
#   system_file 本来就够用。既然如此就别去动它：改标签有让 app 域读不到的风险，
#   换来的只是"看起来更像原厂"。

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

# ============================================================================
# 刷新率配置：面板能力声明必须包含 144。
#
# 这份配置 2026-08-06 曾被挪进 pen-bridge，并在那里把 ratemagic 从
# 8750R60_90_120_144 改成 8750R60_90_120（去掉 144），本意是消除
# 120/144 之间的闪屏。实际后果相反：文件里的图例仍是 4(144Hz)，
# 且 800 多个条目（launcher / systemui / uxdesign 在内）的 rateId 都在用 4，
# OplusRefreshRatePolicyImpl 拿 4 去 ratemagic 声明的速率集合里解析不出来，
# 整块落进降级路径——实测面板被长期钉在 60Hz，SF 侧一度出现
# render=[0.00 Hz, 90.00 Hz] 这种 DisplayModeDirector 六条投票里
# 根本没人投出来的怪值（90 恰好是残缺 ratemagic 里有的速率）。
#
# 改回 8750R60_90_120_144 后实测：activeMode 变成 id=1 / 144.00 Hz、
# measured_fps 115，桌面与浏览器都真正跑到 144，切 App 时策略
# primaryRanges 全程稳定在 [144,144] 不再抖动。
#
# rateId 取值：0=未指定 1=90Hz 2=60Hz 3=120Hz 4=144Hz。文件图例只列了
# 0/1/2/4，但 3 是合法的——com.oplus.ipemanager 笔设置页的 3-1-2-3 是
# 原厂让它跑 120Hz，不要当成非法值去"修"。
#
# 它是整机显示基线，与笔无关（笔在用时的 120Hz 由原厂
# OplusRefreshRatePolicyImpl 依 settings_enable_oppo_pencil 自行投票），
# 所以放回 fix 模块。
# ============================================================================
REFRESH_TARGET=/my_product/etc/refresh_rate_config.xml
REFRESH_PAYLOAD="$MODDIR/payload/refresh_rate_config.tb710fu.xml"
if [ -f "$REFRESH_TARGET" ] && [ -f "$REFRESH_PAYLOAD" ]; then
    chown 0:0 "$REFRESH_PAYLOAD"
    chmod 0644 "$REFRESH_PAYLOAD"
    chcon u:object_r:system_file:s0 "$REFRESH_PAYLOAD" 2>/dev/null
    if mount --bind "$REFRESH_PAYLOAD" "$REFRESH_TARGET" 2>/dev/null; then
        log_msg "refresh rate config mounted (ratemagic 8750R60_90_120_144)"
    else
        log_msg "WARN: refresh rate config bind failed"
    fi
else
    log_msg "ERROR: refresh rate config target or payload missing"
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
# 2026-08-27：补 /odm/lib64 缺库，修 gameopt HAL 的开机崩溃循环。
#
# 现象：init.svc.gameopt_hal_service-1-0 一直是 restarting，从开机循环到关机。
#   F linker: CANNOT LINK EXECUTABLE "/odm/bin/hw/vendor.oplus.hardware.gameopt-service":
#             library "android.frameworks.stats-V1-ndk.so" not found
# 根因：/odm/bin/hw 下的可执行文件走 vendor linker namespace，搜索路径只有
#   /odm/lib64 与 /vendor/lib64。这两个库在本机只存在于 /system/lib64，
#   一加原机的 vendor 侧带了，移植底包的 vendor 分区没有 → 永久崩溃循环。
# 为什么模块里早就放了库却没用上：module/odm/lib64/ 从来没被挂载过。
#   本机的 KSU/hybrid_mount 只覆盖了 /odm/etc，odm 的其它子目录一个没上，
#   和上面 mixer_paths 那段注释说的是同一个毛病（模块 overlay 层不保证覆盖）。
# 修法：/odm 是只读 EROFS，加不了新文件，只能叠一层**只读** overlay。
#   lowerdir 把 upper 放前、原 /odm/lib64 放后，原有 71 个文件一个不动，只多出新库。
#   只读 overlay 不需要 upperdir/workdir，也就不需要 tmpfs 的 xattr 支持，
#   和系统自己给 /vendor/lib64 挂 opex 用的是同一个套路。
# 只搬 gameopt 真正需要的两个：libaiboost.so 由上面 AON 那段单独处理，不重复搬。
# 已实机验证：挂载后 HAL 从 restarting 变 running，并成功注册进 servicemanager
#   （384 vendor.oplus.hardware.gameopt.IGameOptHalService/default）。
# 遗留：HAL 起来后仍报 "failed to open ofb_game_path" / "open es4g ctrl node failed"，
#   因为 /proc/game_opt 这组内核节点还没有，那是内核侧 ko 的事，另案。
# 安全网：挂完立刻数文件数并回读一个原有库，只要发现原文件被遮住就立刻 umount 还原。
# ============================================================================
ODMLIB_SRC="$MODDIR/odm/lib64"
ODMLIB_OVL=/dev/coloros_fix_odmlib64
ODMLIB_WANT="android.frameworks.stats-V1-ndk.so libc++_runtime_fix.so"
if [ -d "$ODMLIB_SRC" ] && [ -d /odm/lib64 ]; then
    _odm_before=$(ls /odm/lib64 2>/dev/null | wc -l)
    _odm_ref=$(ls /odm/lib64/*.so 2>/dev/null | head -1)
    _odm_lbl=$(ls -Zd "$_odm_ref" 2>/dev/null | awk '{print $1}')
    [ -z "$_odm_lbl" ] && _odm_lbl=u:object_r:vendor_file:s0
    rm -rf "$ODMLIB_OVL"
    _odm_staged=0
    if mkdir -p "$ODMLIB_OVL"; then
        for _l in $ODMLIB_WANT; do
            if [ -f "$ODMLIB_SRC/$_l" ] && [ ! -e "/odm/lib64/$_l" ]; then
                cp "$ODMLIB_SRC/$_l" "$ODMLIB_OVL/" 2>/dev/null && _odm_staged=$((_odm_staged+1))
            fi
        done
    fi
    if [ "$_odm_staged" -gt 0 ]; then
        chown 0:0 "$ODMLIB_OVL"/*.so 2>/dev/null
        chmod 0644 "$ODMLIB_OVL"/*.so 2>/dev/null
        chcon "$_odm_lbl" "$ODMLIB_OVL" 2>/dev/null
        chcon "$_odm_lbl" "$ODMLIB_OVL"/*.so 2>/dev/null
        if mount -t overlay coloros_fix_odmlib \
                -o ro,lowerdir="$ODMLIB_OVL:/odm/lib64" /odm/lib64 2>/dev/null; then
            _odm_after=$(ls /odm/lib64 2>/dev/null | wc -l)
            if [ "$_odm_after" -lt "$_odm_before" ] || [ ! -r "$_odm_ref" ]; then
                umount /odm/lib64 2>/dev/null
                rm -rf "$ODMLIB_OVL"
                log_msg "ERROR: /odm/lib64 overlay hid stock libs, rolled back ($_odm_before -> $_odm_after)"
            else
                log_msg "/odm/lib64 overlay mounted: +$_odm_staged libs ($_odm_before -> $_odm_after)"
            fi
        else
            rm -rf "$ODMLIB_OVL"
            log_msg "ERROR: /odm/lib64 overlay mount failed"
        fi
    else
        rm -rf "$ODMLIB_OVL"
        log_msg "/odm/lib64 overlay skipped (nothing missing)"
    fi
fi

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

# ---------------------------------------------------------------------------
# 2026-08-27 追加：按实测 logcat 普查补的第二批。
# 普查方法：logcat -b all -d -v long -t 12000，按 tag 计数排序。
# 当时 main buffer 的 Logspan 只有 2 分 53 秒（累计写入 990MB），即缓冲区被刷屏
# 冲刷到只剩 3 分钟历史，排障时根本翻不到现场。下面这批占了采样窗口约 6 成。
# 只收 V/D/I 级的刷屏源；W/E 级的（libc、ServiceManagerCppClient、HwcComposer、
# GC13A2、qsap_voiceui、horae、linker）抬到 WARN 也压不掉，且有诊断价值，不动。
# jank_cuj_events_* 是掉帧 CUJ 埋点，正在排查掉帧，故意保留。
# 注意：OplusGpuMinidump 的根因是某 vendor 守护进程以 1Hz 轮询一个不存在的文件
# （"gpu minidump control openfile error ... sleep for a while!"），这里只是消掉
# 日志，那个空转还在。connection::networking（互联 QoE 探测，占 14%）和
# DRS_LOG_*、vendor.qti.bluetooth@1.1-* 的 tag 含 : @ / [ 等非法字符，
# 属性名不接受，无法用 log.tag 压制。
# ---------------------------------------------------------------------------
for _logtag in \
    PowerManagerService surfaceview_callback mgulk AICCTModule OplusGpuMinidump \
    OplusWindowManagerService SettingsShellCmd flags_health_check midasd \
    VendorWifiService ScorerUtils OplusWifiPower_TriggerCenter \
    BluetoothQualityReportNativeInterface Osense-BaseDecisionMaker \
    android.hardware.power.stats-impl.oplus vui_dmgr_server AudioPolicyService \
    CamX ThermalEngine btm_acl SDM sysui_multi_action SavePaintHelper \
    AdapterProperties ShellSubscriberWorkerThread; do
    resetprop "log.tag.$_logtag" WARN 2>/dev/null
done
log_msg "debug/verbose log suppression applied ($(getprop 'log.tag.ActivityTaskManager'))"

log_msg "post-fs-data end"
