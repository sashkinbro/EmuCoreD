/*
    Copyright 2016-2025 melonDS team, RSDuck

    This file is part of melonDS.

    melonDS is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    melonDS is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with melonDS. If not, see http://www.gnu.org/licenses/.
*/

#include "../ARMJIT_x64/ARMJIT_Offsets.h"

.text

#define RCPSR w27
#define RCycles w28
#define RCPU x29

.p2align 4,,15

#ifdef __APPLE__
.global _ARM_Dispatch
_ARM_Dispatch:
#else
.global ARM_Dispatch
ARM_Dispatch:
#endif
    stp x19, x20, [sp, #-128]!
    stp x21, x22, [sp, #16]
    stp x23, x24, [sp, #32]
    stp x25, x26, [sp, #48]
    stp x27, x28, [sp, #64]
    stp x29, x30, [sp, #80]

    str xzr, [sp, #96]

    mov RCPU, x0
    ldr RCycles, [RCPU, ARM_Cycles_offset]
    ldr RCPSR, [RCPU, ARM_CPSR_offset]

    br x1

.p2align 4,,15

#ifdef __APPLE__
.global _ARM_RunBlocks
_ARM_RunBlocks:
#else
.global ARM_RunBlocks
ARM_RunBlocks:
#endif
    stp x19, x20, [sp, #-128]!
    stp x21, x22, [sp, #16]
    stp x23, x24, [sp, #32]
    stp x25, x26, [sp, #48]
    stp x27, x28, [sp, #64]
    stp x29, x30, [sp, #80]

    stp x2, x3, [sp, #96]
    str x4, [sp, #112]

    mov RCPU, x0
    ldr RCycles, [RCPU, ARM_Cycles_offset]
    ldr RCPSR, [RCPU, ARM_CPSR_offset]

    br x1

.p2align 4,,15

#ifdef __APPLE__
.global _ARM_Ret
_ARM_Ret:
#else
.global ARM_Ret
ARM_Ret:
#endif
    str RCPSR, [RCPU, ARM_CPSR_offset]

    ldr x0, [sp, #96]
    cbz x0, .Lsingle_exit

    ldr w1, [RCPU, ARM_StopExecution_offset]
    cbnz w1, .Lsingle_exit

    ldr x1, [x0]
    add x1, x1, RCycles, sxtw
    str x1, [x0]
    mov RCycles, wzr

    ldr x2, [sp, #104]
    ldr x2, [x2]
    cmp x1, x2
    b.hs .Lsingle_exit

    ldr w3, [RCPU, ARM_R15_offset]
    tst RCPSR, #0x20
    sub w4, w3, #4
    sub w5, w3, #2
    csel w3, w5, w4, ne

    ldr w4, [RCPU, ARM_FastBlockLookupStart_offset]
    ldr w5, [RCPU, ARM_FastBlockLookupSize_offset]
    sub w6, w3, w4
    cmp w6, w5
    b.hs .Lsingle_exit

    ldr x7, [RCPU, ARM_FastBlockLookup_offset]
    lsr w6, w6, #1
    ldr x7, [x7, x6, lsl #3]
    ldr w8, [RCPU, ARM_Num_offset]
    orr w3, w3, w8
    lsr x9, x7, #32
    cmp w9, w3
    b.ne .Lsingle_exit

    ldr x10, [sp, #112]
    add x10, x10, w7, uxtw
    br x10

.Lsingle_exit:
    str RCycles, [RCPU, ARM_Cycles_offset]

    ldp x29, x30, [sp, #80]
    ldp x27, x28, [sp, #64]
    ldp x25, x26, [sp, #48]
    ldp x23, x24, [sp, #32]
    ldp x21, x22, [sp, #16]
    ldp x19, x20, [sp], #128

    ret

.p2align 4,,15

.global ARM_RestoreContext
ARM_RestoreContext:
    mov sp, x0

    ldp x0, x1, [sp]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    ldp x8, x9, [sp, #64]
    ldp x10, x11, [sp, #80]
    ldp x12, x13, [sp, #96]
    ldp x14, x15, [sp, #112]
    ldp x16, x17, [sp, #128]
    ldp x18, x19, [sp, #144]
    ldp x20, x21, [sp, #160]
    ldp x22, x23, [sp, #176]
    ldp x24, x25, [sp, #192]
    ldp x26, x27, [sp, #208]
    ldp x28, x29, [sp, #224]
    ldr x30, [sp, #240]

    ldp x17, x18, [sp, #248]
    mov sp, x17

    br x18

#if !defined(__APPLE__) && !defined(__WIN32__)
.section .note.GNU-stack,"",@progbits
#endif

