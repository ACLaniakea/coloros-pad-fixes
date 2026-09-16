# OPlus camera boost-pool compatibility module

This is a runtime port of the OPlus `dynamic_boost_pool` for the Lenovo
`qcom,system` DMA heap.  It cannot add calls to the vendor module, so it uses
two tightly scoped kprobes instead:

* allocation from `system_qcom_sg_buffer_alloc()`;
* release from `system_heap_buf_free()`.

At load time the module refuses to continue unless it can resolve both target
functions, derive their exact arm64 `BL` return sites, and find the
`qcom,system` pool list.  Calls from other heaps and all other call sites pass
through untouched.

## Status

`oplus_boost_pool.ko` builds against the pinned TB710FU GKI ABI and passes the
symbol-CRC gate.  The manual smoke test on the OPD2513-based TB710FU port
validated the qcom-system call sites, 192 MiB prefill, automatic binding to
`ider-service_64`, allocation from the pool, release interception and refill.
It is included in the OPlus BSP boot-time module list after
`oplus_kgsl_state_bridge`.

The upstream source default is a 192 MiB pool on devices over 4 GiB RAM
(64 MiB surface-flinger reserve plus 128 MiB camera reserve).  The observed
roughly 517 MiB camera reserve on the comparison device is therefore a
runtime configuration question.  Its value must be measured from the original
device or its startup scripts before changing the default; it can then be set
through `/proc/boost_pool/camera_pages` without rebuilding.

The port deliberately starts at the 192 MiB upstream default.  PKX110 has more
RAM and retains a 564 MiB runtime pool; copying that waterline to this tablet
without a face-unlock A/B would turn a reclaim fix into additional permanent
memory pressure.

The idle `camera_pid` is the original sentinel value `1`.  Until a camera
provider is bound, non-camera clients can consume only the surface-flinger
headroom and cannot enter the camera reserve.

## Build

```sh
kernel-compat/tools/build_boost_pool.sh
```

The script downloads the exact GKI source, prepared output and `Module.symvers`
for build `13606743`, then requires exact `vermagic` and import CRC matches.

## Device verification

Use a booted TB710FU with USB debugging and a validated recovery path.  This
module allocates its reserve asynchronously and changes DMA-heap allocation.
The following remains useful for a one-off diagnostic load.

```sh
adb push kernel-compat/oplus_boost_pool/oplus_boost_pool.ko /data/local/tmp/
adb shell su -c 'insmod /data/local/tmp/oplus_boost_pool.ko'
adb shell su -c 'dmesg | tail -100; cat /proc/boost_pool/camera'
adb shell su -c 'rmmod oplus_boost_pool'
```

Success requires a `bound qcom,system` log with nonzero allocation and free
call-site counts, creation of `/proc/boost_pool/camera`, and a clean unload.
Only then measure face-unlock traces, set the camera pid policy, and decide
whether a capacity larger than the upstream default is justified.
