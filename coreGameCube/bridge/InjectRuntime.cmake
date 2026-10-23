# SPDX-License-Identifier: GPL-3.0-or-later
# Runs at upstream project(), without editing the pinned upstream checkout.
foreach(feature ENABLE_QT ENABLE_TESTS ENABLE_LLVM ENABLE_ANALYTICS ENABLE_AUTOUPDATE
    ENCODE_FRAMEDUMPS USE_UPNP USE_DISCORD_PRESENCE USE_RETRO_ACHIEVEMENTS USE_MGBA
    USE_SANITIZERS ENABLE_SDL)
  set(${feature} OFF CACHE BOOL "EmuCoreD embedded runtime" FORCE)
endforeach()
set(ENABLE_VULKAN ON CACHE BOOL "Vulkan renderer" FORCE)
set(ENABLE_CUBEB ON CACHE BOOL "Android audio" FORCE)
cmake_language(EVAL CODE "cmake_language(DEFER CALL include \"${CMAKE_CURRENT_LIST_DIR}/Runtime.cmake\")")
