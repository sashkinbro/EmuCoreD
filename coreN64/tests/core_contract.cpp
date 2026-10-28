// SPDX-License-Identifier: GPL-3.0-or-later
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include "libretro.h"

int main(int argc, char** argv) {
    if (argc != 2) return 1;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto version = reinterpret_cast<unsigned (*)()>(dlsym(library, "retro_api_version"));
    auto info = reinterpret_cast<void (*)(retro_system_info*)>(dlsym(library, "retro_get_system_info"));
    if (!version || !info || version() != RETRO_API_VERSION) return 3;
    retro_system_info system{};
    info(&system);
    if (!system.library_name || !system.valid_extensions ||
        !std::strstr(system.valid_extensions, "z64") ||
        !std::strstr(system.valid_extensions, "n64") ||
        !std::strstr(system.valid_extensions, "v64")) return 4;
    std::printf("PASS %s %s: %s\n", system.library_name,
        system.library_version ? system.library_version : "", system.valid_extensions);
    return dlclose(library) == 0 ? 0 : 5;
}
