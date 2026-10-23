# GameCube and Wii: Dolphin

- Official upstream: https://github.com/dolphin-emu/dolphin
- Source: pinned Git submodule `dolphin/`, revision `e1d35f666cc35f6ead55d32ea2679df44d04da72`.
- Licensing: see `dolphin/COPYING` and `dolphin/LICENSES/`. Most original code is GPLv2+; the aggregate repository is GPLv3 compatible. Enabled dependencies require their own notice audit.
- Integration status: source acquired, no EmuCoreD Android bridge or playable platform yet. One adapter will serve two distinct platform identities and controller/storage policies.

## Build boundary

Use the upstream ARM64 Android core and our own JNI adapter, never the upstream Android app UI. Review `dolphin/CMakeLists.txt` and `dolphin/Source/Android/jni/` before defining targets. Disable Qt, desktop SDL frontend, updater, analytics, Discord presence, frame encoders and unused debugging integrations. Keep Vulkan and required audio/controller backends. Preserve functional shader caches.

Dolphin declares nested submodules in `dolphin/.gitmodules`, including Vulkan headers/VMA, glslang, SPIRV-Cross, cubeb, compression, networking and optional frontend/developer dependencies. Initialize only the selected Android build requirements, at their pinned revisions; Windows Qt and FFmpeg bundles are not Android dependencies. Do not confuse Dolphin's optional mGBA controller integration with EmuCoreD's standalone GBA adapter.

From the project root:

```powershell
git submodule update --init --depth 1 coreGameCube/dolphin
git -C coreGameCube/dolphin submodule status
```

No app build command is advertised until the EmuCoreD bridge is implemented and tested. Record future build commands and patches here. Full work and acceptance criteria: `../docs/superpowers/plans/2026-10-23-multiplatform-product.md`.
