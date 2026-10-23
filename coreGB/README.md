# Game Boy and Game Boy Color: SameBoy

- Official upstream: https://github.com/LIJI32/SameBoy
- Source: pinned Git submodule `SameBoy/`, revision `26a56bd6a122a6318a456abd80558408742d5adf`.
- Licensing: `SameBoy/LICENSE`, Expat/MIT for the core. Upstream iOS and HexFiend directories are exceptions; do not assume the core license covers those frontends.
- Integration status: source acquired, no EmuCoreD Android bridge or playable GB/GBC platform yet.

## Build boundary

Build the portable C engine in `SameBoy/Core/` through our own NDK/CMake target and Android JNI adapter. Keep GB and GBC platform/model identities distinct. Inspect the upstream Makefile for source exclusions/feature definitions and generated data requirements. Disable debugger and developer dump tools in the release build; do not silently remove RTC, normal battery saves or user state support. Our Compose UI replaces upstream SDL/Cocoa/iOS/Windows interfaces.

```powershell
git submodule update --init --depth 1 coreGB/SameBoy
git -C coreGB/SameBoy rev-parse HEAD
```

There is no `.gitmodules` in this pinned SameBoy tree. Do not download or distribute proprietary boot ROMs. Record verified Android build commands and source patches here when implemented. Full work and acceptance criteria: `../docs/superpowers/plans/2026-10-23-multiplatform-product.md`.
