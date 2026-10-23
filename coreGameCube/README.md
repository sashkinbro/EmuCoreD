# GameCube and Wii: Dolphin

- Official upstream: https://github.com/dolphin-emu/dolphin
- Source: pinned Git submodule `dolphin/`, revision `e1d35f666cc35f6ead55d32ea2679df44d04da72`.
- Licensing: see `dolphin/COPYING` and `dolphin/LICENSES/`. Most original code is GPLv2+; the aggregate repository is GPLv3 compatible. Enabled dependencies require their own notice audit.
- Integration status: Android ARM64 static core and Vulkan build verified; no EmuCoreD Android bridge or playable platform yet. One adapter will serve two distinct platform identities and controller/storage policies.

## Build boundary

Use the upstream ARM64 Android core and our own JNI adapter, never the upstream Android app UI. Review `dolphin/CMakeLists.txt` and `dolphin/Source/Android/jni/` before defining targets. Disable Qt, desktop SDL frontend, updater, analytics, Discord presence, frame encoders and unused debugging integrations. Keep Vulkan and required audio/controller backends. Preserve functional shader caches.

Dolphin declares nested submodules in `dolphin/.gitmodules`, including Vulkan headers/VMA, glslang, SPIRV-Cross, cubeb, compression, networking and optional frontend/developer dependencies. Initialize only the selected Android build requirements, at their pinned revisions; Windows Qt and FFmpeg bundles are not Android dependencies. Do not confuse Dolphin's optional mGBA controller integration with EmuCoreD's standalone GBA adapter.

From the project root:

```powershell
git submodule update --init --depth 1 coreGameCube/dolphin
git -C coreGameCube/dolphin submodule status
```

Build the static Android API 26/ARM64 engine from the project root:

```powershell
.\coreGameCube\build_android_core.ps1 -InitializeDependencies
```

The script uses NDK 29.0.14206865 and CMake 3.30.5, initializes selected nested pins including libadrenotools/linkernsbypass, and builds the `core` target and its dependencies (including Vulkan). Qt, SDL, updater, analytics, LLVM disassembly and frame encoders are disabled. Upstream RetroAchievements and embedded mGBA controller support are disabled in this initial isolated build; their future product support is a separate decision. Cubeb/OpenSLES and ARM64 JIT remain enabled. Cubeb sanitizer instrumentation is disabled for this Release build.

The full 1,218-step native build and a repeat build through this script passed on Windows. This is compilation evidence, not device gameplay, successful final adapter linking or APK size evidence. Release logger/dumper removal, enabled dependency notices, source/runtime data packaging, host callbacks and our JNI/session adapter remain pending. No upstream Android application interface is packaged. Full work and acceptance criteria remain in the ignored local `docs/` plan.
