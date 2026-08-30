# SM8650Q 专用调度（Scene）

这是为联想 TB710FU 移植 ColorOS 的 `SM8650Q/pineapple` 六核平台编写的
独立调度模块。真实容量拓扑为 `1+4+1`：CPU0 小核、CPU1-4 四颗同容量
中核、CPU5 Prime 核。内核可能把中核拆为 `policy1/3`，也可能合并为
`policy1`；两者都属于同一 1+4+1 拓扑，模块会自动适配，不能误判成四个性能簇。

模块使用 Scene/UPerf 兼容的标准入口：

```text
/data/powercfg.sh init
/data/powercfg.sh powersave
/data/powercfg.sh balance
/data/powercfg.sh performance
/data/powercfg.sh fast
```

Scene 负责识别前台应用并调用模式，模块自身没有常驻进程。调度仅控制 WALT
模式上限、升降频响应、短时输入 boost、迁移阈值和有效的六核 cpuset；不会停用
原厂 Power/Perf HAL，不关闭温控，不固定 CPU 最低频率，也不修改内存/ZRAM。

源系统 SM8850 的启动参数会用 `irqaffinity=0-1` 将普通设备中断压到六核机唯一
弱核 CPU0。本模块在 post-fs-data 和启动完成后各执行一次拓扑修正：高频音频、
显示、存储、触控及带宽监控 IRQ 分散到 CPU1-4，CPU5 继续保留给前台交互突发。
修正脚本执行后立即退出，不依赖常驻 irqbalance 服务。

WALT 虽将 CPU1-2 与 CPU3-4 暴露成两个频域簇，但两组容量都为 867。模块保留
中核之间的稳健迁移门槛，仅按模式降低 CPU3-4 到 CPU5/X4 的上迁阈值；均衡模式
为 82%，性能/极速为 72%/65%。持续满载仍会快速使用 X4，普通后台负载不会因此
长期占用 Prime 核。

`standby` 保持均衡基线，不套用省电模式：熄屏后的实际省电仍由内核 suspend 和
原厂 Power HAL 负责，同时避免亮屏到 Scene 前台回调之间继承低频限制而卡顿。

安全门禁校验 SoC、平台、CPU 0-5 与必需的 policy0/1/5；policy3 是可选中核
频域，缺失时自动走合并中核模式，其它设备仍会拒绝执行。
