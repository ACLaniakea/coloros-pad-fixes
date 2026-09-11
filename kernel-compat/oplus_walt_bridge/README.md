# OPlus UX -> WALT MVP 软件桥（实验性、默认关闭）

实机验证后，原来的“补 OPlus pick-next restricted hook”方案已废弃：占用该钩子
的不是联想 HyperSched，而是本机必须保留的高通 `sched-walt.ko`。它的
`walt_cfs_replace_next_task_fair()` 本身就是最终选任务者。

WALT 同时提供了原生任务接口：

```text
/proc/sys/walt/sched_per_task_boost: <pid> <0..3>
set_task_boost(int type, u64 period_ms)
```

临时任务实测写入 `<pid> 3` 后，trace 出现：

```text
walt_cfs_mvp_pick_next: ... mvp_prio=2 ...
```

说明正确方向是把 ColorOS UX 标记翻译成 WALT 已认识的 MVP 状态，而不是替换
WALT 的最终决策。

本模块订阅可多订阅、可注销的 `android_vh_scheduler_tick`。当当前 CFS 任务被
OPlus `test_task_ux()` 判为 UX 时，为 `current` 续一个短期 WALT type-3 boost；
后续选取和抢占完全由 WALT 原生路径完成。不读取 WALT 私有偏移，不注册任何
restricted hook，也不依赖 `oplus_synchronize`。

## 冲突与局限

- 可与现有 `oplus_lb_bridge` 共存：两者使用普通 tick VH，支持多个订阅者。
- 不恢复、也不需要联想实体 HyperSched；继续使用只导出依赖符号的 stub。
- 第一次 wakeup 之前没有 tick，因此这不是完整复刻 OPlus WALT，只是渐进增强。
- WALT 没有导出 task-boost getter；桥可能覆盖同一 UX 任务上 PowerHAL 设置的
  更长 boost。基于这一点，模块加载后默认 `enable=0`，不能直接进入开机链。
- 2026-09-12 在桥未加载时抓取 8 次 launcher swipe：`sched_task_handler` 为 0 条，
  且设备的 `perfboostsconfig.xml` 没有 profile 使用 `sched_per_task_boost`。所以
  原生滑动链路目前未与本桥争抢该状态；但这不是排他保证，未来/第三方 Perf 请求
  仍可对同一 task 写入，故默认关闭策略不变。
- 不主动清除 boost；默认 100 ms 后由 WALT 自然过期，降低残留和误判风险。

## 手动测试与回滚

```sh
insmod oplus_walt_bridge.ko
cat /proc/oplus_walt_bridge

echo 1 > /sys/module/oplus_walt_bridge/parameters/enable
# 只进行有边界的交互/trace 测试
echo 0 > /sys/module/oplus_walt_bridge/parameters/enable

rmmod oplus_walt_bridge
```

`ux_hits`、`boost_ok` 应随真实 UX 负载增长；WALT 的
`walt_cfs_mvp_pick_next` 应出现相应任务。`errors` 必须保持 0。

首轮实机结果见 [TEST-2026-09-12.md](TEST-2026-09-12.md)。结论是链路有效、
可完整热回滚，但尚未完成长时功耗、待机和 PowerHAL 并发测试，不能默认启用。

## 当前发布决策：不启用开机桥

截至 2026-09-12，两组有效、执行顺序相反的 Launcher Perfetto FrameTimeline A/B 都
证明本桥能降低平均帧持续时间及部分 jank 标签；但两组的 `on_time_finish=0` 均增加，
且其中一组出现 51.519 ms 长帧。再结合 `set_task_boost()` 会覆盖而非合并既有 WALT
boost 生命周期，当前不满足“默认开机加载”的稳定性门槛。

因此发布版本保持以下策略：不创建 `enable-walt-bridge` 标记、不自动加载本模块；仅
允许开发者按上文步骤进行有限时、可卸载的手动实验。重新评估默认启用至少需要修复
尾部 deadline 回退，并完成 PowerHAL 并发与待机验收。
