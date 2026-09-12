/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2018-2021 Oplus. All rights reserved.
 */
#ifndef _OSVELTE_INTERNAL_H
#define _OSVELTE_INTERNAL_H

#include <asm/ioctls.h>
#include "common.h"

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

/* experimental feature */
#define OSVELTE_FEATURE_USE_HASHLIST 1

#define OSVELTE_MAJOR		(0)
#define OSVELTE_MINOR		(2)
#define OSVELTE_PATCH_NUM	(3)
#define OSVELTE_VERSION (OSVELTE_MAJOR << 16 | OSVELTE_MINOR)

#define CMD_COMMON_MIN		CMD_OSVELTE_SET_SCENE
#define CMD_COMMON_MAX		CMD_OSVELTE_CLEAR_SCENE
#define CMD_COMMON_INVLAID	0xFFFFFFFE

#define OSVELTE_STATIC_ASSERT(c)				\
{								\
	enum { OSVELTE_static_assert = 1 / (int)(!!(c)) };	\
}

#define osvelte_info(fmt, ...)      \
	pr_info(OSVELTE_LOG_TAG ": " fmt, ##__VA_ARGS__)

#define osvelte_err(fmt, ...)      \
	pr_err(OSVELTE_LOG_TAG ": " fmt, ##__VA_ARGS__)

#define MM_LOG_LVL 1
enum {
	MM_LOG_VERBOSE = 0,
	MM_LOG_INFO,
	MM_LOG_DEBUG,
	MM_LOG_ERR,
};

static inline char mm_loglvl_to_char(int l)
{
	switch (l) {
	case MM_LOG_VERBOSE:
		return 'V';
	case MM_LOG_INFO:
		return 'I';
	case MM_LOG_DEBUG:
		return 'D';
	case MM_LOG_ERR:
		return 'E';
	}
	return '?';
}

#define osvelte_log(l, f, ...) do {					\
	if (l >= MM_LOG_LVL) 						\
		printk(KERN_ERR "%s %5d %5d %c %-16s: %s:%d "f,		\
		       OSVELTE_LOG_TAG, current->tgid, current->pid,	\
		       mm_loglvl_to_char(l), current->comm, __func__,	\
		       __LINE__,  ##__VA_ARGS__);			\
} while (0)

#define osvelte_loge(f, ...)						\
	osvelte_log(MM_LOG_ERR, f, ##__VA_ARGS__)

#define osvelte_logi(f, ...)						\
	osvelte_log(MM_LOG_INFO, f, ##__VA_ARGS__)

#define osvelte_logd(f, ...)						\
	osvelte_log(MM_LOG_DEBUG, f, ##__VA_ARGS__)
#endif /* _OSVELTE_INTERNAL_H */
