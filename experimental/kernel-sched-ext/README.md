# SM8650Q sched_ext policy probe

This directory contains the first runnable-policy validation artifact for the
SM8650Q `1+4+1` tablet kernel port. It is intentionally **not enabled,
installed, or flashed**.

`scx_sm8650q_probe.bpf.c` only exercises the API available in the imported
2023 sched_ext core: enqueue every task on the global dispatch queue using a
bounded 20 ms slice. It does not make CPU-placement, capacity, WALT, or
frequency decisions. Its purpose is to validate BPF `struct_ops` registration,
the watchdog, and automatic core fallback before implementing a device policy.

The public FengChi package is not a complete HMBIRD scheduler: it contains a
newer sched_ext core plus writable `/proc/hmbird_sched` compatibility nodes,
but no policy logic connected to those nodes. Those compatibility nodes must
not be exposed until their controls are backed by a real policy.

The next policy phase must retain WALT and Lenovo HyperSched as the default
path, be opt-in, and add only topology-aware placement after the probe has
booted and loaded cleanly.
