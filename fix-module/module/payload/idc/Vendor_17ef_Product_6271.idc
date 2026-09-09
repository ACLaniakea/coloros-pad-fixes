# Lenovo Keyboard Pack is a detachable keyboard/trackpad even though Lenovo's
# platform driver exposes it on BUS_HOST (0x19).  Mark it external so Android
# and ColorOS enable the native trackpad preference controllers.
device.internal = 0
