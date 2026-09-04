/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Minimal sched_ext registration probe for the SM8650Q 1+4+1 topology.
 *
 * This is deliberately a safety policy, not the claimed OnePlus HMBIRD
 * algorithm.  It uses the 2023 sched_ext core API only: every SCX task is
 * directly put on the core global DSQ with a bounded 20ms slice.  It has no
 * CPU placement or frequency policy of its own, so it is suitable only for
 * validating struct_ops registration and the core watchdog fallback.
 */
#include "vmlinux.h"
#include <bpf/bpf_helpers.h>

char _license[] SEC("license") = "GPL";

#define SCX_DSQ_GLOBAL (1ULL << 63 | 1)
#define SCX_SLICE_DFL  (20ULL * 1000 * 1000)

void scx_bpf_dispatch(struct task_struct *p, u64 dsq_id, u64 slice,
		      u64 enq_flags) __ksym;

SEC("struct_ops/scx_sm8650q_probe_enqueue")
void scx_sm8650q_probe_enqueue(struct task_struct *p, u64 enq_flags)
{
	/* The SCX core owns validation and safely disables on a bad dispatch. */
	scx_bpf_dispatch(p, SCX_DSQ_GLOBAL, SCX_SLICE_DFL, enq_flags);
}

SEC(".struct_ops.link")
struct sched_ext_ops scx_sm8650q_probe_ops = {
	.enqueue = (void *)scx_sm8650q_probe_enqueue,
	.name = "sm8650q_probe",
};
