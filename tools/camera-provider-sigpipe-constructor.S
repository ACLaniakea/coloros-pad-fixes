// Runs from the executable LOAD-segment gap at 0x7528 through an existing
// init-array relocation.  It installs SIG_IGN for SIGPIPE, then tail-calls the
// stock first constructor at 0x5654 so no vendor initialization is skipped.
.text
.org 0x7528
.global camera_provider_sigpipe_constructor
camera_provider_sigpipe_constructor:
    bti c
    sub sp, sp, #128
    stp x0, x1, [sp]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    str x8, [sp, #64]
    mov w0, #13
    add x1, sp, #80
    mov x2, xzr
    // AArch64 rt_sigaction requires sizeof(kernel_sigset_t), not a count of
    // signals. Passing 1 makes the kernel reject the call with EINVAL.
    mov x3, #8
    mov x4, #1
    str x4, [sp, #80]
    str xzr, [sp, #88]
    str xzr, [sp, #96]
    str xzr, [sp, #104]
    mov w8, #134
    svc #0
    ldp x0, x1, [sp]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    ldr x8, [sp, #64]
    add sp, sp, #128
    // AArch64 `b` encodes a signed PC-relative imm26. This is the verified
    // displacement from 0x7588 back to the original constructor at 0x5654.
    .inst 0x17fff833
