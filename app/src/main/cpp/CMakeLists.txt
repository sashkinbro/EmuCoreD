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
# the core's own Android bridge (com.sbro.emucored.core.EmuCoreNative), so this
# module only builds the small helper libraries that are not part of the core.

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
