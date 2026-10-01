# EmuCoreD

[![Support EmuCoreD on Patreon](https://img.shields.io/badge/Patreon-Support%20EmuCoreD-ff424d?logo=patreon&logoColor=white)](https://www.patreon.com/c/emucore/membership)
[![Join the EmuCoreD Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.com/invite/c5EBeNRpz2)
[![Website](https://img.shields.io/badge/Website-emucored.web.app-1f6feb?logo=googlechrome&logoColor=white)](https://emucored.web.app/)

EmuCoreD is a Nintendo 3DS library, launcher, and emulator frontend for Android. It pairs a purpose-built Compose interface with a vendored [Azahar](https://github.com/azahar-emu/azahar) core that is built together with the app, so no separate core download is needed.

Official website: [https://emucored.web.app/](https://emucored.web.app/)

![Status](https://img.shields.io/badge/Status-Active%20Development-blue)

The project is under active development. Use your own legally obtained games.

## Highlights

- Azahar-based emulation core built together with the app for ARM64 devices
- Vulkan, OpenGL ES, and software rendering with internal resolution controls
- Game library with 3DS title and ID extraction, IGDB cover art, search, and per-game settings
- Home screen with shelves, recently played titles, and quick resume
- Screen layout controls for the dual-screen 3DS display
- In-game overlay with rendering, speed, and save state controls
- Touch controls with a layout editor, screen-swap action, and physical gamepad support
- Save states, SD card (sdmc) and NAND managers
- Still-image camera emulation for the outer and inner 3DS cameras
- Cheat and replacement texture catalogs for supported 3DS games
- RetroAchievements and optional Discord integration
- Localized interface in 18 languages for phones, tablets, and Android TV

## What This Repository Contains

This repository contains the Android application, its Kotlin UI, the JNI frontend, the vendored Azahar sources, and the Gradle module that builds the emulation core for Android. No games, save data, or account credentials are included.

## Tech Stack

- Kotlin + Jetpack Compose
- Android DataStore and Room
- JNI bridge to native C++ built with CMake and the Android NDK
- Vendored Azahar core built as a libretro library and driven by the app's own frontend
- Vulkan and OpenGL ES rendering paths with a shader chain runtime
- RetroAchievements integration through rcheevos
- Optional Discord Social SDK integration

## Current App Scope

EmuCoreD version `0.1.0` currently targets Android with:

- `minSdk 26` (Android 8.0)
- `targetSdk 37`
- package id `com.sbro.emucored`
- version `0.1.0`
- ARM64 devices only

## Building Locally

### Requirements

- Android Studio with Android SDK and NDK configured
- JDK 17
- Android SDK 37 and Android NDK `29.0.14206865`
- CMake `3.30.5`

### Debug Build

```powershell
.\gradlew :app:assembleDebug
```

### Release Build

```powershell
.\gradlew :app:assembleRelease
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an ARM64 Android device.

### Optional Discord SDK

Discord support is built when a compatible Discord Social SDK directory is supplied through `emucored.discord.sdkDir` in `local.properties`, a Gradle property with the same name, or `DISCORD_SDK_DIR`. The directory must contain `include/discordpp.h`, `arm64-v8a/libdiscord_partner_sdk.so`, and `discord_partner_sdk.aar`. The SDK is not included in this repository.

## Project Structure

- `app/` Android application, Kotlin UI, and JNI frontend sources
- `app/src/main/cpp` Native bridge and core integration
- `app/src/main/res` Android resources and translations
- `core/` Vendored Azahar sources
- `core-android/` Gradle module that builds the Azahar core for Android
- `IGDB/` Local catalog tooling (not part of the app build)

## Supported Content

CIA installers are extracted and decrypted on device, and the library reads the title, title ID, and icon from 3DS metadata. CCI, CXI, APP, 3DSX, ELF, and AXF content is supported depending on the bundled core. The bundled 3DS catalog supplies additional cover art. No games, save data, or account credentials are included here.

## Content Catalogs

- [3DS cheats](https://github.com/sashkinbro/EmuCoreD-Cheat)
- [3DS texture packs](https://github.com/sashkinbro/EmuCoreD-Textures)

The catalogs credit the original sources. Redistributable files are mirrored in their respective catalog releases; entries without redistribution permission link to their authors' downloads.

## Notes

- Game images, save data, and account credentials are not distributed with this project.
- Compatibility, performance, and graphics behavior vary by game, device, renderer, and driver stack.
- Releases marked as "parallel" are identical to the primary build but use an alternate package ID, so they can be installed side by side.

## Credits and license

EmuCoreD builds on Azahar, which is based on Citra. The root [LICENSE.TXT](LICENSE.TXT) is an exact copy of Azahar's upstream license file (GPLv2 or later). The vendored core and its dependencies retain their copyright and license notices in `core/`.

Thanks to the Azahar and Citra contributors, and to the RetroAchievements team for rcheevos.

EmuCoreD is independent of Nintendo, Azahar, Citra, IGDB, Discord, and RetroAchievements. Nintendo 3DS is a trademark of Nintendo. Game artwork and game data belong to their respective owners.

## Support

If you want to support ongoing development:

- Website: [https://emucored.web.app/](https://emucored.web.app/)
- Patreon: [https://www.patreon.com/c/emucore/membership](https://www.patreon.com/c/emucore/membership)
- Discord: [https://discord.com/invite/c5EBeNRpz2](https://discord.com/invite/c5EBeNRpz2)
- More apps by the author: [Google Play developer page](https://play.google.com/store/apps/dev?id=7136622298887775981)
