#!/usr/bin/env bash
# TB710FU：备份原厂引导分区 → 用自建 Image 重打 boot.img → 刷入 → 校验。
# 每一步都可单独跑：backup / repack / flash / verify / rollback
#
# 前提：平板已 adb 连接、KernelSU 可用（备份要 root）、bootloader 已解锁。
# 这台机器是 Android 14 GKI 布局：boot.img 里只有内核，
# 通用 ramdisk 在 init_boot 里——所以换内核只是重打 boot，不碰 ramdisk。
set -u
S=${S:-$HOME/oplus-src}
W=${W:-/run/media/ACLaniakea/IXUNICS/pad}
BK=$W/kernel-backup
IMAGE=${IMAGE:-$S/out-ref/arch/arm64/boot/Image}
TOOLS=$S/tools
STAMP=$(date +%Y%m%d-%H%M)

die() { echo "!! $*" >&2; exit 1; }
need_adb() { adb get-state >/dev/null 2>&1 || die "adb 里没有设备"; }

slot() { adb shell getprop ro.boot.slot_suffix | tr -d '\r'; }

do_backup() {
    need_adb; mkdir -p "$BK"
    local sfx; sfx=$(slot)
    echo "当前 slot: ${sfx:-(非 A/B)}"
    for p in boot init_boot dtbo vendor_boot; do
        local part="$p$sfx"
        adb shell "su -c 'ls /dev/block/by-name/$part'" >/dev/null 2>&1 || { echo "  跳过 $part（不存在）"; continue; }
        adb shell "su -c 'dd if=/dev/block/by-name/$part of=/data/local/tmp/$part.img bs=4M'" 2>/dev/null
        adb pull "/data/local/tmp/$part.img" "$BK/$part-stock-$STAMP.img" >/dev/null || die "拉取 $part 失败"
        adb shell "su -c 'rm -f /data/local/tmp/$part.img'"
        echo "  备份 $part -> $BK/$part-stock-$STAMP.img  $(sha256sum "$BK/$part-stock-$STAMP.img" | cut -c1-16)"
    done
    ln -sfn "$BK/boot$sfx-stock-$STAMP.img" "$BK/boot-stock-latest.img"
    echo "回滚用的原厂 boot：$BK/boot-stock-latest.img"
}

do_repack() {
    local stock=${1:-$BK/boot-stock-latest.img}
    [ -f "$stock" ] || die "找不到原厂 boot：$stock（先跑 backup）"
    [ -f "$IMAGE" ] || die "找不到 Image：$IMAGE"
    rm -rf "$BK/unpack"; mkdir -p "$BK/unpack"
    python3 "$TOOLS/unpack_bootimg.py" --boot_img "$stock" --out "$BK/unpack" --format mkbootimg \
        > "$BK/unpack/args.txt" || die "拆包失败"
    echo "--- 原厂 boot 头 ---"; cat "$BK/unpack/args.txt"; echo
    # 原样沿用原厂头参数，只把 kernel 换成我们的 Image
    local args; args=$(sed "s#--kernel [^ ]*#--kernel $IMAGE#" "$BK/unpack/args.txt")
    # shellcheck disable=SC2086
    python3 "$TOOLS/mkbootimg.py" $args --out "$BK/boot-new-$STAMP.img" || die "打包失败"
    ln -sfn "$BK/boot-new-$STAMP.img" "$BK/boot-new-latest.img"
    ls -l "$BK/boot-new-$STAMP.img"
    echo "对比原厂大小：$(stat -c %s "$stock") -> $(stat -c %s "$BK/boot-new-$STAMP.img")"
}

do_flash() {
    local img=${1:-$BK/boot-new-latest.img}
    [ -f "$img" ] || die "找不到要刷的镜像：$img"
    need_adb
    echo "即将重启到 fastboot 并刷 boot：$img"
    adb reboot bootloader; sleep 6
    fastboot devices | grep -q . || die "fastboot 里没有设备"
    fastboot getvar current-slot 2>&1 | head -1
    fastboot flash boot "$img" || die "刷入失败——设备仍在 fastboot，可用 rollback 恢复"
    fastboot reboot
}

do_verify() {
    need_adb
    echo "--- uname ---"; adb shell uname -a
    echo "--- KernelSU ---"; adb shell "su -c 'echo root ok; /data/adb/ksud -V 2>/dev/null || true'"
    echo "--- 未加载的 vendor 模块（应为空）---"
    adb shell "su -c 'dmesg | grep -iE \"module .* (has|verification failed|disagrees)\" | head -20'"
    echo "--- 已加载模块数 ---"; adb shell "su -c 'lsmod | wc -l'"
    echo "--- 温度/传感器 ---"; adb shell "su -c 'for z in /sys/class/thermal/thermal_zone*/type; do echo -n \"\$(cat \$z) \"; done'" | tr ' ' '\n' | grep -c .
}

do_rollback() {
    local img=${1:-$BK/boot-stock-latest.img}
    [ -f "$img" ] || die "找不到原厂备份：$img"
    if adb get-state >/dev/null 2>&1; then adb reboot bootloader; sleep 6; fi
    fastboot devices | grep -q . || die "请手动进 fastboot（音量下+电源）"
    fastboot flash boot "$img" && fastboot reboot
}

case "${1:-}" in
    backup)   do_backup ;;
    repack)   shift; do_repack "$@" ;;
    flash)    shift; do_flash "$@" ;;
    verify)   do_verify ;;
    rollback) shift; do_rollback "$@" ;;
    all)      do_backup && do_repack && do_flash ;;
    *) echo "用法: $0 {backup|repack|flash|verify|rollback|all}"; exit 1 ;;
esac
