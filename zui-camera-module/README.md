# Lenovo ZUI Camera for ColorOS

This standalone KernelSU module installs the signed TB710FU ZUI Camera suite as
a systemless priv-app and keeps all port-specific compatibility isolated here.

- `com.zui.camera`, Assistant and QR Scanner: original Lenovo APKs and allowlists.
- `system/etc/camera`: original LCAF configuration overlay required by the tablet camera algorithms.
- `zygisk/arm64-v8a.so`: process-only COW identity for ZUI Camera; global device properties remain unchanged.
- `hook/ZUI-Camera-Compat.apk`: standalone LSPosed hook scoped only to `com.zui.camera`.

It does not replace camera provider, CamX libraries, SELinux policy, or global gallery defaults.
