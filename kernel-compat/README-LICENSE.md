# 这个目录为什么是 GPL-2.0

仓库主体是 GPL-3.0（见根目录 `LICENSE`），但本目录下的内核模块源码
（`oplus_compat/`、`oplus_sched_assist/`、`aclswap/`）采用 **GPL-2.0**。

这不是偏好，是硬约束：

- 这些模块要与 Linux 内核链接，源码里的 `MODULE_LICENSE` 声明就是 `GPL v2`
  （`oplus_shell_temp_compat.c` 与 `oplus_sched_assist.c` 写的是 `"GPL v2"`，
  `oplus_mm_compat.c` 写的是 `"GPL"`，在内核语义里同样表示 GPLv2 兼容）。
- Linux 内核本身是 **GPLv2-only**，没有 "or later" 条款，与 GPLv3 不兼容。

所以本目录只能是 GPLv2。目录外的部分（模块脚本、LSPosed Hook、构建工具）
没有这个约束，走根目录的 GPL-3.0。
