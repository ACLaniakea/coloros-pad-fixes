// SPDX-License-Identifier: GPL-2.0-only
/*
 * Copyright (C) 2020-2022 Oplus. All rights reserved.
 */

#ifndef __OPLUS_RWSEM_BASE_H__
#define __OPLUS_RWSEM_BASE_H__
/*
 * Note:
 * The macros or api in this file rwsem_base.c must be the same as
 * their definition in kernel/locking/rwsem.c.
 */
#define RWSEM_READER_OWNED	(1UL << 0)
#define RWSEM_NONSPINNABLE	(1UL << 1)
#define RWSEM_OWNER_FLAGS_MASK	(RWSEM_READER_OWNED | RWSEM_NONSPINNABLE)

#define RWSEM_WRITER_LOCKED	(1UL << 0)
#define RWSEM_WRITER_MASK	RWSEM_WRITER_LOCKED

#define RWSEM_FLAG_WAITERS	(1UL << 1)
#define RWSEM_FLAG_HANDOFF	(1UL << 2)

#define rwsem_first_waiter(sem) \
	list_first_entry(&sem->wait_list, struct rwsem_waiter, list)

unsigned long long notrace sched_clock(void);

static inline struct task_struct *rwsem_owner(struct rw_semaphore *sem)
{
	return (struct task_struct *)
		(atomic_long_read(&sem->owner) & ~RWSEM_OWNER_FLAGS_MASK);
}

static inline bool rwsem_test_oflags(struct rw_semaphore *sem, long flags)
{
	return atomic_long_read(&sem->owner) & flags;
}

static inline bool is_rwsem_reader_owned(struct rw_semaphore *sem)
{
#if IS_ENABLED(CONFIG_DEBUG_RWSEMS)
	/*
	 * Check the count to see if it is write-locked.
	 */
	long count = atomic_long_read(&sem->count);

	if (count & RWSEM_WRITER_MASK)
		return false;
#endif
	return rwsem_test_oflags(sem, RWSEM_READER_OWNED);
}

#define RWSEM_READER_SHIFT	8
#define RWSEM_READER_BIAS	(1UL << RWSEM_READER_SHIFT)

/*
 * The rwsem_spin_on_owner() function returns the following 4 values
 * depending on the lock owner state.
 *   OWNER_NULL  : owner is currently NULL
 *   OWNER_WRITER: when owner changes and is a writer
 *   OWNER_READER: when owner changes and the new owner may be a reader.
 *   OWNER_NONSPINNABLE:
 *		   when optimistic spinning has to stop because either the
 *		   owner stops running, is unknown, or its timeslice has
 *		   been used up.
 */
enum owner_state {
	OWNER_NULL		= 1 << 0,
	OWNER_WRITER		= 1 << 1,
	OWNER_READER		= 1 << 2,
	OWNER_NONSPINNABLE	= 1 << 3,
};
#define OWNER_SPINNABLE		(OWNER_NULL | OWNER_WRITER | OWNER_READER)

static inline struct task_struct *
rwsem_owner_flags(struct rw_semaphore *sem, unsigned long *pflags)
{
	unsigned long owner = atomic_long_read(&sem->owner);

	*pflags = owner & RWSEM_OWNER_FLAGS_MASK;
	return (struct task_struct *)(owner & ~RWSEM_OWNER_FLAGS_MASK);
}

static inline enum owner_state
rwsem_owner_state(struct task_struct *owner, unsigned long flags)
{
	if (flags & RWSEM_NONSPINNABLE)
		return OWNER_NONSPINNABLE;

	if (flags & RWSEM_READER_OWNED)
		return OWNER_READER;

	return owner ? OWNER_WRITER : OWNER_NULL;
}

static inline void __rwsem_set_reader_owned(struct rw_semaphore *sem,
					    struct task_struct *owner)
{
	unsigned long val = (unsigned long)owner | RWSEM_READER_OWNED |
		(atomic_long_read(&sem->owner) & RWSEM_NONSPINNABLE);

	atomic_long_set(&sem->owner, val);
}

static inline void rwsem_set_reader_owned(struct rw_semaphore *sem)
{
	__rwsem_set_reader_owned(sem, current);
}
#endif /* __OPLUS_RWSEM_BASE_H__ */
