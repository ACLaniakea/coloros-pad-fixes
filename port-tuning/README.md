# 已归档：早期独立调优模块

**不要安装这个目录。** 它是 `coloros_port_tuning` 1.1.5 的快照，2.0.0 起已并入
`fix-module/`（模块 id `coloros_port_fix`）。

里面的取值也早已过时 —— 比如 `module.prop` 描述里写的"全局 swappiness=20"，
现在这一层已经整体交还原厂（见 `修复汇总.md` 与 `fix-module/module/service.sh`
末尾的说明）。留着只为读源码时对照。

构建脚本 `tools/build_all.sh` 不会打包这个目录。
