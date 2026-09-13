# 内核配置留档

| 文件 | 说明 |
|---|---|
| `stock-6.1.128-ab13606743.config` | **原厂内核**的配置,从 `kernel-backup/stock-originals/stock-boot.img` 用 `scripts/extract-ikconfig` 抠出。**重编内核的唯一正确基线** |
| `droidspaces-r2.config` | r2 实际使用的配置 = 原厂 + `MODULE_SIG_PROTECT=n` + LOCALVERSION + DroidSpaces 那批(见下) |
| `abi_symbollist.raw` | `TRIM_UNUSED_KSYMS` 的白名单,已含我们新增的 12 条符号 |

## 不要用当前内核的 /proc/config.gz 当基线

r1+ 的配置里 `TRIM_UNUSED_KSYMS` 是关的,且 `SYSVIPC=y` 的方式会让 `module_layout` 从
`0xea759d7f` 变成 `0x28c5e94f`，289 个厂商模块全部拒载、开机第一屏重启。踩过一次。

## r2 相对原厂改了什么

```
MODULE_SIG_PROTECT=n                          否则原厂模块 sig_ok=false 被拒
LOCALVERSION="-android14-11-sm8650q-droidspaces-r2"
SYSVIPC / SYSVIPC_SYSCTL / SYSVIPC_COMPAT     需配合 sysvipc-kabi-slots 补丁,否则破坏 ABI
POSIX_MQUEUE / POSIX_MQUEUE_SYSCTL            CRC 中性
USER_NS / PID_NS / IPC_NS                     CRC 中性
DEVTMPFS / TMPFS_XATTR / TMPFS_POSIX_ACL      CRC 中性
```

配套补丁见 `../patches/gki-6.1.128-{oplus-gloom-and-percpu,sysvipc-kabi-slots}.patch`。
