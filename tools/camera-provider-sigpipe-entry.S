// Provider entry trampoline.  ELF entry retains its required BTI landing pad,
// then branches here to ignore SIGPIPE before any CamX code runs and continue
// at the original entry prologue's 0x4014 continuation.
// The code is placed in the executable LOAD-segment gap at 0x7528.
.text
.org 0x7528
.global camera_provider_entry
camera_provider_entry:
    // The dynamic linker reaches an ELF entry point with an indirect jump.
    // Match the stock entry landing pad rather than a function-call landing
    // pad so BTI remains valid on this PAC/BTI-enabled binary.
    bti j
    sub sp, sp, #32
    mov w0, #13
    mov x1, sp
    mov x2, xzr
    // AArch64 rt_sigaction requires sizeof(kernel_sigset_t), not a count of
    // signals. Passing 1 makes the kernel reject the call with EINVAL.
    mov x3, #8
    str x3, [sp]
    str xzr, [sp, #8]
    str xzr, [sp, #16]
    str xzr, [sp, #24]
    mov w8, #134
    svc #0
    add sp, sp, #32
    mov x29, #0
    mov x30, #0
    mov x0, sp
    // Verified signed imm26 from 0x7568 to the original entry continuation.
    .inst 0x17fff2ab
