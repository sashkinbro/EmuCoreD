# coreDS — melonDS (Nintendo DS / DSi)

Vendored upstream **melonDS 1.1+** (`https://github.com/melonDS-emu/melonDS`) for
EmuCoreD. The core is integrated through EmuCoreD's own JNI bridge, not libretro.

## Layout

- `melonDS/` — upstream melonDS source.
- `bridge/` — EmuCoreD JNI/platform/frontend layer.
- `CMakeLists.txt` — configures melonDS as a static `core` target for Android.

## Build direction

- NDK, `arm64-v8a`.
- `ENABLE_JIT=ON` (melonDS ARM64 JIT).
- `ENABLE_OGLRENDERER=ON` with `MELONDS_GL_HEADER=<GLES3/gl32.h>`.
- Vulkan is the default DS renderer in this locally adapted tree; it is not a 3DS-only option.
- Software remains explicitly selectable for compatibility comparisons and native-resolution reference rendering. It is not an automatic substitute for fixing Vulkan defects.
- BIOS/firmware can come from user files or FreeBIOS where supported.

## Integration scope

- `.nds`, `.dsi`, `.srl`, `.zip`, `.7z`.
- Own surface/EGL presenter, AAudio output, input, savestates and SRAM.
- Platform-aware library UI, local catalog, per-game settings and memory card manager.
- DS/DSi cover matching and EmuCoreD-Covers index (separate from 3DS).
