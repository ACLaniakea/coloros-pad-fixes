# oplus_mm_kevent / oplus_mm_kevent_fb

一加 `multimedia/feedback` 的内核→用户态事件通道（generic netlink，家族名 `mm_fb`）。
**已编译、已过 ABI 门禁，但不随包出货。**

## 为什么留着源码

对照机上这两个 ko 是加载的，平板固件里没有，属于移植缺件。既然能干净地编出来，
配方就留着，将来如果出现需要它的模块，加回清单是一行的事。

## 为什么不出货

本机既没有生产者也没有消费者，装上只会空转：

- `mm/` 与 `cpu/` 源码树里没有任何模块调用 `mm_fb_kevent_send_to_user`；
- 已出货的 34 个 ko 用 `nm -u` 查，没有一个引用 `mm_fb_*` 符号
  （对照机上依赖 `oplus_mm_kevent_fb` 的 18 个模块全是它自家的音频 DLKM，
  平板的音频是联想/高通那套，不走这条路）。

## 顺带澄清：它不是 `bsp_kevent` 要的那个

`/system_ext/bin/bsp_kevent` 起不来，一度以为是缺这两个 ko。实测装上之后它照样
启动即退——因为它连的是 `secureguard/keventupload` 那棵树用
`netlink_kernel_create(&init_net, NETLINK_OPLUS_KEVENT, ...)` 注册的**裸 netlink
协议号**，跟本模块的 generic netlink 家族 `mm_fb` 不是一回事。
`bsp_kevent` 本身只做 DCS/OLC 遥测上报（写 `/data/oplus/bsp_kevent/kevent_record`），
对性能无影响，没有为它再补一个模块的必要。

## 构建

`kernel-compat/tools/build_missing_mm_modules.sh`。注意 `oplus_mm_kevent_fb`
依赖 `oplus_mm_kevent` 导出的两个符号，ABI 门禁要把前者的 Module.symvers 并进来
并用 `--allow-depends oplus_mm_kevent` 显式声明。
源码里 `#include <soc/oplus/system/oplus_mm_kevent_fb.h>` 走的是一加 `include/` 布局，
本目录用 `soc/oplus/system/` 下的一个转发头把路径补上，避免搬整棵 `inc/`。
