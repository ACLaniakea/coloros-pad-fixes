# oplus_mm_proactive_compact

移植包漏掉、我们自建补回来的一加 `mm/proactive_compact`。**随 BSP 包出货。**

## 它做什么

只读地计算内存碎片度，导出 `/proc/oplus_mem/fragmentation_index`，格式：

```
<是否碎片> <compaction_hpage_order> <compaction_proactiveness>
```

两个阈值可通过写同一节点或模块参数调整（默认 `4` / `20`，与一加一致）。
模块自己**不发起规整**。

## 为什么缺了它整条链就断

ColorOS 的规整链是：

1. 内核模块导出 `fragmentation_index`；
2. 框架读到 `is_fragmented=1`，置属性 `sys.oplus.vm.oplus_compact_memory`；
3. init 的 `on property:sys.oplus.vm.oplus_compact_memory=*` 起 `oplus_compact_memory`；
4. `/system_ext/bin/autochmod.sh` 里的同名函数执行 `echo 1 > /proc/sys/vm/compact_memory`。

第 2~4 步平板上本来就齐（init 服务定义、autochmod.sh 函数都在），缺的只有第 1 步，
所以补上之前这条链整条不通，内核自带的 `vm.compaction_proactiveness` 也是 0。

## 风险

不使用任何 vendor hook，不含 per-CPU 变量（不占 `PERCPU_MODULE_RESERVE`），
只吃 GKI 已导出的 `proc_create` / `proc_remove` / `contig_page_data`。
过 `verify_module_abi.py`：vermagic 精确、18 个符号 CRC 精确。

`proc_mkdir("oplus_mem")` 在目录已存在时返回 NULL，源码本身有回退分支，
与先加载的 `oplus_bsp_zram_opt` 共存无冲突；清单里排在它之后。

构建：`kernel-compat/tools/build_missing_mm_modules.sh`。
