# KGSL state bridge

This module restores the missing event edge between Android process priority
and Lenovo's otherwise working Qualcomm KGSL reclaim implementation. It listens
to the standard `oom_score_adj_update` tracepoint and writes the existing KGSL
per-process `state` sysfs node from a kernel workqueue.

It deliberately does not call unexported `msm_kgsl` functions, scan process
tables, poll from userspace, or change the driver's reclaim limits. The default
threshold is the previously validated conservative boundary, `adj >= 800`;
HOME and PREVIOUS applications remain foreground.

The r3 kernel keeps GKI symbol trimming enabled and adds only `filp_open` and
`kernel_write` to the existing ABI allowlist (`filp_close` was already present).
All filesystem I/O runs from a workqueue, never from the oom-adjust tracepoint.
