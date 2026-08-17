#!/system/bin/sh
#
# Keeps the capture path gain raised on this port.  The audio HAL reapplies
# the ACDB defaults (ADC 12, TX_DEC 84) whenever a recording starts, so a
# one-shot boot setting is not enough.  Poll and re-apply.
#
MODDIR=${1:-${0%/*}/..}

set_gains() {
    tinymix 48 102 >/dev/null 2>&1        # TX_DEC0 Volume
    tinymix 50 102 >/dev/null 2>&1        # TX_DEC2 Volume
    tinymix "ADC1 Volume" 18 >/dev/null 2>&1
    tinymix "ADC2 Volume" 18 >/dev/null 2>&1
}

set_gains
echo "$(date '+%F %T') recording gain applied (ADC 18, TX_DEC 102)" >>"$MODDIR/mic-gain.log"

while true; do
    sleep 0.5
    dec0=$(tinymix 48 2>/dev/null | sed -n 's/.*: \([0-9][0-9]*\).*/\1/p')
    adc1=$(tinymix "ADC1 Volume" 2>/dev/null | sed -n 's/.*: \([0-9][0-9]*\).*/\1/p')
    case "$dec0:$adc1" in
        102:18) ;;
        *) set_gains ;;
    esac
done
