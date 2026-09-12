> [!WARNING]
> **本模块已废弃（2026-09-13），请勿安装。**
>
> 它的核心做法是把全局 `swappiness` 压到 20、并禁用 osense 的主动换出与内存清理。
> 这与 4.0.4 起确立的原则——**内存策略一律交还原厂分档**——直接冲突。4.0.4 的排查
> 结论是：我们自己压低 swappiness 恰恰让这台内存最紧的机器更不敢用 swap，是卡顿的
> 来源之一而非解法（见
> [4.0.4 内存与 AOT 排查记录](../docs/investigations/4.0.4-内存与AOT排查.md)）。
>
> 版本停在 3.2.1，不随任何 Release 发布。所需能力已并入 `FixModule` 与
> `OplusBSP-Modules`。源码保留仅供查阅历史决策。

# 已归档：早期独立调优模块

**不要安装这个目录。** 它是 `coloros_port_tuning` 1.1.5 的快照，2.0.0 起已并入
`fix-module/`（模块 id `coloros_port_fix`）。

里面的取值也早已过时 —— 比如 `module.prop` 描述里写的"全局 swappiness=20"，
现在这一层已经整体交还原厂（见 `修复汇总.md` 与 `fix-module/module/service.sh`
末尾的说明）。留着只为读源码时对照。

构建脚本 `tools/build_all.sh` 不会打包这个目录。
