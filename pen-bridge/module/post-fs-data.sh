#!/system/bin/sh

# Keep the TB710FU global refresh policy in this independently updateable
# Root package. It is a display-policy bind only at this early stage; the
# service writes the vendor pen-wakeup proc nodes once after boot/CPS is ready.
MODDIR=${0%/*}
REFRESH_TARGET=/my_product/etc/refresh_rate_config.xml
REFRESH_PAYLOAD="$MODDIR/payload/refresh_rate_config.tb710fu.xml"
if [ -f "$REFRESH_TARGET" ] && [ -f "$REFRESH_PAYLOAD" ]; then
    chown 0:0 "$REFRESH_PAYLOAD"
    chmod 0644 "$REFRESH_PAYLOAD"
    chcon u:object_r:system_file:s0 "$REFRESH_PAYLOAD" 2>/dev/null
    mount --bind "$REFRESH_PAYLOAD" "$REFRESH_TARGET" 2>/dev/null
fi
exit 0
