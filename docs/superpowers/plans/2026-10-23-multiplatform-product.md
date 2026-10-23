# EmuCoreD Multiplatform Product Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, task by task. The user explicitly authorized execution, main-branch commits and pushes. Do not create another branch or require another planning approval.

**Goal:** Deliver a release-ready paid, open-source EmuCoreD supporting Nintendo DS, Nintendo 3DS, GameCube, Wii, Game Boy Advance, Game Boy and Game Boy Color, with our own Android UI and verified native integration.

**Architecture:** Keep core-specific native adapters separate from the Compose frontend. A platform/core registry owns identity, supported settings and session capabilities; all frontend surfaces consume it. Store upstream engines as pinned submodules, artwork as a separate local repository, and release only platforms whose native integration passes acceptance checks.

**Tech Stack:** Kotlin, Compose, Gradle, Android NDK/CMake, JNI, Azahar, melonDS, Dolphin, mGBA, SameBoy, JSON catalog assets, JUnit and Android instrumentation.

**Spec:** This plan records the user's approved scope from 2026-10-23 and extends `docs/PRODUCT_READINESS.md`. Our UI is retained. New platforms are GameCube/Wii through Dolphin, GBA through mGBA and GB/GBC through SameBoy. N64 and other consoles are outside this expansion.

## Global Constraints

- Work in existing `main`; make coherent English subject-only commits and push `origin main` regularly.
- Paid distribution with open source under compatible GPL terms is approved. Audit all linked dependencies and retain notices; do not relicense third-party files indiscriminately.
- Translate every new or changed user-visible string in all 18 existing locales.
- DS Vulkan remains the primary/default renderer, Software remains a reference; do not hide correctness defects with automatic fallback.
- Android arm64-v8a, minSdk 26, target/compile configuration follows the existing app. Validate actual device/API compatibility before promising it.
- Primary hardware: OnePlus CPH2747 (Adreno). Coordinate use of RG556 (Mali); never interfere with the user's other running app.
- Preserve user ROMs, custom covers, saves, layouts and preferences. Existing externally deleted `core-android/tests/*` files are not part of this task.
- Do not expose launchable platforms before their native adapter works. Source availability and product readiness are different states.
- Release diagnostics, automatic dumpers and profiling collectors must be compiled out; retain functional caches and explicit user exports.
- Prepare a signed release and store materials. Actual store publication requires final approval of the concrete release.

## Review Focus

1. Identically named games on different consoles must never share covers, SRAM, states or per-game overrides.
2. SAF paths with encoded separators, plus signs, revoked grants and non-seekable streams must not select the wrong core or corrupt files.
3. Switching cores after pause/save/surface recreation must stop every previous audio/input/render worker.
4. Unsupported, corrupt, ambiguous or missing game/BIOS files must produce a localized recoverable error, never a fallback to 3DS.
5. Legacy preferences/profile records and partial public profile data must retain their original meaning after registry migration.

## Source Layout and Interfaces

- Existing `core/` + `core-android/`: Azahar and its Android build.
- Existing `coreDS/` + `core-ds/`: melonDS and its Android build.
- New `coreGameCube/dolphin/`: pinned Dolphin upstream, shared by GameCube and Wii.
- New `coreGBA/mgba/`: pinned mGBA upstream.
- New `coreGB/SameBoy/`: pinned SameBoy upstream, shared by GB and GBC.
- Each new core root owns a README, later `bridge/`, `CMakeLists.txt` and native tests. Upstream changes require documented patches, never silent local edits.
- New Android modules `core-dolphin/`, `core-gba/`, `core-gb/` own JNI packaging and release flags once native bridges exist.
- `covers/`: restored `sashkinbro/EmuCoreD-Covers` repository, independently versioned; no bulk artwork in the application Git repository.
- New `core/platform/ConsolePlatform.kt`: stable IDs, core ownership, extensions and planned/available status for all seven consoles.
- New `core/platform/CoreCapabilities.kt`: supported renderers, settings categories, input/screen/save capabilities; no platform-wide inference from `not DS`.
- Existing `data/GamePlatform.kt`: migrate runtime game identity only after adapters can safely dispatch; existing serialized NDS/N3DS names remain valid.
- Existing `core/PlatformUiCapabilities.kt`: become an adapter over registry capabilities, then remove duplicated binary decisions.
- New `core/session/EmulationSession.kt`: `start(request)`, `pause()`, `resume()`, `stop()`, input/surface hooks, async state save/load with explicit results. Core adapters own thread and native lifetime.
- Existing `ui/settings/SettingsScreen.kt`, `ui/home/HomeScreen.kt`, `ui/settings/PerGameSettingsManagerScreen.kt`: consume shared registry/capabilities; selected settings platform never changes an active game's identity.
- Existing `data/covers/CoverIdentityResolver.kt`, `CoverIndexRepository.kt`, `CoverArtRepository.kt`: platform-qualified artwork and cache identities, preserved legacy DS/3DS behavior.

## Task 1: Acquire Reproducible Upstreams and Restore Artwork Repository

- [x] Add pinned submodules from official `dolphin-emu/dolphin`, `mgba-emu/mgba`, `LIJI32/SameBoy` in the paths above; keep upstream frontends out of our app.
- [x] Restore `covers/` from the exact URL used by `CoverIndexRepository`; inspect its index, tooling, licenses and 3D assets before changing it.
- [x] Record SHA, license, dependency/submodule inventory and update/build commands in each core README and `CORE_INTEGRATION.md`.
- [x] Verify `git submodule status`, `git ls-tree HEAD`, upstream remotes, no credentials/ROMs/artwork accidentally staged. Fresh-checkout instructions must reconstruct the sources.
- [x] Commit and push: `Add pinned Dolphin mGBA and SameBoy upstreams`.

## Task 2: Introduce Platform Registry Without Advertising Unfinished Engines

- [ ] Add JUnit tests in `app/src/test/java/com/sbro/emucored/core/platform/ConsolePlatformTest.kt`: exactly seven stable IDs, Dolphin shared by two platforms, SameBoy shared by two, only current DS/3DS marked launchable, unique extension ownership where unambiguous.
- [ ] Watch tests fail before implementing `ConsolePlatform` and `CoreId`; `.iso` cannot by itself distinguish Wii from GameCube. Unknown IDs return null.
- [ ] Add `CoreCapabilitiesTest.kt`: unsupported controls excluded, DS Software cannot internally upscale, Vulkan remains DS default, capability tables cover every platform.
- [ ] Implement registry/capabilities with explicit statuses; keep scanner behavior unchanged until native launch support exists.
- [ ] Run targeted tests and complete unit suite; commit and push: `Introduce explicit console and core capability registry`.

## Task 3: Unify Settings Selection and Library Filters for Existing Platforms

- [ ] Add policy tests for settings category visibility, selected-console persistence, hidden-category selection normalization and search scoped to selected console.
- [ ] Add top console selector to `SettingsScreen.kt` using existing premium tab styling; move current DS renderer/upscale into selected-console categories. Keep genuinely app-wide settings shared.
- [ ] Migrate `PlatformUiCapabilities.kt`; update settings search/help, game menu customization, controls editor and per-game manager to use explicit capabilities.
- [ ] Add platform filter state to `HomeViewModel.kt`, filter before count/grouping, preserve search/sort/favorites combinations; render All/DS/3DS with the same tab component/colors.
- [ ] Test empty libraries, selected platform removed by rescan, rotation, process recreation and gamepad focus. No unsupported planned console appears launchable.
- [ ] Translate strings in all 18 locales; run unit tests and Compose/instrumentation checks on OnePlus, inspect screenshots.
- [ ] Commit and push: `Unify console settings and library platform filters`.

## Task 4: Audit Licensing and Native Build Boundaries

- [ ] Inventory SPDX and all enabled transitive dependencies for Dolphin, mGBA, SameBoy and existing cores. Resolve existing frontend proprietary headers against the user's GPL distribution choice without touching third-party ownership.
- [ ] Audit linked proprietary/optional SDKs, including Discord and distribution dependencies, for compatibility; disable or replace incompatible components in the distributed product.
- [ ] Create `THIRD_PARTY_NOTICES.md`, precise license bundle/source offer and reproducible source/build instructions; audit artwork permissions separately from emulator code licenses.
- [ ] Configure each upstream core-only build, Android ARM64 optimizations, diagnostics flags and symbol visibility. Prevent CMake target/library collisions between engines.
- [ ] Build every core for arm64 and run native ABI smoke tests before hooking UI. Document exact supported Android floor and missing dependencies.
- [ ] Commit build and compliance changes in independently verified groups; push each group.

## Task 5: Common Session Contract and Resource Ownership

- [ ] Add lifecycle contract tests: failed start cleans up, stop idempotent, pause freezes guest execution, resume has no duplicate worker, stale callbacks cannot affect next session.
- [ ] Implement typed requests/results and core factory; adapt existing DS and 3DS first. Keep authoritative game identity and save namespace in the request.
- [ ] Implement attach/detach with rotation/background/surface replacement, AudioFocus, gamepad hotplug, touches, speed control and serialized save/load completion.
- [ ] Explicitly test revoked SAF grants, non-seekable inputs, partial reads, disk full, permission denied, interrupted save and concurrent exit/save.
- [ ] Run existing DS/3DS regression suite and real same-process transitions on OnePlus before adding another adapter.
- [ ] Commit and push in coherent lifecycle steps.

## Task 6: Dolphin GameCube and Wii Native Adapter

- [ ] Inspect pinned Dolphin embedded core APIs/Android platform code; implement in `coreGameCube/bridge/` and package via `core-dolphin/`. Do not embed Dolphin's app UI.
- [ ] Wire boot, host callbacks, filesystem/SAF, Vulkan/OpenGL surfaces, ARM64 CPU core, audio, controller ports and memory cards with isolated per-game state paths.
- [ ] Add boot/error, pause/resume, SRAM/card, state overwrite/load, renderer restart and surface teardown contract tests; run legal homebrew fixtures before retail-game checks.
- [ ] Implement Wii separately within shared adapter: remote/classic/Nunchuk profiles, pointer/motion, NAND/user data, aspect ratio and required system-data flow. Never claim Wii ready merely because GameCube boots.
- [ ] Verify real games, cold/warm pipelines, repeat launches, shader cache and sustained pacing on OnePlus; repeat representative coverage on Mali.
- [ ] Enable GameCube and Wii independently only after each platform's acceptance checks pass; commit and push verified increments.

## Task 7: mGBA Native Adapter

- [ ] Implement core-only build and bridge in `coreGBA/`, package via `core-gba/`, consume common session contract.
- [ ] Wire ROM reader, optional user BIOS, video pixel formats, audio resampling, buttons, RTC, SRAM/flash/EEPROM, states and supported peripherals.
- [ ] Add fixture tests for save types, RTC persistence, pixel/channel correctness, audio continuity, fast-forward, sensor inputs and state/version failures.
- [ ] Verify representative 2D/3D/cartridge-peripheral games on hardware; enable GBA only after lifecycle/state/device checks.
- [ ] Commit and push: `Integrate mGBA sessions and cartridge persistence` (or smaller verified parts).

## Task 8: SameBoy Native Adapter

- [ ] Implement bridge in `coreGB/`, package via `core-gb/`, retain core license notices and exclude upstream marketplace-specific frontend code.
- [ ] Wire model choice GB/GBC, boot ROM selection, LCD/frame output, audio, buttons, MBC/RTC persistence, palettes and state operations.
- [ ] Test GB/GBC distinction, monochrome/palette modes, cartridge RAM sizes, RTC, rumble where available and double-speed games; preserve model-specific save identity.
- [ ] Run homebrew conformance fixtures plus representative device games, lifecycle and transitions; enable GB/GBC independently after acceptance.
- [ ] Commit and push verified increments.

## Task 9: Formats, Catalog Identity, Import and Storage Across All Seven Platforms

- [ ] Add bounded header probes for ambiguous extensions and corruption; map IGDB platform IDs explicitly. Never route unknown game files into Azahar.
- [ ] Extend `GamePlatform.kt`, catalog models, `HomeViewModel.kt` scanner/cache and persisted profiles once adapters can dispatch safely; maintain old NDS/N3DS serialized names.
- [ ] Handle supported archives with traversal/size limits and deterministic entry selection; support cancellation and revoked SAF grants without losing prior library entries.
- [ ] Test same-title cross-platform games, region/revision IDs, imported duplicates, non-ASCII names, encoded paths and scan cancellation.
- [ ] Test platform-qualified storage for saves/states/cheats/cache/screenshots and profile unknown legacy records. Extend complete and partial profile aggregates to all platforms.
- [ ] Commit and push migrations only with downgrade/legacy behavior documented.

## Task 10: Artwork, Mapping and 3D Generation

- [ ] Inspect restored `covers/` schema and existing IGDB/export/render scripts. Reuse pipeline, not live per-tile full-catalog search.
- [ ] Add platform-qualified identity tables for GameCube, Wii, GBA, GB and GBC, including serial/header codes, region/revision and title aliases. Same title on two consoles must resolve separately.
- [ ] Preserve flat covers plus transparent 3D light/dark variants with correct physical box proportions; no UI card/corner clipping around existing 3D art.
- [ ] Generate incremental hashes/indexes, validate missing/corrupt art, URL paths and source provenance. Cover/license/source metadata remains auditable.
- [ ] Test cache single-flight, cold/warm hit/miss time, cancellation, platform/style changes, offline mode, migration and custom-cover priority.
- [ ] Verify library, game menu and manager on device with all aspect ratios. Commit tooling/schema/app changes independently; publish artwork repository changes with the authorized regular push workflow after validating rights and generated outputs.

## Task 11: Complete DS Controls and Polish Vulkan

- [ ] Add supported settings only when the JNI adapter implements them: audio interpolation/bit depth, software threading, applicable JIT, microphone, BIOS/firmware and separately validated DSi setup. Resolution control disabled/explained for Software internal rendering.
- [ ] Expand deterministic GPU fixtures: VRAM bank/remap, palette/texture updates, sprites, affine backgrounds, capture feedback, translucent polygons, depth/order, screen swap and repeated renderer changes.
- [ ] Compare Software vs Vulkan 1x for correctness and performance; measure Vulkan 2x/4x/8x separately. Capture frame-time median/p95/p99, guest speed, thermal behavior and memory on Adreno/Mali.
- [ ] Recheck NFS car/colors in race scenes and Contra movement/jumps/fire, plus other 2D/3D games; no stationary-only evidence.
- [ ] Optimize measured stalls/copies/pipeline creation and validate synchronization without per-frame blocking readbacks. Keep debug GPU diagnostics unavailable in release.
- [ ] Fix per-game reset renderer/scale ownership and autosave-exit preview race with regression tests. Preserve all current DS/3DS tests.
- [ ] Commit and push each reproduced, verified correction.

## Task 12: DS/3DS Regression and Remaining Upstream Work

- [ ] Finish documented Azahar/Azahar+ imports in `docs/azahar-sync.md` and `docs/azaharplus-sync.md` selectively with compatibility tests, not blind merges.
- [ ] Test 3DS OpenGL/Vulkan, occupied-slot save overwrite, previews, autosave exit, cheats where supported and screenshot orientation/colors.
- [ ] Run actual app surfaces through repeated DS/3DS and every newly enabled core transition. Ensure gamepad removes unused button space and screen swap remaps touch correctly.
- [ ] Recheck audio focus/backgrounding, controller disconnect/reconnect, storage errors, configuration reset and upgrade migration.
- [ ] Commit and push verified batches; update readiness evidence with exact build/device/test results.

## Task 13: Localization, Accessibility and Whole-Product UI

- [ ] Update settings, game manager/menu, library, profiles, onboarding, help/about, achievements, multiplayer visibility and backup/export to supported platform capabilities.
- [ ] Validate all 18 locales: key parity, placeholders/plurals, no untranslated new strings, RTL, long labels and console names. Keep genuinely core-specific 3DS descriptions accurate.
- [ ] Verify touch target sizes, default layouts, external gamepad navigation/focus, landscape/portrait and edge-to-edge/insets across single/dual-screen platforms.
- [ ] Compose tests assert unsupported settings absent and active-game console cannot be changed by global settings selection.
- [ ] Commit and push coherent UI/localization batches.

## Task 14: Minified Release and Ship Gate

- [ ] Add/verify JNI/R8 rules for every adapter and serialization/reflection path. Execute tests on minified release, not just debug.
- [ ] Scan final APK/native objects for enabled diagnostics, dump writers and accidental bundled ROMs/BIOS/keys. Confirm required caches and user exports still work.
- [ ] Run all host/unit/native/Compose/instrumentation suites. Device matrix covers every enabled platform, same-process transitions, cold/warm start, save/load, render backends and sustained performance.
- [ ] Check fresh install plus upgrade preserving real user data, release signature, ABI packaging, size, notices/source availability and reproducible source checkout.
- [ ] Prepare release notes, screenshots, platform compatibility/peripheral limitations, privacy/store metadata and artifact hashes.
- [ ] Resolve all release-blocking defects, commit/push final verified changes; present actual release artifact and evidence for final publication approval.
- [ ] Mark the product goal complete only when the agreed release criteria and authorized release steps are actually fulfilled.

## Verification Commands and Evidence

Use Android Studio JBR for Gradle. Unit baseline: `./gradlew.bat :app:testDebugUnitTest`.
Debug integration: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`.
Run instrumentation explicitly on the OnePlus transport; never use multi-device connected tasks while the user is on another device.
Source verification: `git submodule status --recursive`, upstream `git rev-parse HEAD`, `git diff --check` and scoped staged diff inspection.
Release: existing `logs/r8-device-smoke.init.gradle` may debug-sign a test release without changing product signing; production release uses configured credentials, never prints them.
Record command, result, commit, device/GPU/driver, ROM identity, backend/scale and important limitations in the progress ledger/readiness document. A source-only smoke check is never evidence of working game emulation.

## Progress Ledger

- 2026-10-23: User approved full plan execution on main and paid GPL/open-source distribution. Active product goal is extended by this plan; metadata objective predates this expansion and remains active.
- 2026-10-23: Planning/source inspection in progress. Four pre-existing externally deleted native test files left untouched.
- 2026-10-23: Task 1 complete, commit `88824d0d1` pushed to origin/main. All three official sources pinned; covers restored at `27478e28c54b34b6353b1b88751aa38b1c9fe10e`, 18,204 files. Artwork repo has no root LICENSE; do not assume code licenses cover artwork permissions. Android dependencies/adapters are still pending.
- 2026-10-23: Task 2 initial registry/capabilities implemented. Red: new test compilation failed on absent registry types. Green: full `:app:testDebugUnitTest` passed 159 tests, zero failures/errors/skips (`logs/platform-registry-green.log`). Existing DS/3DS UI capability wrapper now uses the explicit registry. No new core is advertised as launchable.
