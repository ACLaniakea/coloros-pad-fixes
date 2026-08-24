#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR
export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

"$MODDIR/scheduler/install-interface.sh" service >/dev/null 2>&1

# 等 ROM 的 post-boot Power/Horae 脚本完成后再落一次最终基线，避免早期值被
# 后置脚本覆盖。service.sh 自身是独立子进程，不阻塞 system_server；命中后退出。
count=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 120 ]; do
    sleep 1
    count=$((count + 1))
done
sleep 15

# 若 Scene 已先调用某个模式，保持该模式；否则以均衡模式完成初始化。
mode=$(cat /data/adb/sm8650q-scene-scheduler/current_mode 2>/dev/null)
case "$mode" in
    powersave|balance|performance|fast) sh /data/powercfg.sh "$mode" >/dev/null 2>&1 ;;
    *) sh /data/powercfg.sh init >/dev/null 2>&1 ;;
esac
