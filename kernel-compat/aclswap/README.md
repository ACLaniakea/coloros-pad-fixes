# aclswap — zram with writeback for the Lenovo TB710FU kernel

## Why this exists

The ported ColorOS userspace assumes OPlus HybridSwap: cold compressed pages are
written back to flash so the compressed pool never grows without bound in RAM.
The whole ColorOS memory policy is calibrated around that pressure valve — including
LMKD, which counts file cache as available and declines to kill while the pool is
quietly eating RAM.

Lenovo's kernel provides none of it. `/proc/nandswap`, `/proc/nandswap_vnd` and
`/sys/block/zram0/hybridswap_*` are all absent, and the GKI build this device runs
has `# CONFIG_ZRAM_WRITEBACK is not set`. Meanwhile the ROM's own fstab still asks
for a backing device:

    /dev/block/zram0 none swap defaults zramsize=80%,zram_backingdev_size=prop

So the pool only ever grows. Measured on this tablet: `mem_used_max` reached
1.70 GB of an 8 GB machine, and a burst of 24 app launches drove three launches
past a 40-second timeout while MemFree stayed pinned at 85–260 MB.

Porting OPlus HybridSwap itself is not an option: it replaces `zram_drv`/`zcomp`
and hooks `mm/`, which changes exported symbol CRCs and breaks the KMI every
Lenovo vendor module is built against. This module takes the narrow path instead —
the stock GKI zram driver, rebuilt out-of-tree with writeback enabled, under a
different name so it coexists with the GKI `zram.ko` rather than replacing it
(replacing a GKI module is also blocked by `CONFIG_MODULE_SIG_PROTECT`).

## What the patch changes

`aclswap.patch` is a 30-line delta against `drivers/block/zram` of the exact GKI
source this device runs. It does three things:

1. **Renames the device** — block device `aclswap%d`, blkdev name `aclswap`,
   control class `aclswap-control`, debugfs dir `aclswap`. Nothing collides with
   the loaded stock `zram`.
2. **Allocates a dynamic CPU hotplug state.** The stock driver registers the
   statically numbered `CPUHP_ZCOMP_PREPARE`. That number is already owned by the
   GKI zram module, which stays loaded, so a second registration returns `-EBUSY`
   from `module_init` before anything reaches the log — an insmod that fails with
   "Device or resource busy" and an empty dmesg. `CPUHP_BP_PREPARE_DYN` plus a
   shared `aclswap_cpuhp_state` fixes it.
3. Nothing else. Compression, the block layer and the sysfs ABI are stock.

`CONFIG_ZRAM_WRITEBACK` and `CONFIG_ZRAM_MEMORY_TRACKING` are forced on through
`ccflags-y`, not by patching Kconfig. Memory tracking is what makes `idle` accept
an age in seconds instead of only `all`, so writeback can target genuinely cold
pages rather than everything.

## Backing device: the SELinux trap

The backing store is a preallocated file on `/data/vendor`, attached with
`losetup`. The directory is not arbitrary. The loop worker writes the backing file
from a kernel thread, and that write is subject to SELinux: with the file in
`/data/local/tmp` (`shell_data_file`) or `/data/adb` (`adb_data_file`) every write
fails, and it surfaces as

    loop: Write error at byte offset 4096, length 4096.
    I/O error, dev loop52, sector 8 op 0x1:(WRITE)

not as a permission error, which makes it look like broken hardware. In
`/data/vendor` (`vendor_data_file`) the same test writes and reads back cleanly.
Verified: 256 MB written, `bd_stat` 65536 pages out, `mem_used` 256 MB -> 0 MB,
and the read-back sha256 matched the source.

## Building

    kernel-compat/tools/build_aclswap.sh

Same toolchain contract as `build_gki_compat.sh`: exact GKI source, exact
`modules_prepare` output, exact `Module.symvers`. `CONFIG_MODVERSIONS` is on, so a
module built against a merely similar 6.1 tree will not load.

This device: GKI build `13606743`, common commit
`5c2cea985a841939e6d074cbed2019dec0245fcd`, running
`6.1.128-android14-11-g5c2cea985a84-ab13606743`.

## License

Derived from the Linux kernel's zram driver; GPL-2.0, as upstream.
