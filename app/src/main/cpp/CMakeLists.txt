# SPDX-FileCopyrightText: 2026 SBRO
# SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
cmake_minimum_required(VERSION 3.22)
project(EmuCoreDAndroid LANGUAGES CXX C)

# Google Play requires 16 KiB page size support: keep every ELF load segment aligned
# even when a toolchain default changes.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The emulator core (Azahar, vendored in /core) is built by the :core-android
# Gradle module and shipped as libemucore_core.so. EmuCoreD talks to it through
# the core's own Android bridge (com.sbro.emucored.core.EmuCoreNative).
#
# This module builds the frontend's own additions that are not part of the core:
# the RetroArch (.slangp) shader chain and the GPU post-processing presenter
# used for the downloadable shader packs.

# Build the same pinned RetroArch .slangp runtime the frontend has always used
# when Cargo is available. The GL effect presenter stays the fallback.
add_subdirectory(third_party/librashader)

set(SHADER_SOURCES
    shader_effect.cpp
    shader_chain.cpp
    shader_chain_present.cpp
    shader_bridge.cpp
)

if(TARGET librashader)
    add_library(emucore_shaders SHARED ${SHADER_SOURCES})
    target_link_libraries(emucore_shaders PRIVATE librashader)
    target_compile_definitions(emucore_shaders PRIVATE EMUCORED_HAVE_LIBRASHADER=1)
else()
    add_library(emucore_shaders SHARED ${SHADER_SOURCES})
endif()

find_library(EGL_LIBRARY EGL)
find_library(GLES_LIBRARY GLESv3)

target_link_libraries(emucore_shaders PRIVATE
    android
    log
    ${EGL_LIBRARY}
    ${GLES_LIBRARY}
)

target_compile_options(emucore_shaders PRIVATE
    -Wall -Wextra
    $<$<NOT:$<CONFIG:Debug>>:-O3>
)

set_target_properties(emucore_shaders PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    OUTPUT_NAME "emucore_shaders"
)

# The Discord Social SDK is optional and runs in the isolated :discord process.
if(DISCORD_SDK_DIR)
    set(DISCORD_SDK_SO "${DISCORD_SDK_DIR}/${ANDROID_ABI}/libdiscord_partner_sdk.so")
    set(DISCORD_SDK_INCLUDE "${DISCORD_SDK_DIR}/include")
    if(EXISTS "${DISCORD_SDK_SO}" AND EXISTS "${DISCORD_SDK_INCLUDE}/discordpp.h")
        add_library(discord_partner_sdk SHARED IMPORTED)
        set_target_properties(discord_partner_sdk PROPERTIES IMPORTED_LOCATION "${DISCORD_SDK_SO}")
        add_library(emucored_discord SHARED discord_bridge.cpp)
        target_include_directories(emucored_discord PRIVATE "${DISCORD_SDK_INCLUDE}")
        target_link_libraries(emucored_discord PRIVATE discord_partner_sdk log)
        set_target_properties(emucored_discord PROPERTIES OUTPUT_NAME "emucored_discord")
    endif()
endif()
