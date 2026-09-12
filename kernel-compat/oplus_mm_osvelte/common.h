/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2018-2021 Oplus. All rights reserved.
 */
#ifndef _OSVELTE_COMMON_H
#define _OSVELTE_COMMON_H

#define KMODULE_NAME "oplus_bsp_mm_osvelte"

/* 移植适配（2026-09-12）：DEV_NAME 固定为 "osvelte"，不再随 _DBG 改名。
 *
 * 上游这里是：开了 CONFIG_OPLUS_FEATURE_MM_OSVELTE_DBG 就叫 "osvelte_dbg"。
 * 我们的构建脚本对整棵树取 CONFIG 并集（各模块开的宏不一致会让共享结构体布局
 * 对不上，osvelte 又被 hybridswap_zram 与 uxmem_opt 依赖，所以必须统一），
 * _DBG 于是被打开，procfs 根目录成了 /proc/osvelte_dbg、字符设备成了
 * /dev/osvelte_dbg。两处名字都对不上原厂，后果是：
 *   - lmkd 打不开 /dev/osvelte，也读不到 /proc/osvelte/lowmem_dbg（它两处都引用）；
 *   - libresourcemanagerservice.so 读 /proc/osvelte/dma_buf/procinfo 一无所获；
 *   - /dev/osvelte_dbg 落不到 vendor_file_contexts 里 /dev/osvelte 那条规则上，
 *     标签退化成默认的 device:s0（策略里 osvelte_device 这个类型本来就是齐的）。
 *
 * 单纯关掉 _DBG 不行：dma_buf / ashmem 两个子目录也在同一个宏里，关了就一起没了，
 * 而对照机两样都有。所以只固定名字，_DBG 的功能保留。
 */
#define DEV_NAME "osvelte"

#define DEV_PATH "/dev/" DEV_NAME

#define OSVELTE_LOG_TAG DEV_NAME

/* declare page-flags here */
#define PG_ezreclaimable (PG_oem_reserved)

enum oplus_mm_scene_bit {
	MM_SCENE_CAMERA = 0,
	MM_SCENE_ANIMATION,
	NR_MM_SCENE_BIT,
};

enum oplus_mm_symbol {
	OPLUS_MM_KOBJ,
	OPLUS_TASK_EZRECLAIMD,
	OMS_END,
};

/* common ioctl for userspace */
#define __COMMONIO 0xFA
#define CMD_OSVELTE_GET_VERSION		_IO(__COMMONIO, 1)
#define CMD_OSVELTE_SET_SCENE		_IO(__COMMONIO, 2)
#define CMD_OSVELTE_CLEAR_SCENE		_IO(__COMMONIO, 3)

struct osvelte_common_header {
	u32 api_version;
	u64 private_data;
	u32 buffer_len;
	/* payload */
	char data[];
};

/* kgsl.c use osvelte_info */
#define osvelte_info(fmt, ...)      \
	pr_info(OSVELTE_LOG_TAG ": " fmt, ##__VA_ARGS__)

#define osvelte_err(fmt, ...)      \
	pr_err(OSVELTE_LOG_TAG ": " fmt, ##__VA_ARGS__)

long osvelte_common_ioctl(struct file *file, unsigned int cmd, unsigned long arg);
int osvelte_common_init(struct kobject *root);
int osvelte_common_exit(void);

extern struct kobject *oplus_mm_kobj;
extern void osvelte_register_symbol(enum oplus_mm_symbol sym, void *data);
extern void *osvelte_read_symbol(enum oplus_mm_symbol sym, bool atomic);
extern bool osvelte_test_scene(unsigned long nr);
#endif /* _OSVELTE_COMMON_H */
