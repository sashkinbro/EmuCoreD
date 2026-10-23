# Game Boy Advance: mGBA

- Official upstream: https://github.com/mgba-emu/mgba
- Source: pinned Git submodule `mgba/`, revision `fd9dc5c2cbf234365068855851107e762fc6211b`.
- Licensing: `mgba/LICENSE` (MPL-2.0), plus enabled dependency notices. Covered source and modifications must remain available according to the license.
- Integration status: source acquired, no EmuCoreD Android bridge or playable GBA platform yet.

## Build boundary

Build the core library with `LIBMGBA_ONLY`, GBA enabled, and our own Android JNI/presentation/audio adapter. Review actual CMake cache behavior: upstream's library-only configuration disables frontends/dependency discovery and builds a static library. Do not package Qt, SDL, Python, libretro, scripting, debug servers or upstream app UI. Keep required save/RTC support. mGBA can emulate GB too, but our dedicated GB/GBC adapter will use SameBoy.

```powershell
git submodule update --init --depth 1 coreGBA/mgba
git -C coreGBA/mgba rev-parse HEAD
```

There is no `.gitmodules` in this pinned mGBA tree. Optional third-party code and data remain subject to their individual licenses. Android build/bridge commands will be recorded only after verification. Full work and acceptance criteria: `../docs/superpowers/plans/2026-10-23-multiplatform-product.md`.
