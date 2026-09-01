#!/system/bin/sh
# CryptoEng split proxy：10003 本地实现，其余命令交给已验证的软件 HAL。
LOG=/data/local/tmp/ce_setup.log
echo "=== setup $(date) ===" >> $LOG
MODDIR=/data/adb/modules/cryptoeng_hal_fix
SRC=$MODDIR/odm/bin/hw/vendor-oplus-hardware-cryptoeng-service
BACKING_SRC=$MODDIR/bin/cryptoeng-backing
DST=/odm/bin/hw/vendor-oplus-hardware-cryptoeng-service
CE=/mnt/ce_hal/ce_service
BACKING=/mnt/ce_hal/ce_backing

# 1. tmpfs 承载（非 nosuid，避免 nosuid_transition）
mkdir -p /mnt/ce_hal
mountpoint -q /mnt/ce_hal || mount -t tmpfs -o mode=0755 tmpfs /mnt/ce_hal
cp -f $SRC $CE
cp -f $BACKING_SRC $BACKING
chmod 755 $CE
chmod 755 $BACKING
chcon u:object_r:hal_cryptoeng_oplus_exec:s0 $CE
chcon u:object_r:hal_cryptoeng_oplus_exec:s0 $BACKING
echo "stage: $(ls -laZ $CE 2>&1)" >> $LOG
echo "backing: $(ls -laZ $BACKING 2>&1)" >> $LOG

# 2. 存储目录属主（init 以 system 运行）
mkdir -p /data/vendor_de/0/cryptoeng /mnt/vendor/persist/data/cryptoeng
chown -R system:system /data/vendor_de/0/cryptoeng /mnt/vendor/persist/data/cryptoeng
chcon -R u:object_r:cryptoeng_data_file:s0 /data/vendor_de/0/cryptoeng 2>/dev/null
chcon -R u:object_r:vendor_persist_data_file:s0 /mnt/vendor/persist/data/cryptoeng 2>/dev/null

# 3. bind mount 覆盖 /odm 二进制
umount $DST 2>/dev/null
mount --bind $CE $DST
echo "odm: $(ls -laZ $DST 2>&1)" >> $LOG

# 4. 停用系统 HAL 服务（由 split 代理替代，避免服务名冲突）
stop hal_cryptoeng_oplus 2>/dev/null
pkill -f ce_proxy_certpin 2>/dev/null
pkill -f ce_backing 2>/dev/null
sleep 1

# 5. 复制 split 代理并启动（代理会自行拉起 backing 并注册 default）
cp -f $MODDIR/bin/ce_proxy_certpin /mnt/ce_hal/ce_proxy_certpin
chmod 755 /mnt/ce_hal/ce_proxy_certpin
chcon u:object_r:hal_cryptoeng_oplus_exec:s0 /mnt/ce_hal/ce_proxy_certpin
setsid /mnt/ce_hal/ce_proxy_certpin > /data/local/tmp/ce_proxy.out 2>&1 < /dev/null &
echo "proxy: $(ps -A | grep ce_proxy | head -1)" >> $LOG
echo "=== done ===" >> $LOG
