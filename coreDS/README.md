# coreDS — melonDS (Nintendo DS / DSi)

Vendored upstream **melonDS 1.1+** (`https://github.com/melonDS-emu/melonDS`) for
EmuCoreD. The core is integrated through EmuCoreD's own JNI bridge, not libretro.

## Layout

- `melonDS/` — upstream melonDS source.
- `bridge/` — EmuCoreD JNI/platform/frontend layer (to be added).
- `CMakeLists.txt` — configures melonDS as a static `core` target for Android.

## Build direction

- NDK, `arm64-v8a`.
- `ENABLE_JIT=ON` (melonDS ARM64 JIT).
- `ENABLE_OGLRENDERER=ON` with `MELONDS_GL_HEADER=<GLES3/gl32.h>`.
- Software renderer remains available as a fallback.
- melonDS has no Vulkan backend; Vulkan remains a 3DS-only renderer.
- BIOS/firmware can come from user files or FreeBIOS where supported.

## Integration scope

- `.nds`, `.dsi`, `.srl`, `.zip`, `.7z`.
- Own surface/EGL presenter, AAudio output, input, savestates and SRAM.
- Platform-aware library UI, local catalog, per-game settings and memory card manager.
- DS/DSi cover matching and EmuCoreD-Covers index (separate from 3DS).
