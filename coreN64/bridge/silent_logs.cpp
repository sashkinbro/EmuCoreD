// SPDX-License-Identifier: GPL-3.0-or-later
// Linker wrappers apply only to the release libretro core, not the host process.
#include <cstdarg>

extern "C" int __wrap___android_log_print(int, const char*, const char*, ...) { return 0; }
extern "C" int __wrap___android_log_vprint(int, const char*, const char*, va_list) { return 0; }
extern "C" int __wrap___android_log_write(int, const char*, const char*) { return 0; }
