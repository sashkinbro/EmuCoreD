cmake_minimum_required(VERSION 3.22)

project(EmuCoreDS LANGUAGES C CXX ASM)

option(EMUCORED_DIAGNOSTICS "Compile diagnostic logging and dump tools" OFF)
option(EMUCORED_BUILD_DS_TOOLS "Build standalone DS JIT difftest and benchmark CLI" OFF)
add_compile_definitions(EMUCORED_DIAGNOSTICS=$<BOOL:${EMUCORED_DIAGNOSTICS}>)

set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
set(ENABLE_JIT ON CACHE BOOL "" FORCE)
set(ENABLE_JIT_PROFILING OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER ON CACHE BOOL "" FORCE)
set(ENABLE_LTO OFF CACHE BOOL "" FORCE)
add_compile_definitions(MELONDS_GL_HEADER=<GLES3/gl32.h>)
add_compile_definitions(VK_NO_PROTOTYPES)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core/externals/libadrenotools ${CMAKE_CURRENT_BINARY_DIR}/adrenotools)
add_subdirectory(melonDS)
target_compile_definitions(core PRIVATE MELONDS_HAS_ADRENOTOOLS=1)
target_link_libraries(core PRIVATE adrenotools)

add_subdirectory(bridge)

if (ANDROID AND EMUCORED_BUILD_DS_TOOLS)
    add_executable(emucored_ds_jit_difftest
        tests/ds_jit_difftest.cpp
        bridge/platform_android.cpp
        bridge/melonds_android_stubs.cpp
        bridge/renderer/VulkanOutput.cpp
    )
    target_include_directories(emucored_ds_jit_difftest PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge/renderer
    )
    target_compile_definitions(emucored_ds_jit_difftest PRIVATE
        MELONDS_GL_HEADER=<GLES3/gl32.h>
    )
    target_link_libraries(emucored_ds_jit_difftest PRIVATE
        core
        android
        log
        EGL
        GLESv3
    )
    set_target_properties(emucored_ds_jit_difftest PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
    )
    target_compile_options(emucored_ds_jit_difftest PRIVATE
        $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-flto=thin>
    )
    target_link_options(emucored_ds_jit_difftest PRIVATE
        $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-flto=thin>
    )
endif()
