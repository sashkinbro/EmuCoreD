# EmuCoreD

[![Support EmuCoreD on Patreon](https://img.shields.io/badge/Patreon-Support%20EmuCoreD-ff424d?logo=patreon&logoColor=white)](https://www.patreon.com/c/emucore/membership)
[![Join the EmuCoreD Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.com/invite/c5EBeNRpz2)
[![Website](https://img.shields.io/badge/Website-emucored.web.app-1f6feb?logo=googlechrome&logoColor=white)](https://emucored.web.app/)

EmuCoreD is a Nintendo library, launcher, and emulator frontend for Android. It supports ten console families — Nintendo 3DS, Nintendo DS, Nintendo 64, GameCube, Wii, Game Boy Advance, Game Boy, Game Boy Color, NES/Famicom, and SNES/Super Famicom — through bundled Azahar, melonDS, Mupen64Plus-Next, Dolphin, mGBA, SameBoy, Nestopia, and bsnes cores. The cores are built together with the app, so no separate core download is needed.

## Supported Consoles

- Nintendo 3DS — [Azahar](https://github.com/azahar-emu/azahar)
- Nintendo DS — [melonDS](https://github.com/melonDS-emu/melonDS)
- Nintendo 64 — [Mupen64Plus-Next](https://github.com/libretro/mupen64plus-libretro-nx)
- GameCube and Wii — [Dolphin](https://github.com/dolphin-emu/dolphin)
- Game Boy Advance — [mGBA](https://github.com/mgba-emu/mgba)
- Game Boy and Game Boy Color — [SameBoy](https://github.com/LIJI32/SameBoy)
- NES / Famicom — [Nestopia](https://gitlab.com/jgemu/nestopia)
- SNES / Super Famicom — [bsnes](https://github.com/bsnes-emu/bsnes)

Official website: [https://emucored.web.app/](https://emucored.web.app/)

![Status](https://img.shields.io/badge/Status-Active%20Development-blue)

The project is under active development. Use your own legally obtained games.

## Highlights

- Ten supported console families with bundled emulation cores for ARM64 devices:
  - Nintendo 3DS via Azahar
  - Nintendo DS via melonDS
  - Nintendo 64 via Mupen64Plus-Next
  - GameCube and Wii via Dolphin
  - Game Boy Advance via mGBA
  - Game Boy and Game Boy Color via SameBoy
  - NES/Famicom via Nestopia
  - SNES/Super Famicom via bsnes
- Vulkan, OpenGL ES, and software rendering where supported, with internal resolution controls
- Unified game library with platform filters, metadata and title-ID extraction, IGDB cover art, search, and per-game settings
- GameCube and Wii disc support, including ISO, RVZ, WIA, GCZ, CISO, WBFS, and related Dolphin formats
- ZIP archive scanning and launch for supported GBA, GB, GBC, NES, and SNES games; individual entries keep separate library and save identities
- A custom Vulkan driver catalog and file installation for compatible Adreno devices when using supported 3DS, DS, GameCube, and Wii Vulkan renderers
- Home screen with shelves, recently played titles, and quick resume
- Screen layout controls for the dual-screen 3DS and DS displays
- In-game overlay with rendering, speed, and save-state controls
- Touch controls with a per-console layout editor and physical gamepad support
- Save states, plus 3DS SD card (sdmc) and NAND managers
- Still-image camera emulation for the outer and inner 3DS cameras
- Cheat catalogs for 3DS and retro consoles, and replacement texture catalogs for 3DS, GameCube, and Wii
- RetroAchievements and optional Discord integration
- Localized interface in 18 languages for phones, tablets, and Android TV

## What This Repository Contains

This repository contains the Android application, its Kotlin UI, the JNI frontend, the vendored emulator sources, and the Gradle modules that build all supported cores for Android. No games, save data, or account credentials are included.

## Tech Stack

- Kotlin + Jetpack Compose
- Android DataStore and Room
- JNI bridge to native C++ built with CMake and the Android NDK
- Bundled Azahar (3DS), melonDS (DS), Mupen64Plus-Next (N64), Dolphin (GameCube/Wii), mGBA (GBA), SameBoy (GB/GBC), Nestopia (NES), and bsnes (SNES) cores driven by the app's own frontend
- Vulkan and OpenGL ES rendering paths with a shader chain runtime
- RetroAchievements integration through rcheevos
- Optional Discord Social SDK integration

## Current App Scope

EmuCoreD version `0.0.2` currently targets Android with:

- `minSdk 26` (Android 8.0)
- `targetSdk 37`
- package id `com.sbro.emucored`
- `versionCode 25`
- ARM64 devices only

## Building Locally

### Requirements

- Android Studio with Android SDK and NDK configured
- JDK 17
- Android SDK 37 and Android NDK `29.0.14206865`
- CMake `3.30.5`
- Initialized Git submodules: `git submodule update --init --recursive`
- A Firebase configuration for this application at `app/google-services.json`

The optional RetroArch shader-chain runtime requires Rust and Cargo; without them, the built-in shader presenter remains available.

### Debug Build

```powershell
.\gradlew :app:assembleDebug
```

### Release Build

```powershell
.\gradlew :app:assembleRelease
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an ARM64 Android device. The release APK is written to `app/build/outputs/apk/release/`. To produce a signed release, configure `emucored.release.storeFile`, `emucored.release.storePassword`, `emucored.release.keyAlias`, and `emucored.release.keyPassword` in `local.properties`. Keep signing credentials private.

### Optional Discord SDK

Discord support is built when a compatible Discord Social SDK directory is supplied through `emucored.discord.sdkDir` in `local.properties`, a Gradle property with the same name, or `DISCORD_SDK_DIR`. The directory must contain `include/discordpp.h`, `arm64-v8a/libdiscord_partner_sdk.so`, and `discord_partner_sdk.aar`. The SDK is not included in this repository.

## Project Structure

- `app/` Android application, Kotlin UI, and JNI frontend sources
- `app/src/main/cpp` Native bridge and core integration
- `app/src/main/res` Android resources and translations
- `core/` Vendored Azahar 3DS sources
- `core-android/` Gradle module that builds the Azahar 3DS core for Android
- `coreDS/` Nintendo DS bridge, renderers, and vendored melonDS sources
- `core-ds/` Gradle module that builds the Nintendo DS core for Android
- `coreN64/` Nintendo 64 bridge and pinned Mupen64Plus-Next source
- `core-n64/` Gradle module that builds the Nintendo 64 core for Android
- `coreGameCube/dolphin/` Pinned Dolphin source for GameCube and Wii
- `core-dolphin/` Gradle module that builds the Dolphin core for Android
- `coreGBA/mgba/` Pinned mGBA source for Game Boy Advance
- `core-gba/` Gradle module that builds the mGBA core for Android
- `coreGB/SameBoy/` Pinned SameBoy source for Game Boy and Game Boy Color
- `core-gb/` Gradle module that builds the SameBoy core for Android
- `coreNES/nestopia/` Pinned Nestopia source for NES/Famicom
- `core-nes/` Gradle module that builds the Nestopia core for Android
- `coreSNES/bsnes/` Pinned bsnes source for SNES/Super Famicom
- `core-snes/` Gradle module that builds the bsnes core for Android
- `core-common/` Shared native and Kotlin core contracts
- `core-tests/` Standalone Android engine checks
- `IGDB/` Local catalog tooling (not part of the app build)

All ten console families are integrated into the app and launchable. Source pins and per-core build notes live with each vendored core.

## Supported Content

- **Nintendo 3DS:** The library discovers 3DS, CCI, CXI, APP, CIA, Z3DS, ZCCI, ZCXI, ZCIA, 3DSX, Z3DSX, ELF, and AXF files. Launch compatibility depends on the format and encryption; use decrypted game images, and note that compressed ZCCI and ZCXI are not accepted by the core. CIA packages are installed to the emulated system; encrypted packages require appropriate system keys or decrypted content.
- **Nintendo DS:** `.nds`, `.srl`, and `.dsi` images through the melonDS-based core, including DS save states, on-screen controls, screen swapping, and DS cover art. DSi catalog entries are listed for discovery; DSi-mode execution is not implemented yet and requires DSi BIOS/NAND files.
- **Nintendo 64:** `.n64`, `.z64`, and `.v64` cartridge images.
- **GameCube:** GCM, ISO, RVZ, WIA, GCZ, CISO, and TGC images, plus DOL and ELF homebrew.
- **Wii:** ISO, RVZ, WIA, GCZ, CISO, WBFS, and WAD images, plus DOL and ELF homebrew. Split ISO and WBFS sets are supported.
- **Game Boy Advance:** `.gba` and `.agb`.
- **Game Boy:** `.gb` and `.sgb`.
- **Game Boy Color:** `.gbc` and `.cgb`.
- **NES/Famicom:** `.nes`, `.unf`, `.unif`, and `.fds`.
- **SNES/Super Famicom:** `.sfc`, `.smc`, `.swc`, and `.fig`.

ZIP archives are supported for GBA, GB, GBC, NES, and SNES game entries; support for ZIP archives does not extend to every console or archive format. Compatibility and performance vary by game, device, renderer, and driver stack. No games, save data, or account credentials are included here.

## Content Catalogs

- [Cheat catalogs](https://github.com/sashkinbro/EmuCoreD-Cheat) for Nintendo 3DS and the supported retro consoles
- [Texture packs](https://github.com/sashkinbro/EmuCoreD-Textures) for Nintendo 3DS, GameCube, and Wii

The catalogs credit the original sources. Redistributable files are mirrored in their respective catalog releases; entries without redistribution permission link to their authors' downloads.

## Notes

- Game images, save data, and account credentials are not distributed with this project.
- Compatibility, performance, and graphics behavior vary by game, device, renderer, and driver stack.
- Releases marked as "parallel" are identical to the primary build but use an alternate package ID, so they can be installed side by side.

## Credits and license

EmuCoreD builds on Azahar, which is based on Citra, and on melonDS for Nintendo DS emulation. Nintendo 64 emulation uses Mupen64Plus-Next; GameCube and Wii emulation use Dolphin; Game Boy Advance uses mGBA; Game Boy and Game Boy Color use SameBoy; NES/Famicom uses Nestopia; SNES/Super Famicom uses bsnes. The root [LICENSE.TXT](LICENSE.TXT) is an exact copy of Azahar's upstream license file (GPLv2 or later). The vendored cores and their dependencies retain their copyright and license notices in their respective source directories.

Thanks to the Azahar, Citra, melonDS, Mupen64Plus-Next, Dolphin, mGBA, SameBoy, Nestopia, and bsnes contributors, and to the RetroAchievements team for rcheevos.

EmuCoreD is independent of Nintendo, Azahar, Citra, melonDS, Mupen64Plus-Next, Dolphin, mGBA, SameBoy, Nestopia, bsnes, IGDB, Discord, and RetroAchievements. Nintendo 3DS, Nintendo DS, Nintendo 64, GameCube, Wii, Game Boy Advance, Game Boy, Game Boy Color, NES, and SNES are trademarks of Nintendo. Game artwork and game data belong to their respective owners.

## Support

If you want to support ongoing development:

- Website: [https://emucored.web.app/](https://emucored.web.app/)
- Patreon: [https://www.patreon.com/c/emucore/membership](https://www.patreon.com/c/emucore/membership)
- Discord: [https://discord.com/invite/c5EBeNRpz2](https://discord.com/invite/c5EBeNRpz2)
- More apps by the author: [Google Play developer page](https://play.google.com/store/apps/dev?id=7136622298887775981)
