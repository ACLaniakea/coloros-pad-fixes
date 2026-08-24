# SM8650Q 专用调度（Scene）

这是为联想 TB710FU 移植 ColorOS 的 `SM8650Q/pineapple` 六核平台编写的
独立调度模块。真实容量拓扑为 `1+4+1`：CPU0 小核、CPU1-4 四颗同容量
中核、CPU5 Prime 核；中间四核仅在 cpufreq 层拆为 policy1/3 两个频域，
所以接口形态是 `policy0/1/3/5`，不能误判成四个性能簇。

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

`standby` 保持均衡基线，不套用省电模式：熄屏后的实际省电仍由内核 suspend 和
原厂 Power HAL 负责，同时避免亮屏到 Scene 前台回调之间继承低频限制而卡顿。

安全门禁同时校验 SoC、平台、CPU 0-5 与 policy0/1/3/5；其它设备拒绝执行。
