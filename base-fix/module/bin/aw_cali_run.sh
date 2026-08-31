#!/system/bin/sh
# 智能功放（AWINIC aw882xx）Re 校准辅助脚本
#
# 用途：移植后 /mnt/vendor/persist/factory/audio/aw_cali.bin 可能是空的（全空格），
#       此时功放的振幅保护缺少每台实测的直流阻抗基准，大音量下容易破音。
#
# 重要：Re 是逐台实测数据，不能从别的机器复制，必须在本机跑。
#
# 前提：校准需要扬声器通路处于激活状态，也就是**必须有音频正在播放**，
#       并且扬声器不能被遮挡。校准过程本身由 DSP 注入信号完成，与系统音量无关，
#       可以先把媒体音量调到 0。
#
# 用法：先播放任意音频，然后 su -c /data/adb/modules/<模块名>/bin/aw_cali_run.sh

CALI=/vendor/bin/aw882xx_cali
F=/mnt/vendor/persist/factory/audio/aw_cali.bin

[ -x "$CALI" ] || { echo "找不到 $CALI，本机不支持"; exit 1; }

echo "== 校准前 =="
cat "$F" 2>/dev/null; echo
echo "== 合法阻抗区间 =="
"$CALI" all get_re_range 2>&1 | tail -1
echo "== 当前扬声器状态（需要正在放音，否则 get st failed）=="
"$CALI" all get_spkr_st 2>&1 | tail -2
echo "== 开始校准（3000ms）=="
"$CALI" all cali_all 3000 2>&1 | tail -5
echo "== 校准后 =="
cat "$F" 2>/dev/null; echo
echo "四个值应互相接近，且落在上面的 re_min~re_max 区间内。"
