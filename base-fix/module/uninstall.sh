#!/system/bin/sh

MODDIR=${0%/*}

if [ -f "$MODDIR/cpu-limit-guard.pid" ]; then
    kill "$(cat "$MODDIR/cpu-limit-guard.pid")" 2>/dev/null
fi
[ -f "$MODDIR/app-suggestion.pid" ] && kill "$(cat "$MODDIR/app-suggestion.pid")" 2>/dev/null
[ -f "$MODDIR/voice-power-guard.pid" ] && kill "$(cat "$MODDIR/voice-power-guard.pid")" 2>/dev/null
resetprop persist.sys.horae.enable 0
resetprop -p persist.sys.tango_zygote32.start 1
