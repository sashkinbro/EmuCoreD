#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <numeric>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "Args.h"
#include "ARM.h"
#include "ARMJIT.h"
#include "ARMJIT_Memory.h"
#include "GPU.h"
#include "GPU2D_Soft.h"
#include "GPU3D_Soft.h"
#include "GPU3D_Vulkan.h"
#include "NDS.h"
#include "NDSCart.h"
#include "Savestate.h"
#include "SPU.h"
#include "renderer/FrameQueue.h"
#include "renderer/VulkanOutput.h"
#include "xxhash/xxhash.h"

namespace EmuCoreDS {
extern std::string g_user_dir;
extern std::string g_internal_dir;
extern std::string g_sram_path;
extern std::string g_firmware_path;

static bool g_stop_requested = false;
void RequestStop(melonDS::Platform::StopReason) { g_stop_requested = true; }
void WriteNdsSave(const melonDS::u8*, melonDS::u32, melonDS::u32, melonDS::u32) {}
void WriteFirmwareData(const melonDS::u8*, melonDS::u32, melonDS::u32, melonDS::u32) {}
} // namespace EmuCoreDS

using namespace melonDS;

namespace {

struct CliOptions {
    bool self_test = false;
    bool bench = false;
    bool bisect = false;
    bool lockstep = false;
    bool strict_cycles = true;
    std::string rom_path;
    std::string mode = "vulkan_1x";
    std::string dump_ppm;
    int warmup = 120;
    int frames = 180;
    int fastmem = -1; // -1 = default/auto
    int block_size = 32;
    int lit_opt = 1;
    int branch_opt = 1;
    bool verbose = false;
};

double NowMs() {
    using clock = std::chrono::steady_clock;
    return std::chrono::duration<double, std::milli>(clock::now().time_since_epoch()).count();
}

u64 HashBytes(const void* data, size_t size) {
    if (!data || size == 0) return 0;
    return XXH3_64bits(data, size);
}

bool ReadRomFile(const std::string& path, std::unique_ptr<u8[]>& out, size_t& out_size) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const auto sz = f.tellg();
    if (sz <= 0 || static_cast<size_t>(sz) > 512ull * 1024ull * 1024ull) return false;
    out_size = static_cast<size_t>(sz);
    out = std::make_unique<u8[]>(out_size);
    f.seekg(0, std::ios::beg);
    f.read(reinterpret_cast<char*>(out.get()), sz);
    return f.good() || f.eof();
}

std::unique_ptr<NDS> CreateEmptyNds(std::optional<JITArgs> jit_args) {
    NDSArgs args;
    args.Renderer3D = std::make_unique<SoftRenderer>();
    args.JIT = jit_args;
    auto nds = std::make_unique<NDS>(std::move(args), nullptr);
    nds->Reset();
    // Setup permissive PU regions for MainRAM, WRAM, ITCM, IO so bare tests run cleanly.
    nds->ARM9.CP15Write(0x100, 0x00012078);
    nds->ARM9.CP15Write(0x200, 0x00000042);
    nds->ARM9.CP15Write(0x201, 0x00000042);
    nds->ARM9.CP15Write(0x300, 0x00000002);
    nds->ARM9.CP15Write(0x502, 0x15111011);
    nds->ARM9.CP15Write(0x503, 0x05100011);
    nds->ARM9.CP15Write(0x600, 0x04000033);
    nds->ARM9.CP15Write(0x601, 0x04000033);
    nds->ARM9.CP15Write(0x610, 0x0200002B);
    nds->ARM9.CP15Write(0x611, 0x0200002B);
    nds->ARM9.CP15Write(0x620, 0x00000000);
    nds->ARM9.CP15Write(0x621, 0x00000000);
    nds->ARM9.CP15Write(0x630, 0x08000035);
    nds->ARM9.CP15Write(0x631, 0x08000035);
    nds->ARM9.CP15Write(0x640, 0x0300001B);
    nds->ARM9.CP15Write(0x641, 0x0300001B);
    nds->ARM9.CP15Write(0x670, 0x027FF017);
    nds->ARM9.CP15Write(0x671, 0x027FF017);
    nds->ARM9.CP15Write(0x910, 0x0300000A);
    nds->ARM9.CP15Write(0x911, 0x00000020);
    nds->MapSharedWRAM(3);
    nds->SetARM9RegionTimings(0x02000, 0x02400, Mem9_MainRAM, 16, 10, 2);
    nds->Start();
    return nds;
}

std::unique_ptr<NDS> CreateRomNds(const std::string& rom_path,
                                  std::optional<JITArgs> jit_args,
                                  bool use_vulkan,
                                  int scale,
                                  bool threaded_soft) {
    std::unique_ptr<u8[]> rom_data;
    size_t rom_size = 0;
    if (!ReadRomFile(rom_path, rom_data, rom_size)) return nullptr;

    NDSArgs args;
    if (use_vulkan) {
        args.Renderer3D = VulkanRenderer3D::New();
    } else {
        args.Renderer3D = std::make_unique<SoftRenderer>();
    }
    args.JIT = jit_args;
    auto nds = std::make_unique<NDS>(std::move(args), nullptr);
    auto cart = NDSCart::ParseROM(std::move(rom_data), static_cast<u32>(rom_size), nullptr, {});
    if (!cart) return nullptr;
    nds->SetNDSCart(std::move(cart));
    if (use_vulkan) {
        if (auto* r3d = dynamic_cast<VulkanRenderer3D*>(&nds->GPU.GetRenderer3D())) {
            r3d->SetRenderSettings(false, true, scale, false, 0.0f, 0.0f, false, false, false, nds->GPU);
        }
    } else {
        if (auto* soft = dynamic_cast<SoftRenderer*>(&nds->GPU.GetRenderer3D())) {
            soft->SetThreaded(threaded_soft, nds->GPU);
        }
    }
    nds->Reset();
    nds->SetupDirectBoot(rom_path);
    nds->RTC.SetDateTime(2026, 1, 1, 12, 0, 0);
    nds->Start();
    return nds;
}

struct CpuStateSnapshot {
    u32 r[16]{};
    u32 cpsr = 0;
    u32 r_fiq[8]{};
    u32 r_svc[3]{};
    u32 r_abt[3]{};
    u32 r_irq[3]{};
    u32 r_und[3]{};
    u64 cycles = 0;
};

CpuStateSnapshot CaptureCpu(const ARM& cpu, u64 timestamp) {
    CpuStateSnapshot s;
    std::memcpy(s.r, cpu.R, sizeof(s.r));
    s.cpsr = cpu.CPSR;
    std::memcpy(s.r_fiq, cpu.R_FIQ, sizeof(s.r_fiq));
    std::memcpy(s.r_svc, cpu.R_SVC, sizeof(s.r_svc));
    std::memcpy(s.r_abt, cpu.R_ABT, sizeof(s.r_abt));
    std::memcpy(s.r_irq, cpu.R_IRQ, sizeof(s.r_irq));
    std::memcpy(s.r_und, cpu.R_UND, sizeof(s.r_und));
    s.cycles = timestamp;
    return s;
}

struct MicroTestCase {
    std::string name;
    int cpu_num = 0; // 0 = ARM9, 1 = ARM7
    bool thumb = false;
    u32 start_addr = 0x02001000;
    u32 halt_addr = 0x0200F000;
    u32 init_cpsr = 0x000000DF; // System mode, IRQ/FIQ disabled
    std::array<u32, 16> init_r{};
    std::vector<u32> arm_words;
    std::vector<u16> thumb_words;
    std::vector<std::pair<u32, u32>> init_mem32;
    bool check_cycles = true;
    int max_steps = 2048;
};

// Runs a crafted sequence on ARM9 or ARM7.
// Sentinel halt_addr contains a dedicated halt block so previous blocks finish and report exact Cycles.
CpuStateSnapshot RunMicroSequence(NDS& nds, const MicroTestCase& tc, bool use_jit, u64& out_ram_hash) {
    NDS::Current = &nds;
    ARM& cpu = (tc.cpu_num == 0) ? static_cast<ARM&>(nds.ARM9) : static_cast<ARM&>(nds.ARM7);
    nds.CurCPU = tc.cpu_num;

    for (auto& [addr, val] : tc.init_mem32) {
        if (tc.cpu_num == 0) nds.ARM9Write32(addr, val);
        else nds.ARM7Write32(addr, val);
    }

    u32 addr = tc.start_addr;
    if (!tc.thumb) {
        for (u32 w : tc.arm_words) {
            if (tc.cpu_num == 0) nds.ARM9Write32(addr, w);
            else nds.ARM7Write32(addr, w);
            addr += 4;
        }
        // Jump to halt_addr preserving R0..R14 and CPSR: LDR PC, [PC, #-4]; .word halt_addr
        if (tc.cpu_num == 0) {
            nds.ARM9Write32(addr, 0xE51FF004u);
            nds.ARM9Write32(addr + 4, tc.halt_addr);
        } else {
            nds.ARM7Write32(addr, 0xE51FF004u);
            nds.ARM7Write32(addr + 4, tc.halt_addr);
        }
    } else {
        for (u16 hw : tc.thumb_words) {
            if (tc.cpu_num == 0) nds.ARM9Write16(addr, hw);
            else nds.ARM7Write16(addr, hw);
            addr += 2;
        }
        // Align to 4 bytes, LDR R7, [PC, #0] or use a clean Thumb->ARM BX R7 via trampoline:
        // Actually in Thumb: if we overwrite R7 only at the very end, wait: what if test checks R7?
        // Use PUSH {R7} before? Better: PUSH {R7} modifies SP.
        // Instead, put a tiny ARM/Thumb halt check: loop step-by-step on Interpreter, and for JIT
        // make halt_addr reachable by `B halt_addr`? No, BranchOptimizations follows static `B`!
        // With `POP {PC}`? If we push `halt_addr` at SP-4? No, clobbers RAM.
        // Simplest: for Thumb tests, put `0x4738` (BX R7) and set R7 = halt_addr inside test OR
        // in Thumb tests don't inspect R7 in the final epilogue, OR save R7 at a scratch slot `0x0200E000`
        // (and restore R7 after reading)! Let's use `LDR R7, [PC, #4]; BX R7; .align 4; .word halt_addr`,
        // AND for Thumb tests compare R0..R6, R8..R14, CPSR, RAM (save/don't rely on R7, or test R7 via MOV R0, R7 before epilogue).
        if ((addr & 2u) != 0) {
            if (tc.cpu_num == 0) nds.ARM9Write16(addr, 0x46C0u); // NOP
            else nds.ARM7Write16(addr, 0x46C0u);
            addr += 2;
        }
        // PC at addr is (addr + 4) & ~3 = addr + 4. LDR R7, [PC, #0] -> 0x4F00; BX R7 -> 0x4738; .word halt_addr
        const u16 ldr_r7 = 0x4F00u;
        const u16 bx_r7 = 0x4738u;
        if (tc.cpu_num == 0) {
            nds.ARM9Write16(addr, ldr_r7);
            nds.ARM9Write16(addr + 2, bx_r7);
            nds.ARM9Write32(addr + 4, tc.halt_addr);
        } else {
            nds.ARM7Write16(addr, ldr_r7);
            nds.ARM7Write16(addr + 2, bx_r7);
            nds.ARM7Write32(addr + 4, tc.halt_addr);
        }
    }

    // Write dedicated halt stub at tc.halt_addr
    if (tc.cpu_num == 0) {
        nds.ARM9Write32(tc.halt_addr, 0xEE070F90u); // MCR p15, 0, r0, c7, c0, 4 (Wait for IRQ -> Halt(1))
        nds.ARM9Write32(tc.halt_addr + 4, 0xEAFFFFFEu); // B .
    } else {
        // ARM7 HALTCNT = 0x80 at 0x04000301
        nds.ARM7Write32(tc.halt_addr + 0, 0xE3A00080u); // MOV R0, #0x80
        nds.ARM7Write32(tc.halt_addr + 4, 0xE59F1004u); // LDR R1, [PC, #4]
        nds.ARM7Write32(tc.halt_addr + 8, 0xE5C10000u); // STRB R0, [R1]
        nds.ARM7Write32(tc.halt_addr + 12, 0xEAFFFFFEu); // B .
        nds.ARM7Write32(tc.halt_addr + 16, 0x04000301u);
    }

    if (use_jit) nds.JIT.ResetBlockCache();

    for (int i = 0; i < 15; ++i) cpu.R[i] = tc.init_r[i];
    cpu.CPSR = tc.init_cpsr & ~0x20u;
    cpu.UpdateMode(0x13, cpu.CPSR, true);
    cpu.Cycles = 0;
    cpu.StopExecution = 0;
    cpu.JumpTo(tc.start_addr | (tc.thumb ? 1u : 0u), false);
    // Reset cycle counter after initial JumpTo pipeline fill so we only measure test instructions!
    cpu.Cycles = 0;
    nds.ARM9Timestamp = 0;
    nds.ARM7Timestamp = 0;

    auto step_loop = [&](auto&& exec_fn) {
        for (int step = 0; step < tc.max_steps; ++step) {
            const u32 cur_pc = cpu.R[15] - ((cpu.CPSR & 0x20u) ? 2u : 4u);
            if (cur_pc == tc.halt_addr) break;
            if (tc.cpu_num == 0) {
                nds.ARM9Target = nds.ARM9Timestamp + 1;
                exec_fn();
                if (nds.ARM9.Halted) break;
            } else {
                nds.ARM7Target = nds.ARM7Timestamp + 1;
                exec_fn();
                if (nds.ARM7.Halted) break;
            }
        }
    };

    if (use_jit) {
        if (tc.cpu_num == 0) step_loop([&] { nds.ARM9.Execute<CPUExecuteMode::JIT>(); });
        else step_loop([&] { nds.ARM7.Execute<CPUExecuteMode::JIT>(); });
    } else {
        if (tc.cpu_num == 0) step_loop([&] { nds.ARM9.Execute<CPUExecuteMode::Interpreter>(); });
        else step_loop([&] { nds.ARM7.Execute<CPUExecuteMode::Interpreter>(); });
    }

    out_ram_hash = HashBytes(nds.MainRAM, 0x400000) ^ HashBytes(nds.ARM7WRAM, nds.ARM7WRAMSize);
    const u64 ts = (tc.cpu_num == 0) ? nds.ARM9Timestamp : nds.ARM7Timestamp;
    return CaptureCpu(cpu, ts);
}

std::vector<MicroTestCase> BuildMicroTests() {
    std::vector<MicroTestCase> tests;

    // 1. ARM9 Conditional ALU + Flags + Skip cycles
    {
        MicroTestCase tc;
        tc.name = "arm9_cond_alu_flags";
        tc.init_r = {0x12345678, 0x80000001, 0x7FFFFFFF, 0x00000005, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x02008000, 0, 0};
        tc.arm_words = {
            0xE1510002, // CMP R1, R2
            0x00804003, // ADDEQ R4, R0, R3 (not taken)
            0x10905003, // ADDSNE R5, R0, R3 (taken)
            0xE2556001, // SUBS R6, R5, #1
            0xE0A57006, // ADC R7, R5, R6
            0xE0D78001, // SBCS R8, R7, R1
            0xE0F89002, // RSCS R9, R8, R2
            0xE26A0000, // RSB R0, R10, #0 -> wait: R10=7, RSB R10, R10, #0 = 0xE26AA000
            0xE26AA000, // RSB R10, R10, #0
            0xE2EA10FF, // RSC R11, R10, #0xFF
            0xE16FA012, // CLZ R10, R2
        };
        tests.push_back(std::move(tc));
    }

    // 2. ARM9 Multiplies (MUL, MLA, UMULL, SMULL, UMLAL, SMLAL, SMLAxy, SMLALxy, SMULxy, SMLAWy, SMULWy)
    {
        MicroTestCase tc;
        tc.name = "arm9_muls_dsp";
        tc.init_r = {0xFFFF8001u, 0x00020003u, 0x12345678u, 0x80000000u, 0x00001111u, 0x22223333u, 0x7FFFFFFFu, 0x00000001u, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        tc.arm_words = {
            0xE0080190, // MUL R8, R0, R1
            0xE0392190, // MLAS R9, R0, R1, R2
            0xE08B9190, // UMULL R9, R11, R0, R1 (RdLo=R9, RdHi=R11)
            0xE0C98190, // SMULL R8, R9, R0, R1
            0xE0B54190, // UMLALS R4, R5, R0, R1
            0xE0F54190, // SMLALS R4, R5, R0, R1
            0xE10A6180, // SMLABB R10, R0, R1, R6 (sets Q flag!)
            0xE14B4180, // SMLALBB R4, R11, R0, R1 (RdLo=R4, RdHi=R11)
            0xE16C01E0, // SMULTT R12, R0, R1
            0xE1236180, // SMLAWB R3, R0, R1, R6
            0xE12701E0, // SMULWT R7, R0, R1
        };
        tests.push_back(std::move(tc));
    }

    // 3. ARM7 Multiplies (tests ARM7 dynamic CLS/CLZ cycle calculation!)
    {
        MicroTestCase tc;
        tc.name = "arm7_mul_cycles";
        tc.cpu_num = 1;
        tc.start_addr = 0x03801000;
        tc.halt_addr = 0x0380F000;
        tc.init_r = {0x00000005u, 0x000000FFu, 0x0001FFFFu, 0x12345678u, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0x03808000u, 0, 0};
        tc.arm_words = {
            0xE0060091, // MUL R6, R1, R0 (Rs=R0=5 -> 1 byte)
            0xE0273192, // MLA R7, R2, R1, R3 (Rs=R1=0xFF)
            0xE0898290, // UMULL R8, R9, R0, R2 (Rs=R2=0x1FFFF)
            0xE0DBAB93, // SMULLS R10, R11, R3, R3 (Rs=R3=0x12345678)
        };
        tests.push_back(std::move(tc));
    }

    // 4. ARM9 Load/Store + Unaligned ROR + Post-index + Writeback + Signed half/byte
    {
        MicroTestCase tc;
        tc.name = "arm9_load_store_unaligned";
        tc.init_r = {0x02004000u, 0xAABBCCDDu, 0x11223344u, 0x00008081u, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        tc.init_mem32 = {
            {0x02004000u, 0x12345678u},
            {0x02004004u, 0x9ABCDEF0u},
            {0x02004008u, 0x807F8001u},
        };
        tc.arm_words = {
            0xE5904000, // LDR R4, [R0]
            0xE5905001, // LDR R5, [R0, #1] (unaligned ROR by 8!)
            0xE4906004, // LDR R6, [R0], #4 (post-index)
            0xE1D070F0, // LDRSH R7, [R0, #0]
            0xE1D080D2, // LDRSB R8, [R0, #2]
            0xE5B01004, // STR R1, [R0, #4]! (pre-index writeback)
            0xE5D09005, // LDRB R9, [R0, #1]
            0xE1C030B4, // STRH R3, [R0, #4]
            0xE1D0A0B4, // LDRH R10, [R0, #4]
        };
        tests.push_back(std::move(tc));
    }

    // 5. ARM9 LDM/STM (multiple registers, writeback, unaligned base, LDM load)
    {
        MicroTestCase tc;
        tc.name = "arm9_ldm_stm";
        tc.init_r = {0x02005002u, 0x11111111u, 0x22222222u, 0x33333333u, 0x44444444u, 0x55555555u, 0, 0, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        tc.arm_words = {
            0xE92D003E, // STMFD SP!, {R1-R5} (5 regs = odd count!)
            0xE8BD0F80, // LDMFD SP!, {R7-R11} (load odd count -> exercises LDM fastmem!)
            0xE8A0001E, // STMIA R0!, {R1-R4} (unaligned base R0=0x02005002!)
            0xE910000F, // LDMDB R0, {R0-R3} (Rn in reglist!)
        };
        tests.push_back(std::move(tc));
    }

    // 6. ARM9 Branches, ARM<->Thumb BX/BLX, Thumb ALU + PUSH/POP + LDMIA/STMIA
    {
        MicroTestCase tc;
        tc.name = "arm9_thumb_interwork";
        tc.init_r = {10, 20, 30, 40, 50, 60, 0x02002001u, 0x0200F000u, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        // At 0x02001000 (ARM): BLX to Thumb at 0x02002001? Wait: put Thumb stub at 0x02002000!
        // Wait: Write Thumb words into 0x02002000 via init_mem32:
        // 0x02002000: PUSH {R0, R1, R2, LR} (0xB507)
        // 0x02002002: ADD R0, R0, R1        (0x1840)
        // 0x02002004: LSL R0, R0, #2        (0x0080)
        // 0x02002006: CMP R0, #120          (0x2878)
        // 0x02002008: BEQ +2                (0xD001)
        // 0x0200200A: ADD R0, #99           (0x3063)
        // 0x0200200C: POP {R1, R2, R3, PC}  (0xBD0E)
        tc.init_mem32 = {
            {0x02002000u, (0x1840u << 16) | 0xB507u},
            {0x02002004u, (0x2878u << 16) | 0x0080u},
            {0x02002008u, (0x3063u << 16) | 0xD000u},
            {0x0200200Cu, (0x46C0u << 16) | 0xBD0Eu},
        };
        tc.arm_words = {
            0xE12FFF36, // BLX R6 (jumps to 0x02002001 Thumb!)
            0xE2804007, // ADD R4, R0, #7
        };
        tests.push_back(std::move(tc));
    }

    // 7. Thumb mode standalone (shifts, ASR/ROR reg, NEG, PUSH/POP, LDMIA/STMIA)
    {
        MicroTestCase tc;
        tc.name = "thumb_alu_mem";
        tc.thumb = true;
        tc.init_r = {0x80000001u, 4, 0x02004800u, 0x12345678u, 0xABCDEF01u, 5, 6, 0x0200F000u, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        tc.thumb_words = {
            0x4108, // ASR R0, R1
            0x41C8, // ROR R0, R1
            0x4240, // NEG R0, R0
            0xB419, // PUSH {R0, R3, R4}
            0xBC32, // POP {R1, R4, R5}
            0xC219, // STMIA R2!, {R0, R3, R4}
            0x3A0C, // SUB R2, #12
            0xCA30, // LDMIA R2!, {R4, R5}
        };
        tests.push_back(std::move(tc));
    }

    // 8. Self-modifying code invalidation
    {
        MicroTestCase tc;
        tc.name = "arm9_smc_invalidate";
        tc.init_r = {0x02001010u, 0xE3A0202Au, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        // 0x02001000: STR R1, [R0]      (overwrites MOV R2, #1 at 0x02001010 with MOV R2, #42!)
        // 0x02001004: MOV R3, #7
        // 0x02001008: NOP
        // 0x0200100C: NOP
        // 0x02001010: MOV R2, #1 -> overwritten to MOV R2, #42 (0xE3A0202Au)
        tc.arm_words = {
            0xE5801000, // STR R1, [R0]
            0xE3A03007, // MOV R3, #7
            0xE1A00000, // NOP
            0xE1A00000, // NOP
            0xE3A02001, // MOV R2, #1
        };
        tests.push_back(std::move(tc));
    }

    // 9. ARM7 MUL/MLA/UMULL/SMULL cycle boundaries (0, 0x80, 0xFF, 0x100, 0xFFFFFF00, 0x80000000, 0xFFFFFFFF)
    {
        MicroTestCase tc;
        tc.name = "arm7_mul_boundaries";
        tc.cpu_num = 1;
        tc.start_addr = 0x03801000;
        tc.halt_addr = 0x0380F000;
        tc.init_r = {0u, 0x80u, 0xFFu, 0x100u, 0xFFFFFF00u, 0x80000000u, 0xFFFFFFFFu, 0x12345678u, 0, 0, 0, 0, 0, 0x03808000u, 0, 0};
        tc.arm_words = {
            0xE0080097, // MUL R8, R7, R0   (Rs=0 -> 1I)
            0xE0080197, // MUL R8, R7, R1   (Rs=0x80 -> 1I)
            0xE0080297, // MUL R8, R7, R2   (Rs=0xFF -> 1I)
            0xE0080397, // MUL R8, R7, R3   (Rs=0x100 -> 2I)
            0xE0080497, // MUL R8, R7, R4   (Rs=0xFFFFFF00 -> 1I)
            0xE0080597, // MUL R8, R7, R5   (Rs=0x80000000 -> 4I)
            0xE0080697, // MUL R8, R7, R6   (Rs=0xFFFFFFFF -> 1I)
            0xE0280497, // MLA R8, R7, R4, R0 (Rs=0xFFFFFF00 -> 2I)
            0xE0898497, // UMULL R8, R9, R7, R4 (Rs=0xFFFFFF00 -> 5I!)
            0xE0A98197, // UMLAL R8, R9, R7, R1 (Rs=0x80 -> 2I)
            0xE0C98497, // SMULL R8, R9, R7, R4 (Rs=0xFFFFFF00 -> 2I!)
            0xE0E98697, // SMLAL R8, R9, R7, R6 (Rs=0xFFFFFFFF -> 2I)
        };
        tests.push_back(std::move(tc));
    }

    // 10. Conditional skipped ALU/LDR/MUL instructions + RSC imm + SMLALxy
    {
        MicroTestCase tc;
        tc.name = "arm9_cond_skip_and_dsp";
        tc.init_r = {0x02004000u, 5u, 10u, 0x11223344u, 0x00020003u, 0xFFFF8001u, 1u, 2u, 0, 0, 0, 0, 0, 0x02008000u, 0, 0};
        tc.init_mem32 = {{0x02004000u, 0xCAFEBABEu}};
        tc.arm_words = {
            0xE1510002, // CMP R1, R2 (Z=0, C=0)
            0x05908000, // LDREQ R8, [R0] (skipped! must charge 1C only)
            0x00090495, // MULEQ R9, R5, R4 (skipped! must charge 1C only)
            0x1590A000, // LDRNE R10, [R0] (taken!)
            0xE2EA30FF, // RSC R3, R10, #0xFF
            0xE1487584, // SMLALBB R7, R8, R4, R5 (RdHi=R8 -> tests EncodeRegTo64!)
            0xE0898095, // UMULL R8, R9, R5, R0 (RdHi=R9, no S)
        };
        tests.push_back(std::move(tc));
    }

    // 11. ARM7 LDM with Rn in reglist and writeback bit set (must NOT writeback!)
    {
        MicroTestCase tc;
        tc.name = "arm7_ldm_rn_writeback";
        tc.cpu_num = 1;
        tc.start_addr = 0x03801000;
        tc.halt_addr = 0x0380F000;
        tc.init_r = {0, 0x03804000u, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x03808000u, 0, 0};
        tc.init_mem32 = {
            {0x03804000u, 0xAAAAAAAAu},
            {0x03804004u, 0xBBBBBBBBu},
            {0x03804008u, 0xCCCCCCCCu},
        };
        tc.arm_words = {
            0xE8B1000E, // LDMIA R1!, {R1, R2, R3} (R1 is in list and R3 > R1 -> ARMv4 loads R1=0xAAAAAAAA, NO writeback!)
        };
        tests.push_back(std::move(tc));
    }

    return tests;
}

int RunSelfTests(const CliOptions& opt) {
    auto tests = BuildMicroTests();
    const bool try_fastmem = (opt.fastmem != 0) && ARMJIT_Memory::IsFastMemSupported();
    std::cout << "[SelfTest] FastMemSupported=" << ARMJIT_Memory::IsFastMemSupported()
              << " testing_fastmem=" << try_fastmem
              << " strict_cycles=" << opt.strict_cycles << "\n";

    JITArgs jit_args;
    jit_args.MaxBlockSize = static_cast<unsigned>(opt.block_size);
    jit_args.LiteralOptimizations = opt.lit_opt != 0;
    jit_args.BranchOptimizations = opt.branch_opt != 0;
    jit_args.FastMemory = try_fastmem;

    auto nds_interp = CreateEmptyNds(std::nullopt);
    auto nds_jit = CreateEmptyNds(jit_args);

    int failures = 0;
    for (const auto& tc : tests) {
        u64 ram_i = 0, ram_j = 0;
        const auto si = RunMicroSequence(*nds_interp, tc, false, ram_i);
        const auto sj = RunMicroSequence(*nds_jit, tc, true, ram_j);

        bool ok = true;
        std::ostringstream diff;
        const int max_reg = tc.thumb ? 15 : 15;
        for (int r = 0; r < max_reg; ++r) {
            if (tc.thumb && r == 7) continue; // epilogue uses R7 as branch target
            if (si.r[r] != sj.r[r]) {
                ok = false;
                diff << " R" << r << "(I=0x" << std::hex << si.r[r] << " J=0x" << sj.r[r] << ")" << std::dec;
            }
        }
        if (si.cpsr != sj.cpsr) {
            ok = false;
            diff << " CPSR(I=0x" << std::hex << si.cpsr << " J=0x" << sj.cpsr << ")" << std::dec;
        }
        if (ram_i != ram_j) {
            ok = false;
            diff << " RAM_HASH(I=0x" << std::hex << ram_i << " J=0x" << ram_j << ")" << std::dec;
        }
        if (opt.strict_cycles && tc.check_cycles && si.cycles != sj.cycles) {
            ok = false;
            diff << " CYCLES(I=" << si.cycles << " J=" << sj.cycles << ")";
        }
        std::cout << (ok ? "[PASS] " : "[FAIL] ") << tc.name
                  << " cycles(I=" << si.cycles << ", J=" << sj.cycles << ")";
        if (!ok) {
            std::cout << " DIFF:" << diff.str();
            failures++;
        }
        std::cout << "\n";
    }
    return failures == 0 ? 0 : 1;
}

struct FrameSnapshot {
    u32 frame = 0;
    u64 arm9_ts = 0;
    u64 arm7_ts = 0;
    u32 arm9_r[16]{};
    u32 arm9_cpsr = 0;
    u32 arm7_r[16]{};
    u32 arm7_cpsr = 0;
    u64 main_ram_hash = 0;
    u64 swram_hash = 0;
    u64 wram7_hash = 0;
    u64 fb_top_hash = 0;
    u64 fb_bot_hash = 0;
};

void ApplyScriptedInput(NDS& nds, int frame) {
    // Deterministic input sequence to navigate common DS menus if needed.
    // Bits: 0=A, 1=B, 2=Sel, 3=Start, 4=Right, 5=Left, 6=Up, 7=Down
    u32 mask = 0xFFFu;
    if (frame >= 90 && frame < 96) mask &= ~(1u << 3);   // Start
    if (frame >= 120 && frame < 126) mask &= ~(1u << 0); // A
    if (frame >= 150 && frame < 156) mask &= ~(1u << 0); // A
    nds.SetKeyMask(mask);
    if (frame >= 105 && frame < 111) nds.TouchScreen(128, 96);
    else if (frame == 111) nds.ReleaseScreen();
}

std::vector<FrameSnapshot> CaptureRomTrace(const std::string& rom_path,
                                           std::optional<JITArgs> jit_args,
                                           int frames,
                                           std::vector<u32>* out_last_fb = nullptr) {
    auto nds = CreateRomNds(rom_path, jit_args, false, 1, false);
    std::vector<FrameSnapshot> trace;
    if (!nds) return trace;
    trace.reserve(static_cast<size_t>(frames));
    for (int f = 0; f < frames; ++f) {
        ApplyScriptedInput(*nds, f);
        nds->RunFrame();
        FrameSnapshot s;
        s.frame = static_cast<u32>(f);
        s.arm9_ts = nds->ARM9Timestamp;
        s.arm7_ts = nds->ARM7Timestamp;
        std::memcpy(s.arm9_r, nds->ARM9.R, sizeof(s.arm9_r));
        s.arm9_cpsr = nds->ARM9.CPSR;
        std::memcpy(s.arm7_r, nds->ARM7.R, sizeof(s.arm7_r));
        s.arm7_cpsr = nds->ARM7.CPSR;
        s.main_ram_hash = HashBytes(nds->MainRAM, 0x400000);
        s.swram_hash = HashBytes(nds->SharedWRAM, nds->SharedWRAMSize);
        s.wram7_hash = HashBytes(nds->ARM7WRAM, nds->ARM7WRAMSize);
        const int front = nds->GPU.FrontBuffer;
        s.fb_top_hash = HashBytes(nds->GPU.Framebuffer[front][0].get(), 256 * 192 * 4);
        s.fb_bot_hash = HashBytes(nds->GPU.Framebuffer[front][1].get(), 256 * 192 * 4);
        trace.push_back(s);
        if (out_last_fb && f == frames - 1) {
            out_last_fb->resize(256 * 192 * 2);
            std::memcpy(out_last_fb->data(), nds->GPU.Framebuffer[front][0].get(), 256 * 192 * 4);
            std::memcpy(out_last_fb->data() + 256 * 192, nds->GPU.Framebuffer[front][1].get(), 256 * 192 * 4);
        }
    }
    nds->Stop();
    return trace;
}

void PrintFbDiffMetrics(const std::vector<u32>& a, const std::vector<u32>& b) {
    if (a.size() != b.size() || a.empty()) return;
    size_t diff_pixels = 0;
    double sq_err = 0.0;
    int max_delta = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        const u32 pa = a[i];
        const u32 pb = b[i];
        if ((pa & 0xFFFFFFu) != (pb & 0xFFFFFFu)) diff_pixels++;
        for (int c = 0; c < 3; ++c) {
            const int va = static_cast<int>((pa >> (c * 8)) & 0xFFu);
            const int vb = static_cast<int>((pb >> (c * 8)) & 0xFFu);
            const int d = std::abs(va - vb);
            max_delta = std::max(max_delta, d);
            sq_err += static_cast<double>(d * d);
        }
    }
    const double mse = sq_err / (static_cast<double>(a.size()) * 3.0);
    const double psnr = mse > 0.0 ? (10.0 * std::log10((255.0 * 255.0) / mse)) : 99.99;
    const double diff_pct = (100.0 * static_cast<double>(diff_pixels)) / static_cast<double>(a.size());
    std::cout << "  LastFB Diff: " << std::fixed << std::setprecision(2) << diff_pct
              << "% pixels differ, maxChannelDelta=" << max_delta
              << ", PSNR=" << std::setprecision(2) << psnr << " dB\n";
}

int RunRomDiffTest(const CliOptions& opt) {
    if (opt.rom_path.empty()) {
        std::cerr << "--rom is required\n";
        return 2;
    }
    const bool fastmem = (opt.fastmem == -1) ? ARMJIT_Memory::IsFastMemSupported() : (opt.fastmem != 0);
    JITArgs jit_args;
    jit_args.MaxBlockSize = static_cast<unsigned>(opt.block_size);
    jit_args.LiteralOptimizations = opt.lit_opt != 0;
    jit_args.BranchOptimizations = opt.branch_opt != 0;
    jit_args.FastMemory = fastmem;

    std::cout << "[DiffTest] ROM=" << opt.rom_path << " frames=" << opt.frames
              << " block=" << jit_args.MaxBlockSize
              << " lit=" << jit_args.LiteralOptimizations
              << " branch=" << jit_args.BranchOptimizations
              << " fastmem=" << jit_args.FastMemory << "\n";

    std::vector<u32> fb_interp, fb_jit;
    const auto interp = CaptureRomTrace(opt.rom_path, std::nullopt, opt.frames, &fb_interp);
    const auto jit = CaptureRomTrace(opt.rom_path, jit_args, opt.frames, &fb_jit);
    if (interp.empty() || jit.size() != interp.size()) {
        std::cerr << "[DiffTest] Failed to run ROM\n";
        return 2;
    }

    int first_cpu_div = -1;
    int first_ram_div = -1;
    int first_fb_div = -1;
    int fb_mismatches = 0;
    for (int f = 0; f < opt.frames; ++f) {
        const auto& a = interp[f];
        const auto& b = jit[f];
        const bool cpu_match = std::memcmp(a.arm9_r, b.arm9_r, sizeof(a.arm9_r)) == 0
            && a.arm9_cpsr == b.arm9_cpsr
            && std::memcmp(a.arm7_r, b.arm7_r, sizeof(a.arm7_r)) == 0
            && a.arm7_cpsr == b.arm7_cpsr;
        const bool ram_match = a.main_ram_hash == b.main_ram_hash
            && a.swram_hash == b.swram_hash
            && a.wram7_hash == b.wram7_hash;
        const bool fb_match = a.fb_top_hash == b.fb_top_hash && a.fb_bot_hash == b.fb_bot_hash;
        if (!cpu_match && first_cpu_div < 0) first_cpu_div = f;
        if (!ram_match && first_ram_div < 0) first_ram_div = f;
        if (!fb_match) {
            fb_mismatches++;
            if (first_fb_div < 0) first_fb_div = f;
        }
    }

    std::cout << "  firstCpuDiv=" << first_cpu_div
              << " firstRamDiv=" << first_ram_div
              << " firstFbDiv=" << first_fb_div
              << " fbMismatchFrames=" << fb_mismatches << "/" << opt.frames << "\n";
    PrintFbDiffMetrics(fb_interp, fb_jit);

    if (first_ram_div >= 0 && opt.bisect) {
        std::cout << "  [Bisect] Testing individual JIT toggles...\n";
        for (const char* label : {"fastmem=0", "lit=0", "branch=0", "block=1"}) {
            JITArgs t = jit_args;
            if (std::strcmp(label, "fastmem=0") == 0) t.FastMemory = false;
            if (std::strcmp(label, "lit=0") == 0) t.LiteralOptimizations = false;
            if (std::strcmp(label, "branch=0") == 0) t.BranchOptimizations = false;
            if (std::strcmp(label, "block=1") == 0) t.MaxBlockSize = 1;
            const auto tr = CaptureRomTrace(opt.rom_path, t, opt.frames);
            int div = -1;
            for (int f = 0; f < opt.frames; ++f) {
                if (tr[f].main_ram_hash != interp[f].main_ram_hash) { div = f; break; }
            }
            std::cout << "    " << label << " -> firstRamDiv=" << div << "\n";
        }
    }
    return 0;
}

bool WritePpm(const std::string& path, const u32* bgra_pixels, int width, int height) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out << "P6\n" << width << " " << height << "\n255\n";
    std::vector<u8> row(static_cast<size_t>(width) * 3);
    for (int y = 0; y < height; ++y) {
        const u32* src = bgra_pixels + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; ++x) {
            const u32 p = src[x];
            row[x * 3 + 0] = static_cast<u8>((p >> 16) & 0xFFu);
            row[x * 3 + 1] = static_cast<u8>((p >> 8) & 0xFFu);
            row[x * 3 + 2] = static_cast<u8>(p & 0xFFu);
        }
        out.write(reinterpret_cast<const char*>(row.data()), static_cast<std::streamsize>(row.size()));
    }
    return out.good();
}

int RunBenchmark(const CliOptions& opt) {
    if (opt.rom_path.empty()) {
        std::cerr << "--rom is required\n";
        return 2;
    }

    bool use_jit = true;
    bool use_vulkan = false;
    bool threaded_soft = true;
    int scale = 1;
    bool fastmem = (opt.fastmem == -1) ? ARMJIT_Memory::IsFastMemSupported() : (opt.fastmem != 0);

    if (opt.mode == "interp") {
        use_jit = false;
    } else if (opt.mode == "jit_nofastmem") {
        fastmem = false;
    } else if (opt.mode == "jit_fastmem") {
        fastmem = ARMJIT_Memory::IsFastMemSupported();
    } else if (opt.mode == "soft_1x") {
        use_vulkan = false;
        threaded_soft = true;
    } else if (opt.mode == "vulkan_1x") {
        use_vulkan = true;
        scale = 1;
    } else if (opt.mode == "vulkan_2x") {
        use_vulkan = true;
        scale = 2;
    } else if (opt.mode == "vulkan_4x") {
        use_vulkan = true;
        scale = 4;
    } else {
        std::cerr << "Unknown --mode: " << opt.mode << "\n";
        return 2;
    }

    std::optional<JITArgs> jit_args;
    if (use_jit) {
        JITArgs ja;
        ja.MaxBlockSize = static_cast<unsigned>(opt.block_size);
        ja.LiteralOptimizations = opt.lit_opt != 0;
        ja.BranchOptimizations = opt.branch_opt != 0;
        ja.FastMemory = fastmem;
        jit_args = ja;
    }

    auto nds = CreateRomNds(opt.rom_path, jit_args, use_vulkan, scale, threaded_soft);
    if (!nds) {
        std::cerr << "Failed to load ROM: " << opt.rom_path << "\n";
        return 2;
    }

    std::unique_ptr<MelonDSAndroid::VulkanOutput> vk_out;
    std::array<std::unique_ptr<Frame>, 3> vk_frames;
    size_t vk_cursor = 0;
    u64 vk_frame_id = 0;
    if (use_vulkan) {
        vk_out = std::make_unique<MelonDSAndroid::VulkanOutput>(true);
        if (!vk_out->init()) {
            std::cerr << "VulkanOutput::init() failed!\n";
            return 3;
        }
        for (auto& f : vk_frames) f = std::make_unique<Frame>();
    }

    const int total = opt.warmup + opt.frames;
    std::vector<double> frame_ms;
    frame_ms.reserve(static_cast<size_t>(opt.frames));

    double sum_run_ms = 0, sum_pre_ms = 0, sum_tail_ms = 0, sum_snap_ms = 0, sum_comp_ms = 0, sum_wait_ms = 0;
    u64 poly_sum = 0;

    auto step_one_frame = [&](int f, bool measure) {
        ApplyScriptedInput(*nds, f);
        const double t0 = NowMs();
        double t_pre = 0, t_run = 0, t_tail = 0, t_snap = 0, t_comp = 0, t_wait = 0;

        if (use_vulkan) {
            const double p0 = NowMs();
            if (vk_out->ensureFaithfulAtlas()) {
                vk_out->uploadFaithfulAtlasPreFrame(nds->GPU);
                vk_out->publishFaithfulCertifiedCaptureTerminals(nds->GPU, static_cast<u32>(scale));
            }
            t_pre = NowMs() - p0;
        }

        const double r0 = NowMs();
        nds->RunFrame();
        t_run = NowMs() - r0;

        if (use_vulkan) {
            const double a0 = NowMs();
            if (auto* r2d = dynamic_cast<GPU2D::SoftRenderer*>(&nds->GPU.GetRenderer2D())) {
                r2d->PublishCompletedFaithfulFrame();
            }
            vk_out->uploadFaithfulAtlas(nds->GPU);
            t_tail = NowMs() - a0;

            Frame* frame = vk_frames[vk_cursor].get();
            vk_cursor = (vk_cursor + 1) % vk_frames.size();
            const u32 fw = static_cast<u32>(256 * scale);
            const u32 fh = static_cast<u32>(386 * scale);
            frame->backend = FrameBackend::VulkanImage;
            frame->width = fw;
            frame->height = fh;
            frame->frameId = ++vk_frame_id;
            frame->publicationGeneration = frame->frameId;
            if (vk_out->ensureFrameResources(frame, fw, fh)) {
                auto* r3d = dynamic_cast<VulkanRenderer3D*>(&nds->GPU.GetRenderer3D());
                const double s0 = NowMs();
                vk_out->solicitarSnapshotEnCompose(r3d, false);
                t_snap = NowMs() - s0;

                MelonDSAndroid::VulkanCompositionInputs inputs{};
                if (vk_out->buildCompositionInputs(frame, *r3d, scale,
                                                   MelonDSAndroid::VulkanFilterMode::Nearest,
                                                   false, false, false, inputs)) {
                    const double c0 = NowMs();
                    vk_out->composeAndSubmitFrame(frame, inputs);
                    vk_out->solicitarSnapshotEnCompose(nullptr, false);
                    t_comp = NowMs() - c0;
                    // Pipeline the 3-frame ring across the timed window; drain GPU at
                    // warmup boundary (clean start) and final frame (complete GPU cost).
                    if (f == opt.warmup - 1 || f == total - 1) {
                        const double w0 = NowMs();
                        vk_out->waitForFrame(frame, 100'000'000ull);
                        t_wait = NowMs() - w0;
                    }
                } else {
                    vk_out->solicitarSnapshotEnCompose(nullptr, false);
                }
            }
        } else {
            // Drain SPU output so ring stays realistic
            if (nds->SPU.GetOutputSize() > 0) {
                s16 tmp[4096];
                nds->SPU.ReadOutput(tmp, std::min(nds->SPU.GetOutputSize(), 2048));
            }
        }

        const double total_ms = NowMs() - t0;
        if (measure) {
            frame_ms.push_back(total_ms);
            sum_pre_ms += t_pre;
            sum_run_ms += t_run;
            sum_tail_ms += t_tail;
            sum_snap_ms += t_snap;
            sum_comp_ms += t_comp;
            sum_wait_ms += t_wait;
            poly_sum += nds->GPU.GPU3D.RenderNumPolygons;
        }
    };

    for (int f = 0; f < total; ++f) {
        step_one_frame(f, f >= opt.warmup);
    }

    if (!opt.dump_ppm.empty()) {
        if (use_vulkan) {
            Frame* last = vk_frames[(vk_cursor + vk_frames.size() - 1) % vk_frames.size()].get();
            const u32 fw = static_cast<u32>(256 * scale);
            const u32 fh = static_cast<u32>(386 * scale);
            std::vector<u32> px(static_cast<size_t>(fw) * fh);
            if (vk_out->readFramePixels(last, px.data(), px.size(), 1'000'000'000ull)) {
                WritePpm(opt.dump_ppm, px.data(), static_cast<int>(fw), static_cast<int>(fh));
            }
        } else {
            const int front = nds->GPU.FrontBuffer;
            std::vector<u32> stacked(256 * 386, 0xFF000000u);
            std::memcpy(stacked.data(), nds->GPU.Framebuffer[front][0].get(), 256 * 192 * 4);
            std::memcpy(stacked.data() + 256 * 194, nds->GPU.Framebuffer[front][1].get(), 256 * 192 * 4);
            WritePpm(opt.dump_ppm, stacked.data(), 256, 386);
        }
    }

    if (vk_out) {
        for (auto& f : vk_frames) f.reset();
        vk_out->shutdown();
        vk_out.reset();
    }
    nds->Stop();
    nds.reset();

    std::sort(frame_ms.begin(), frame_ms.end());
    const double sum_total = std::accumulate(frame_ms.begin(), frame_ms.end(), 0.0);
    const double avg = sum_total / static_cast<double>(frame_ms.size());
    const double p50 = frame_ms[frame_ms.size() / 2];
    const double p95 = frame_ms[std::min(frame_ms.size() - 1, (frame_ms.size() * 95) / 100)];
    const double p99 = frame_ms[std::min(frame_ms.size() - 1, (frame_ms.size() * 99) / 100)];
    const double fps = 1000.0 / avg;
    const double n = static_cast<double>(opt.frames);

    std::cout << std::fixed << std::setprecision(2)
              << "BENCH mode=" << opt.mode
              << " fastmem=" << fastmem
              << " frames=" << opt.frames
              << " avgMs=" << avg
              << " p50Ms=" << p50
              << " p95Ms=" << p95
              << " p99Ms=" << p99
              << " maxMs=" << frame_ms.back()
              << " fps=" << fps
              << " avgPolys=" << (static_cast<double>(poly_sum) / n)
              << " | runMs=" << (sum_run_ms / n);
    if (use_vulkan) {
        std::cout << " preMs=" << (sum_pre_ms / n)
                  << " tailMs=" << (sum_tail_ms / n)
                  << " snapMs=" << (sum_snap_ms / n)
                  << " compMs=" << (sum_comp_ms / n)
                  << " waitMs=" << (sum_wait_ms / n);
    }
    std::cout << "\n";
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    CliOptions opt;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&](const char* flag) -> std::string {
            if (i + 1 >= argc) {
                std::cerr << "Missing argument for " << flag << "\n";
                std::exit(2);
            }
            return argv[++i];
        };
        if (a == "--self-test") opt.self_test = true;
        else if (a == "--bench") opt.bench = true;
        else if (a == "--bisect") opt.bisect = true;
        else if (a == "--lockstep") opt.lockstep = true;
        else if (a == "--verbose") opt.verbose = true;
        else if (a == "--no-strict-cycles") opt.strict_cycles = false;
        else if (a == "--rom") opt.rom_path = next("--rom");
        else if (a == "--mode") opt.mode = next("--mode");
        else if (a == "--dump-ppm") opt.dump_ppm = next("--dump-ppm");
        else if (a == "--warmup") opt.warmup = std::stoi(next("--warmup"));
        else if (a == "--frames") opt.frames = std::stoi(next("--frames"));
        else if (a == "--fastmem") opt.fastmem = std::stoi(next("--fastmem"));
        else if (a == "--block-size") opt.block_size = std::stoi(next("--block-size"));
        else if (a == "--lit-opt") opt.lit_opt = std::stoi(next("--lit-opt"));
        else if (a == "--branch-opt") opt.branch_opt = std::stoi(next("--branch-opt"));
    }

    if (opt.self_test) return RunSelfTests(opt);
    if (opt.bench) return RunBenchmark(opt);
    if (!opt.rom_path.empty()) return RunRomDiffTest(opt);

    std::cout << "Usage:\n"
              << "  --self-test [--fastmem 0|1] [--no-strict-cycles]\n"
              << "  --rom <path.nds> --frames N [--fastmem 0|1] [--block-size N] [--lit-opt 0|1] [--branch-opt 0|1] [--bisect]\n"
              << "  --bench --rom <path.nds> --mode interp|jit_nofastmem|jit_fastmem|soft_1x|vulkan_1x|vulkan_2x|vulkan_4x [--warmup W] [--frames N] [--dump-ppm out.ppm]\n";
    return 0;
}
