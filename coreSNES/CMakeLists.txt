# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(EmuCoreSNES LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(EMUCORED_DIAGNOSTICS "Enable development diagnostics" OFF)
set(BSNES_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/bsnes")
set(BSNES_SRC "${BSNES_ROOT}/bsnes")
# Match the pinned upstream core-only Makefiles; included implementation .cpp
# fragments must not be globbed into duplicate translation units.
set(BSNES_SOURCES bridge/bsnes_core.cpp ${BSNES_ROOT}/libco/libco.c)
foreach(unit emulator/emulator filter/filter lzma/lzma
        sfc/interface/interface sfc/system/system sfc/controller/controller
        sfc/cartridge/cartridge sfc/memory/memory sfc/cpu/cpu sfc/smp/smp
        sfc/dsp/dsp sfc/ppu/ppu sfc/ppu-fast/ppu sfc/expansion/expansion
        sfc/coprocessor/coprocessor sfc/slot/slot
        processor/arm7tdmi/arm7tdmi processor/spc700/spc700 processor/wdc65816/wdc65816)
    list(APPEND BSNES_SOURCES "${BSNES_SRC}/${unit}.cpp")
endforeach()
# bsnes uses this upstream SameBoy revision internally for Super Game Boy.
# Ordinary GB/GBC sessions use our independent modern SameBoy module.
foreach(unit apu camera rumble display gb joypad mbc memory printer random rewind save_state sgb sm83_cpu timing)
    list(APPEND BSNES_SOURCES "${BSNES_SRC}/gb/Core/${unit}.c")
endforeach()
add_library(emucored_bsnes_core STATIC ${BSNES_SOURCES})
target_include_directories(emucored_bsnes_core PUBLIC "${BSNES_SRC}/target-libretro" PRIVATE "${BSNES_ROOT}" "${BSNES_SRC}")
target_compile_definitions(emucored_bsnes_core PRIVATE __LIBRETRO__ PLATFORM_ANDROID GB_INTERNAL GB_DISABLE_DEBUGGER GB_DISABLE_CHEATS _GNU_SOURCE GB_VERSION="1.0.2")
target_compile_options(emucored_bsnes_core PRIVATE -O3 -Wno-multichar -Wno-narrowing)
target_link_libraries(emucored_bsnes_core PUBLIC m)
option(EMUCORED_BUILD_ANDROID_RUNTIME "Build own Android session adapter" OFF)
if(ANDROID AND EMUCORED_BUILD_ANDROID_RUNTIME)
    add_library(emucored_snes SHARED bridge/session.cpp)
    target_include_directories(emucored_snes PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../core-common")
    target_link_libraries(emucored_snes PRIVATE emucored_bsnes_core android aaudio EGL GLESv2)
    target_link_options(emucored_snes PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
endif()
option(EMUCORED_BUILD_CORE_TESTS "Build original standalone native contracts" OFF)
if(EMUCORED_BUILD_CORE_TESTS)
    target_sources(emucored_bsnes_core PRIVATE tests/bus_probe.cpp)
    add_executable(emucored_bsnes_contract_test tests/bsnes_contract_test.cpp)
    target_link_libraries(emucored_bsnes_contract_test PRIVATE emucored_bsnes_core)
endif()
