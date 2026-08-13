#!/system/bin/sh

echo "pen_wakeup_mode=$(cat /proc/pen_wakeup_mode 2>/dev/null)"
echo "pen_wakeup_switch=$(cat /proc/pen_wakeup_switch 2>/dev/null)"
echo "support_pen=$(cat /proc/support_pen 2>/dev/null)"
echo "pen2_hall=$(cat /sys/devices/virtual/factory/interface/hw_info/pen2_hall 2>/dev/null)"
echo "cps_chip=$(cat /sys/devices/platform/soc/9c0000.qcom,qupv3_i2c_geni_se/98c000.i2c/i2c-2/2-0041/get_chip_id 2>/dev/null)"
echo "cps_version=$(cat /sys/devices/platform/soc/9c0000.qcom,qupv3_i2c_geni_se/98c000.i2c/i2c-2/2-0041/get_version 2>/dev/null)"
echo "cps_uevent=$(cat /sys/devices/platform/soc/9c0000.qcom,qupv3_i2c_geni_se/98c000.i2c/i2c-2/2-0041/uevent 2>/dev/null | tr '\n' ';')"
echo "cps_gpio_pid=$(cat "${0%/*}/cps-gpio.pid" 2>/dev/null)"
echo "cps_gpio_helper=$([ -x "${0%/*}/bin/pen-cps-gpio" ] && echo 1 || echo 0)"
echo "pen_disconnect_latch=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null)"
echo "hid_control_apk=$(pm path com.aclaniakea.penhidctl 2>/dev/null | head -1)"
tail -40 "${0%/*}/pen-bridge.log" 2>/dev/null
