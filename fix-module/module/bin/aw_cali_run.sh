#!/system/bin/sh
# 智能功放（AWINIC aw882xx ×4）Re 校准
#
# 背景：移植后 /mnt/vendor/persist/factory/audio/aw_cali.bin 可能是空的（全空格），
#       f0 正常而唯独缺 Re。缺了 Re，功放的振幅保护没有每台实测的直流阻抗基准，
#       大音量下容易破音。
#
# 重要：Re 是逐台实测数据，不能从别的机器复制，必须在本机跑。
#
# 原理：校准信号由 DSP 自己注入，但扬声器通路必须真正处于工作状态。数字静音或
#       音量 0 都不行 —— OPlus 的 HAL 会把功放置于关闭态（PaClosed），DSP 算法
#       不运行，get_spkr_st 会返回 "get st failed"。因此脚本会生成一段低幅方波
#       并以较低音量播放，跑完自动恢复原音量。
#
# 会有轻微声音，属正常。请在安静环境下运行，且不要遮挡扬声器。
#
# 用法：su -c /data/adb/modules/<模块名>/bin/aw_cali_run.sh
#       跑完重启一次让 HAL 重新读取。

CALI=/vendor/bin/aw882xx_cali
F=/mnt/vendor/persist/factory/audio/aw_cali.bin
# 必须放在共享存储里：播放器读不到 /data/local/tmp。
WAV=/sdcard/Music/aw_cali_tone.wav
TMP=/data/local/tmp/aw_cali_tmp
CAL_VOL=24                 # 0..160，够把功放顶起来又不吵
TAG="[aw_cali]"

die() { echo "$TAG $1"; exit 1; }
cleanup() { rm -rf "$TMP" "$WAV"; }

[ -x "$CALI" ] || die "找不到 $CALI，本机不支持该校准工具"
[ -e "$F" ] || die "找不到 $F，persist 分区可能未挂载"

echo "$TAG 校准前：$(tr -s ' ' ' ' < "$F")"
echo "$TAG 合法区间：$("$CALI" all get_re_range 2>&1 | tail -1)"

# ---- 生成低幅方波 WAV ---------------------------------------------------
# 48kHz / 立体声 / 16bit。用「反复自我拼接」的方式按 2 的幂增长，避免
# 在 shell 里逐样本循环（几万次 printf 会非常慢）。
# 半周期 512 样本 ≈ 10.7ms，整周期约 94Hz；幅度 ±2048，约 -24 dBFS。
grow() {  # $1=文件 $2=翻倍次数
    _i=0
    while [ "$_i" -lt "$2" ]; do cat "$1" "$1" > "$1.x" && mv "$1.x" "$1"; _i=$((_i+1)); done
}
rm -rf "$TMP"; mkdir -p "$TMP" || die "无法创建 $TMP"
printf '\000\010\000\010' > "$TMP/hi"      # 左右声道各 +2048
printf '\000\370\000\370' > "$TMP/lo"      # 左右声道各 -2048
grow "$TMP/hi" 9                            # 4B -> 2048B = 512 样本
grow "$TMP/lo" 9
cat "$TMP/hi" "$TMP/lo" > "$TMP/period"     # 一个完整周期 4096B
grow "$TMP/period" 10                       # 4096B -> 4MB ≈ 21.8 秒
DATA=$(( $(wc -c < "$TMP/period") ))
[ "$DATA" -gt 100000 ] || { cleanup; die "生成波形失败"; }

# WAV 头（44 字节）。长度字段按实际 DATA 计算，用 printf 逐字节写小端。
le32() { _n=$1; _i=0; while [ $_i -lt 4 ]; do printf "\\$(printf '%03o' $((_n & 255)))"; _n=$((_n >> 8)); _i=$((_i+1)); done; }
le16() { _n=$1; _i=0; while [ $_i -lt 2 ]; do printf "\\$(printf '%03o' $((_n & 255)))"; _n=$((_n >> 8)); _i=$((_i+1)); done; }
mkdir -p /sdcard/Music 2>/dev/null
{
    printf 'RIFF'; le32 $((DATA + 36)); printf 'WAVEfmt '
    le32 16; le16 1; le16 2; le32 48000; le32 192000; le16 4; le16 16
    printf 'data'; le32 "$DATA"
} > "$WAV"
[ "$(wc -c < "$WAV")" = 44 ] || { cleanup; die "WAV 头长度异常"; }
cat "$TMP/period" >> "$WAV"
chmod 644 "$WAV"
chown media_rw:media_rw "$WAV" 2>/dev/null

# ---- 调低音量并起播 -----------------------------------------------------
VOL=$(cmd media_session volume --stream 3 --get 2>&1 | sed -n 's/.*volume is \([0-9]*\).*/\1/p' | head -1)
[ -n "$VOL" ] || VOL=40
cmd media_session volume --stream 3 --set "$CAL_VOL" >/dev/null 2>&1
echo "$TAG 原音量 $VOL，校准期间临时设为 $CAL_VOL（会有轻微声音）"

# 通路是否就绪，唯一可信的判据就是 get_spkr_st 本身能不能读回 R0。
# 不能用 resolve-activity 挑播放器：实测 audio/x-wav 会解析到 QQ 的 JumpActivity，
# 那只是个跳转壳，根本不会起音频流。
spkr_ready() { "$CALI" all get_spkr_st 2>&1 | grep -q 'R0'; }

PLAYER=
for cand in \
    com.heytap.music/com.allsaints.music.MainActivity \
    com.oplus.music/com.oplus.music.MainActivity \
    com.android.music/com.android.music.MediaPlaybackActivity
do
    pm path "${cand%%/*}" >/dev/null 2>&1 || continue
    echo "$TAG 尝试 $cand"
    am start -a android.intent.action.VIEW -d "file://$WAV" -t audio/x-wav -n "$cand" >/dev/null 2>&1
    _t=0
    while [ $_t -lt 12 ]; do
        sleep 1
        if spkr_ready; then PLAYER=$cand; break; fi
        _t=$((_t+1))
    done
    [ -n "$PLAYER" ] && break
    am force-stop "${cand%%/*}" >/dev/null 2>&1
done

if [ -z "$PLAYER" ]; then
    cmd media_session volume --stream 3 --set "$VOL" >/dev/null 2>&1
    cleanup
    die "扬声器通路没能激活。请手动播放一段音乐（音量别太小），然后重跑本脚本。"
fi

echo "$TAG 通路已激活：$("$CALI" all get_spkr_st 2>&1 | grep R0)"
echo "$TAG 开始校准（3000ms）"
"$CALI" all cali_all 3000 2>&1 | grep -vE "mixer_plug|mixer_plugin"

am force-stop "${PLAYER%%/*}" >/dev/null 2>&1
cmd media_session volume --stream 3 --set "$VOL" >/dev/null 2>&1
cleanup

echo "$TAG 校准后：$(tr -s ' ' ' ' < "$F")"
echo "$TAG 音量已恢复到 $VOL。四个值应互相接近并落在合法区间内；重启一次生效。"
