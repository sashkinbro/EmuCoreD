// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.core.platform.session.CoreCheatCode
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.data.DisplayCrop
import com.sbro.emucored.ui.common.invalidateCoverImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.roundToInt

internal data class DsLaunchConfiguration(val renderer: Int, val renderScale: Int)

internal fun dsLaunchConfiguration(renderer: Int, renderScale: Int) =
    DsLaunchConfiguration(
        renderer = if (renderer == 13) 13 else 14,
        renderScale = renderScale.coerceIn(1, 8)
    )

/**
 * App-facing session bridge.
 *
 * EmuCoreD owns the whole path from the settings screen to the emulated 3DS:
 * settings are translated into the core's own config file, the session is
 * started through [CoreRuntime], and the presentation surface is attached
 * here. No libretro option table, no PCM pump.
 */
object EmulatorBridge {
    private const val TAG = "EmulatorBridge"
    const val AUTO_RENDERER = RendererDefaults.AUTO
    const val OPENGL_RENDERER = RendererDefaults.OPENGL
    const val VULKAN_RENDERER = RendererDefaults.VULKAN

    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serialScope = CoroutineScope(SupervisorJob() + serialDispatcher)

    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    @Volatile
    private var isVmActive: Boolean = false

    @Volatile
    private var lastSurface: Surface? = null

    @Volatile
    private var lastSurfaceWidth: Int = 0

    @Volatile
    private var lastSurfaceHeight: Int = 0

    @Volatile
    private var surfaceEventVersion: Long = 0

    private val _presentationSurfaceGeneration = MutableStateFlow(0L)
    val presentationSurfaceGeneration: StateFlow<Long> =
        _presentationSurfaceGeneration.asStateFlow()
    val runtimeFailure: StateFlow<RuntimeFailure?> get() = NativeApp.runtimeFailure

    @Volatile
    private var shutdownRequested: Boolean = false

    private val shutdownLock = Any()
    @Volatile private var shutdownJob: Job? = null

    private var contextRef: WeakReference<Context>? = null

    init {
        isNativeLoaded = NativeApp.hasNativeCore
        if (isNativeLoaded) {
            Log.i(TAG, "${NativeApp.loadedCoreLibraryName} is ready")
        } else {
            Log.e(TAG, "${NativeApp.loadedCoreLibraryName} is unavailable")
        }
    }

    fun isBundledAngleAvailable(): Boolean = false

    private fun rendererName(renderer: Int): String = when (renderer) {
        AUTO_RENDERER -> "OpenGL"
        OPENGL_RENDERER -> "OpenGL"
        VULKAN_RENDERER -> "Vulkan"
        else -> "Unknown($renderer)"
    }

    private fun appVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    internal fun normalizeRenderer(renderer: Int): Int =
        RendererDefaults.normalizeAndroidRenderer(renderer)

    fun getMaxUpscaleMultiplier(renderer: Int): Int = UPSCALE_MAX.toInt()

    fun initializeOnce(context: Context) {
        contextRef = WeakReference(context)
        if (!isNativeLoaded) {
            Log.e(TAG, "initializeOnce skipped: native library is not loaded")
            return
        }
        try {
            NativeApp.initializeOnce(context.applicationContext)
            Log.i(TAG, "initializeOnce completed")
        } catch (error: Exception) {
            Log.e(TAG, "initializeOnce failed", error)
            CrashLogger.logError(TAG, "initializeOnce FAILED", error)
        }
    }

    fun getContext(): Context? = contextRef?.get()

    /**
     * Writes the app's current choices into the core's config.ini and reloads
     * the core settings, so every later session starts with them applied.
     */
    suspend fun applyRuntimeConfig(
        emulatorDataPath: String? = null,
        gamePath: String? = null,
        renderer: Int,
        upscaleMultiplier: Float,
        gpuHardwareProfile: Int = GpuHardwareProfiles.ADRENO,
        mediatekAngleOpenGl: Boolean = false,
        aspectRatio: Int = 1,
        displayCrop: DisplayCrop = DisplayCrop.None,
        audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
        audioFastForwardVolume: Int = AudioDefaults.VOLUME_DEFAULT,
        audioMuted: Boolean = false,
        audioOutputLatencyMs: Int = AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT,
        audioMinimalOutputLatency: Boolean = AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
        frameSkip: Int = 0,
        frameLimitEnabled: Boolean = true,
        vSyncEnabled: Boolean = false,
        fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
        shaderChainEnabled: Boolean = false,
        shaderChainPreset: String = "",
        textureReplacementsEnabled: Boolean = true,
        enableCheats: Boolean = false,
        autotestMode: Boolean = false,
        layoutOption: Int = -1,
        swapScreens: Boolean? = null
    ) = withContext(serialDispatcher) {
        if (!isNativeLoaded) return@withContext
        val context = getContext() ?: return@withContext
        val resolvedRenderer = normalizeRenderer(renderer)

        CoreConfig.clearTransientOverrides()
        NativeApp.reloadDataRoot(emulatorDataPath ?: "")
        // Azahar stores its NAND, SD card and system data under its user
        // directory; point the core at the data root the user selected so the
        // emulator and the managers share one tree.
        NativeApp.setCoreUserDirectory(
            EmulatorStorage.azaharSaveRoot(context, emulatorDataPath).absolutePath
        )

        val prefs = AppPreferences(context)
        NativeApp.setCrashContextString("emu_renderer_name", rendererName(resolvedRenderer))
        NativeApp.setCrashContextString(
            "emu_gpu_profile",
            GpuHardwareProfiles.coreOverrideFor(GpuHardwareProfiles.normalize(gpuHardwareProfile))
        )
        NativeApp.setCrashContextBool("emu_mediatek_angle_opengl", mediatekAngleOpenGl)

        val compatibility = gamePath
            ?.let { path -> runCatching { getGameMetadata(path) }.getOrNull() }
            ?.let { metadata -> N3dsGameCompatibility.defaultsFor(metadata.serial, metadata.title) }
            ?: N3dsGameCompatibility.Defaults()

        CoreConfig.apply(
            CoreConfig.Values(
                renderer = resolvedRenderer,
                resolutionFactor = normalizeUpscale(upscaleMultiplier),
                aspectRatio = aspectRatio,
                displayCrop = displayCrop,
                audioVolume = audioVolume,
                audioFastForwardVolume = audioFastForwardVolume,
                audioMuted = audioMuted,
                audioOutputLatencyMs = audioOutputLatencyMs,
                audioMinimalOutputLatency = audioMinimalOutputLatency,
                frameSkip = frameSkip,
                frameLimitEnabled = frameLimitEnabled,
                vSyncEnabled = vSyncEnabled,
                fastForwardSpeed = fastForwardSpeed,
                shaderChainEnabled = shaderChainEnabled && shaderChainPreset.isNotBlank(),
                shaderChainPreset = shaderChainPreset.trim(),
                customTextures = textureReplacementsEnabled,
                cheatsEnabled = enableCheats,
                performanceOverlay = prefs.showFps.first(),
                mediatekAngleOpenGl = mediatekAngleOpenGl,
                layoutOption = layoutOption,
                swapScreens = swapScreens,
                simulate3dsGpuTimings = compatibility.simulateGpuTimings,
                delayGameRenderThreadUs = compatibility.delayGameRenderThreadUs
            )
        )
        NativeApp.setDisplayCrop(displayCrop.sanitized())
    }

    suspend fun startEmulation(
        path: String,
        saveStateIdentityPath: String? = null,
        dsRenderScale: Int = 1,
        dsRenderer: Int = 14,
        dsSwapScreens: Boolean = false,
        platformOverride: GamePlatform? = null
    ): Boolean {
        if (!isNativeLoaded) {
            Log.e(TAG, "startEmulation skipped: native library is not loaded")
            return false
        }
        if (path.isBlank()) {
            Log.e(TAG, "startEmulation rejected blank game path")
            return false
        }
        val context = getContext() ?: return false
        val identity = saveStateIdentityPath ?: path
        val probeContext = currentCoroutineContext()
        val platform = platformOverride ?: ConsoleGameDiscovery.identify(context, path,
            checkActive = { probeContext.ensureActive() }) ?: return false
        GamePlatform.remember(identity, platform)
        if (platform != GamePlatform.N3DS && platform != GamePlatform.NDS) {
            awaitPendingShutdown()
            if (isVmActive()) return false
            AdditionalConsoleSession.attachSurface(lastSurface, lastSurfaceWidth, lastSurfaceHeight)
            val root = ConsoleSessionStorage.root(context.filesDir)
            val userDirectory = if (platform.console.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN)
                File(root, "dolphin") else root
            return BackupSessionGate.start(active = { AdditionalConsoleSession.isActive }) {
                AdditionalConsoleSession.start(EngineLaunchRequest(
                    context,
                    platform.console,
                    path,
                    identity,
                    userDirectory,
                    CoreOptionPreferences(context).effectiveOptions(platform.console, identity),
                    sourceDisplayName = DocumentPathResolver.getDisplayName(context, path)
                ))
            }
        }
        if (platform == GamePlatform.NDS) {
            awaitPendingShutdown()
            if (isVmActive()) return false
            val context = getContext() ?: return false
            val dsConfig = dsLaunchConfiguration(dsRenderer, dsRenderScale)
            return withContext(Dispatchers.IO) {
                DsSession.start(
                    context,
                    path,
                    saveStateIdentityPath ?: path,
                    renderScale = dsConfig.renderScale,
                    renderer = dsConfig.renderer,
                    swapped = dsSwapScreens
                )
            }
        }
        awaitPendingShutdown()
        if (isVmActive()) {
            NativeApp.logCrashBreadcrumb("startEmulation blocked: previous session still owns the core")
            Log.e(TAG, "startEmulation blocked because the previous session still owns the core")
            return false
        }
        if (!awaitPresentationSurface()) {
            // The core blocks in System::Init without a window, so a session
            // started before the SurfaceView is ready would never boot.
            NativeApp.logCrashBreadcrumb("startEmulation aborted: no presentation surface")
            Log.e(TAG, "startEmulation aborted: no presentation surface")
            return false
        }
        // Save-state files are named after the path the user launched, not the
        // core's prepared/materialized path, so writes and listings agree.
        NativeApp.setSaveStateIdentityPath(saveStateIdentityPath ?: path)
        val pathType = if (path.startsWith("content://")) "content" else "file"
        NativeApp.logCrashBreadcrumb("startEmulation requested pathType=$pathType vmActive=$isVmActive")
        Log.i(TAG, "startEmulation requested pathType=$pathType vmActive=$isVmActive")

        return BackupSessionGate.start(active = { isVmActive }) {
            getContext()?.let { com.sbro.emucored.data.drive.DriveBackupArchive(it).recoverPending() }
            runSerial {
                isVmActive = true
                shutdownRequested = false
                var result = try {
                    NativeApp.logCrashBreadcrumb("startEmulation entering the core")
                    GpuDriverRuntime.configureAzahar(context, identity,
                        CoreConfig.currentValues()?.renderer ?: RendererDefaults.DEFAULT)
                    NativeApp.runVMThread(path)
                } catch (error: Exception) {
                    NativeApp.logCrashBreadcrumb("startEmulation failed before the core returned")
                    Log.e(TAG, "startEmulation native call failed", error)
                    false
                }
                if (result && !NativeApp.hasDiscMedia()) {
                    // The core silently boots the HOME menu when an image cannot
                    // be opened. Surface that as a failed launch instead of
                    // leaving the user on a misleading screen.
                    NativeApp.logCrashBreadcrumb("image failed to mount; aborting launch")
                    Log.w(TAG, "Image could not be mounted; aborting $pathType launch")
                    runCatching { NativeApp.shutdown() }
                    result = false
                }
                isVmActive = NativeApp.hasOwnedVm()
                NativeApp.logCrashBreadcrumb("startEmulation finished result=$result")
                Log.i(TAG, "startEmulation finished result=$result")
                result
            }
        }
    }

    /**
     * Waits until the emulation screen has produced a usable presentation
     * surface. The native core blocks during System::Init until a window is
     * available, so the session must not be started before then.
     */
    private suspend fun awaitPresentationSurface(
        timeoutMs: Long = 8_000L,
        pollMs: Long = 50L
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val surface = lastSurface
            if (surface != null && surface.isValid && lastSurfaceWidth > 0 && lastSurfaceHeight > 0) {
                return true
            }
            delay(pollMs)
        }
        return false
    }

    suspend fun pause() {
        if (AdditionalConsoleSession.isActive) { AdditionalConsoleSession.pause(); return }
        if (DsSession.isActive) {
            DsSession.pause()
            return
        }
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.pause()
            } catch (_: Exception) { }
        }
    }

    suspend fun resume() {
        if (AdditionalConsoleSession.isActive) { AdditionalConsoleSession.resume(); return }
        if (DsSession.isActive) {
            DsSession.resume()
            return
        }
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                rebindSurface()
                NativeApp.resume()
            } catch (_: Exception) { }
        }
    }

    /** Requests native stop immediately; renderer teardown continues asynchronously. */
    fun requestShutdown() {
        if (AdditionalConsoleSession.isActive) { AdditionalConsoleSession.requestStop(); return }
        if (DsSession.isActive) {
            DsSession.stop()
            return
        }
        if (!isNativeLoaded) return
        runCatching { NativeApp.requestShutdown() }
            .onFailure { Log.e(TAG, "Unable to request emulator shutdown", it) }
    }

    /** Queues one teardown operation and lets all callers await the same session release. */
    fun shutdownAsync(): Job {
        if (AdditionalConsoleSession.isActive) return synchronized(shutdownLock) {
            shutdownJob?.takeIf { it.isActive }?.let { return@synchronized it }
            serialScope.launch {
                resetKeyStatus()
                AdditionalConsoleSession.stop()
                BackupSessionGate.stopped()
            }.also { job ->
                shutdownJob = job
                job.invokeOnCompletion { synchronized(shutdownLock) { if (shutdownJob === job) shutdownJob = null } }
            }
        }
        if (DsSession.isActive) {
            return serialScope.launch {
                DsSession.stop()
                BackupSessionGate.stopped()
            }
        }
        return synchronized(shutdownLock) {
        shutdownJob?.takeIf { it.isActive }?.let { return@synchronized it }

        serialScope.launch {
            if (!isNativeLoaded) return@launch
            shutdownRequested = true
            try {
                NativeApp.shutdown()
            } finally {
                // A failed teardown must retain ownership and its descriptors.
                shutdownRequested = false
                val sessionReleased = !runCatching {
                    NativeApp.hasValidVm() || NativeApp.hasOwnedVm()
                }.getOrDefault(true)
                isVmActive = !sessionReleased
                if (sessionReleased) {
                    BackupSessionGate.stopped()
                    getContext()?.let { com.sbro.emucored.data.drive.DriveBackupWork.afterGame(it) }
                }
            }
        }.also { job ->
            shutdownJob = job
            job.invokeOnCompletion {
                synchronized(shutdownLock) {
                    if (shutdownJob === job) shutdownJob = null
                }
            }
        }
        }
    }

    suspend fun shutdown() {
        shutdownAsync().join()
    }

    private suspend fun awaitPendingShutdown() {
        val pending = synchronized(shutdownLock) { shutdownJob }
        pending?.join()
    }

    fun hasValidVm(): Boolean {
        if (AdditionalConsoleSession.isActive) return AdditionalConsoleSession.isRunning
        if (DsSession.isActive) return true
        if (!isNativeLoaded) return false
        return try {
            NativeApp.hasValidVm()
        } catch (_: Exception) {
            false
        }
    }

    fun isVmActive(): Boolean {
        // A status read never performs teardown. The core can still own its
        // window after a failed worker and needs an explicit shutdown.
        return AdditionalConsoleSession.isActive || DsSession.isActive || isVmActive || (isNativeLoaded && NativeApp.hasOwnedVm())
    }

    fun getGameTitle(path: String): String = getGameMetadata(path).title

    /**
     * [readDiscMetadata] opens the image to read its real title. Bulk library
     * scans pass false: opening every image would make the first scan
     * dramatically slower, and the filename/title-index serial is enough to
     * list the library.
     */
    fun getGameMetadata(path: String, readDiscMetadata: Boolean = true): GameMetadata {
        val inferredMetadata = when {
            ArchiveGameIdentity.parse(path) != null -> parseMetadataFromName(
                DocumentPathResolver.getFallbackDisplayName(path).substringBeforeLast('.')
            )
            path.startsWith("content://") -> {
                val context = getContext()
                val displayName = context?.let { DocumentPathResolver.getDisplayName(it, path) } ?: path
                parseMetadataFromName(displayName)
            }
            else -> {
                val localPath = if (path.startsWith("file://")) {
                    runCatching { android.net.Uri.parse(path).path }.getOrNull() ?: path
                } else {
                    path
                }
                parseMetadataFromName(File(localPath).nameWithoutExtension)
            }
        }
        if (!readDiscMetadata || !isNativeLoaded) return inferredMetadata
        if (isVmActive) return inferredMetadata
        getContext()?.let { context ->
            if (NdsGameFormats.isSupportedName(path)) {
                runCatching { NdsGameMetadataReader.read(context, path) }.getOrNull()?.let { metadata ->
                    return GameMetadata(
                        title = metadata.title?.let { normalizeNativeGameTitle(it, inferredMetadata.title) }
                            ?: inferredMetadata.title,
                        serial = metadata.serial ?: inferredMetadata.serial,
                        serialWithCrc = metadata.serial ?: inferredMetadata.serialWithCrc
                    )
                }
            }
            if (GamePlatform.forPath(path) == GamePlatform.N3DS) {
                runCatching { N3dsGameMetadataReader.read(context, path) }.getOrNull()?.let { metadata ->
                    return GameMetadata(
                        title = metadata.title?.let { normalizeNativeGameTitle(it, inferredMetadata.title) }
                            ?: inferredMetadata.title,
                        serial = metadata.serial ?: inferredMetadata.serial,
                        serialWithCrc = metadata.serial ?: inferredMetadata.serialWithCrc
                    )
                }
            }
        }
        return inferredMetadata
    }

    fun cleanGameDisplayTitle(rawTitle: String?, fallbackNameOrPath: String? = null): String {
        val fallbackDisplay = fallbackNameOrPath
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                if (value.startsWith("content://")) {
                    getContext()?.let { context -> DocumentPathResolver.getDisplayName(context, value) }
                        ?: DocumentPathResolver.normalizeDisplayName(value)
                } else {
                    DocumentPathResolver.normalizeDisplayName(value)
                }
            }
            .orEmpty()
        val trimmed = rawTitle?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallbackDisplay
        if (looksLikeStoragePath(trimmed)) return fallbackDisplay
        val withoutExtension = if (trimmed.contains('.')) {
            val ext = trimmed.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (GamePlatform.isSupportedName(trimmed)) trimmed.substringBeforeLast('.') else trimmed
        } else {
            trimmed
        }
        return withoutExtension.ifBlank { fallbackDisplay }
    }

    private fun normalizeNativeGameTitle(nativeTitle: String, fallbackTitle: String): String {
        val trimmed = nativeTitle.trim()
        return trimmed.ifBlank { fallbackTitle }
    }

    private fun looksLikeStoragePath(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.contains("%2F", ignoreCase = true) ||
            trimmed.contains("%3A", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("primary:", ignoreCase = true) ||
            trimmed.startsWith("home:", ignoreCase = true) ||
            trimmed.startsWith("raw:", ignoreCase = true) ||
            trimmed.contains("/storage/", ignoreCase = true)
    }

    fun parseMetadataFromName(rawName: String): GameMetadata {
        val ext = rawName.substringAfterLast('.', "").lowercase()
        val cleanName = if (ext in N3dsGameFormats.extensions) {
            rawName.substringBeforeLast('.').trim()
        } else {
            rawName.trim()
        }
        val serial = extractTitleIdFromName(cleanName)
        val title = cleanName
            // Dump tools append the 16 hex digit title ID and the CTR product
            // code; both are identifiers, not part of the game name.
            .replace(Regex("""(?i)\b[0-9A-F]{16}\b"""), " ")
            .replace(Regex("""(?i)\bCTR-[PN]-[A-Z0-9]{4}\b"""), " ")
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cart|rev)\s*[a-z0-9]+\b""", RegexOption.IGNORE_CASE), " ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { cleanName }
        return GameMetadata(title = title, serial = serial, serialWithCrc = serial)
    }

    private fun extractTitleIdFromName(value: String): String? {
        val normalized = value.uppercase(Locale.ROOT)
        Regex("""\b[0-9A-F]{16}\b""").find(normalized)?.let { return it.value }
        Regex("""\bCTR-[PN]-[A-Z0-9]{4}\b""").find(normalized)?.let { return it.value }
        return null
    }

    suspend fun saveState(slot: Int): Boolean {
        if (AdditionalConsoleSession.isActive) return AdditionalConsoleSession.saveState(slot)
        if (DsSession.isActive) {
            return withContext(Dispatchers.IO) { DsSession.saveState(slot) }
        }
        if (!isNativeLoaded || !isVmActive) return false
        val saved = runSerial {
            try {
                NativeApp.saveStateToSlot(slot)
            } catch (_: Exception) {
                false
            }
        }
        return saved
    }

    suspend fun replaceCheats(codes: List<CoreCheatCode>): Boolean {
        if (AdditionalConsoleSession.isActive) return AdditionalConsoleSession.session?.replaceCheats(codes) == true
        if (DsSession.isActive) return withContext(Dispatchers.IO) { DsSession.replaceCheats(codes) }
        return false
    }

    suspend fun loadState(slot: Int): Boolean {
        if (AdditionalConsoleSession.isActive) return AdditionalConsoleSession.loadState(slot)
        if (DsSession.isActive) {
            return withContext(Dispatchers.IO) { DsSession.loadState(slot) }
        }
        if (!isNativeLoaded || !isVmActive) return false
        return runSerial {
            try {
                val success = NativeApp.loadStateFromSlot(slot)
                if (success) {
                    runCatching { rebindSurface() }
                    runCatching { NativeApp.resume() }
                }
                success
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun setRenderer(gpuType: Int): Boolean {
        if (AdditionalConsoleSession.isActive) return false // Backend replacement requires an owned checkpoint restart.
        if (DsSession.isActive) {
            return withContext(Dispatchers.IO) { DsSession.setRenderer(gpuType) }
        }
        val resolvedRenderer = normalizeRenderer(gpuType)
        NativeApp.logCrashBreadcrumb(
            "renderer change requested renderer=${rendererName(resolvedRenderer)}($resolvedRenderer) vmActive=$isVmActive"
        )
        Log.i(TAG, "Renderer change requested: ${rendererName(resolvedRenderer)} vmActive=$isVmActive")
        val previousRendererOption = CoreOptionStore.value("graphics_api")
        CoreOptionStore.set("graphics_api", RendererDefaults.coreRendererName(resolvedRenderer))
        if (isVmActive) {
            // The core re-negotiates the renderer only on boot, so the in-game
            // switch is a state-preserving session restart.
            val restarted = runSerial { NativeApp.restartRenderer(resolvedRenderer) }
            isVmActive = NativeApp.hasOwnedVm()
            if (!restarted) {
                if (previousRendererOption == null) CoreOptionStore.remove("graphics_api")
                else CoreOptionStore.set("graphics_api", previousRendererOption)
                Log.e(TAG, "Renderer restart failed for ${rendererName(resolvedRenderer)}")
                return false
            }
        }
        return true
    }

    suspend fun setUpscaleMultiplier(multiplier: Float) {
        if (AdditionalConsoleSession.isActive) {
            AdditionalConsoleSession.session?.applyOption("resolution", multiplier.roundToInt().coerceIn(1, 4).toString())
            return
        }
        val normalized = normalizeUpscale(multiplier)
        if (DsSession.isActive) {
            DsSession.setRenderScale(Math.round(normalized).coerceIn(1, 8))
            return
        }
        CoreOptionStore.set("resolution_factor", Math.round(normalized).toString())
    }

    suspend fun setAspectRatio(type: Int) {
        CoreOptionStore.set("aspect_ratio", type.toString())
    }

    suspend fun setDisplayCrop(value: DisplayCrop) {
        val crop = value.sanitized()
        CoreOptionStore.set("display_crop_left", crop.left.toString())
        CoreOptionStore.set("display_crop_top", crop.top.toString())
        CoreOptionStore.set("display_crop_right", crop.right.toString())
        CoreOptionStore.set("display_crop_bottom", crop.bottom.toString())
        NativeApp.setDisplayCrop(crop)
    }

    suspend fun setFrameLimitEnabled(enabled: Boolean) {
        if (!isNativeLoaded) return
        runSerial { NativeApp.setFrameLimitEnabled(enabled) }
        CoreOptionStore.set("use_frame_limit", enabled.toString())
    }

    suspend fun setRewindEnabled(enabled: Boolean) {
        if (!isNativeLoaded) return
        runSerial { NativeApp.setRewindEnabled(enabled) }
        CoreOptionStore.set("rewind_enabled", enabled.toString())
    }

    suspend fun setVSyncEnabled(enabled: Boolean) {
        CoreOptionStore.set("use_vsync", enabled.toString())
    }

    suspend fun setFastForwardSpeed(value: Float) {
        CoreOptionStore.set("turbo_limit", value.toString())
    }

    suspend fun setFrameSkip(value: Int) {
        val normalized = value.coerceIn(0, 4)
        runSerial { NativeApp.setFrameSkip(normalized) }
        CoreOptionStore.set("frame_skip", normalized.toString())
    }

    fun setPadButton(padIndex: Int, index: Int, range: Int, pressed: Boolean) {
        if (DsSession.isActive) {
            DsSession.setPadButton(index, pressed)
            return
        }
        if (!isNativeLoaded) return
        try {
            NativeApp.setPadButton(padIndex, index, range, pressed)
        } catch (_: Exception) { }
    }

    fun setPadButtonFromSource(
        padIndex: Int,
        index: Int,
        range: Int,
        pressed: Boolean,
        source: Int
    ) {
        if (!isNativeLoaded) return
        try {
            NativeApp.setPadButtonFromSource(padIndex, index, range, pressed, source)
        } catch (_: Exception) { }
    }

    fun resetKeyStatus() {
        if (DsSession.isActive) {
            DsSession.resetKeys()
            return
        }
        if (!isNativeLoaded || !isVmActive) return
        launchSerial {
            if (!isVmActive || !runCatching { NativeApp.hasValidVm() }.getOrDefault(false)) return@launchSerial
            try {
                NativeApp.resetKeyStatus()
            } catch (_: Exception) { }
        }
    }

    fun resetPadState(padIndex: Int) {
        if (!isNativeLoaded || !isVmActive) return
        launchSerial {
            if (!isVmActive || !runCatching { NativeApp.hasValidVm() }.getOrDefault(false)) return@launchSerial
            try {
                NativeApp.resetPadState(padIndex)
            } catch (_: Exception) { }
        }
    }

    fun onSurfaceCreated(generation: Long) {
        if (AdditionalConsoleSession.isActive) return
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) return
        Log.i(TAG, "onSurfaceCreated: generation=$generation")
        NativeApp.setCrashContextString("emu_surface_state", "created")
        NativeApp.logCrashBreadcrumb("surfaceCreated")
        launchSerial {
            try {
                NativeApp.onNativeSurfaceCreated()
                Log.i(TAG, "onSurfaceCreated: native callback done")
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceCreated: native callback failed", e)
            }
        }
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, generation: Long) {
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) {
            Log.i(TAG, "Ignoring stale surfaceChanged generation=$generation")
            return
        }
        val eventVersion = ++surfaceEventVersion
        Log.i(TAG, "onSurfaceChanged: width=$width height=$height valid=${surface.isValid} generation=$generation eventVersion=$eventVersion")
        lastSurface = surface
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        AdditionalConsoleSession.attachSurface(surface, width, height)
        if (AdditionalConsoleSession.isActive) {
            NativeApp.rememberSurface(surface, width, height)
            return
        }
        // Publish the window synchronously so a session that is already
        // booting can hand it to the core before System::Init blocks.
        runCatching { NativeApp.rememberSurface(surface, width, height) }
        DsSession.setSurface(surface)
        if (DsSession.isActive) return
        NativeApp.setCrashContextString("emu_surface_state", "changed")
        NativeApp.setCrashContextInt("emu_surface_width", width)
        NativeApp.setCrashContextInt("emu_surface_height", height)
        NativeApp.setCrashContextBool("emu_surface_valid", surface.isValid)
        NativeApp.logCrashBreadcrumb("surfaceChanged width=$width height=$height valid=${surface.isValid}")
        launchSerial {
            if (surfaceEventVersion != eventVersion) {
                Log.w(TAG, "onSurfaceChanged: eventVersion mismatch ($eventVersion != $surfaceEventVersion), skipping")
                return@launchSerial
            }
            try {
                NativeApp.onNativeSurfaceChanged(surface, width, height)
                Log.i(TAG, "onSurfaceChanged: native callback done")
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceChanged: native callback failed", e)
            }
        }
    }

    /** Attaches (or replaces) the second screen surface for the bottom screen panel. */
    fun onSecondarySurfaceChanged(surface: Surface, width: Int, height: Int) {
        if (AdditionalConsoleSession.isActive) return
        if (!isNativeLoaded) return
        DsSession.setSecondarySurface(surface)
        if (DsSession.isActive) return
        Log.i(TAG, "onSecondarySurfaceChanged: width=$width height=$height valid=${surface.isValid}")
        NativeApp.setCrashContextInt("emu_secondary_surface_width", width)
        NativeApp.setCrashContextInt("emu_secondary_surface_height", height)
        NativeApp.logCrashBreadcrumb("secondarySurfaceChanged width=$width height=$height")
        launchSerial {
            try {
                NativeApp.onNativeSecondarySurfaceChanged(surface, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "onSecondarySurfaceChanged: native callback failed", e)
            }
        }
    }

    /** Detaches the second screen surface; the panel was closed. */
    fun onSecondarySurfaceDestroyed() {
        if (AdditionalConsoleSession.isActive) return
        if (!isNativeLoaded) return
        DsSession.setSecondarySurface(null)
        if (DsSession.isActive) return
        Log.i(TAG, "onSecondarySurfaceDestroyed")
        NativeApp.logCrashBreadcrumb("secondarySurfaceDestroyed")
        launchSerial {
            try {
                NativeApp.onNativeSecondarySurfaceDestroyed()
            } catch (e: Exception) {
                Log.e(TAG, "onSecondarySurfaceDestroyed: native callback failed", e)
            }
        }
    }

    /**
     * Switches the core layout live (for example to single-screen while the
     * bottom-screen panel is open). The app preference store keeps the user's
     * own layout choice; this only affects the running session.
     */
    fun setScreenLayout(option: Int) {
        if (AdditionalConsoleSession.isActive) return
        if (DsSession.isActive) return
        if (!isNativeLoaded) return
        NativeApp.logCrashBreadcrumb("screen layout requested option=$option")
        launchSerial {
            try {
                EmuCoreNative.notifyOrientationChange(
                    option,
                    currentDisplayRotation(),
                    isPortraitDisplay()
                )
                EmuCoreNative.updateFramebuffer(isPortraitDisplay())
            } catch (e: Exception) {
                Log.e(TAG, "setScreenLayout failed", e)
            }
        }
    }

    /** Changes how the DS top and bottom screens are presented. */
    fun setDsScreenLayout(mode: Int) {
        if (DsSession.isActive) {
            DsSession.setLayout(mode)
        }
    }

    /** Swaps which 3DS screen is counted as the prominent one. */
    fun setSwapScreens(swap: Boolean) {
        if (AdditionalConsoleSession.isActive) return
        if (DsSession.isActive) {
            DsSession.setSwapScreens(swap)
            return
        }
        if (!isNativeLoaded) return
        NativeApp.logCrashBreadcrumb("swap screens requested swap=$swap")
        launchSerial {
            CoreConfig.setTransientOverride("swap_screen", swap.toString())
            try {
                EmuCoreNative.swapScreens(swap, currentDisplayRotation())
            } catch (e: Exception) {
                Log.e(TAG, "setSwapScreens failed", e)
            }
        }
    }

    private fun currentDisplayRotation(): Int {
        val context = getContext() ?: return 0
        return runCatching {
            context.display?.rotation ?: android.view.Surface.ROTATION_0
        }.getOrDefault(0)
    }

    private fun isPortraitDisplay(): Boolean {
        val context = getContext() ?: return false
        return context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    fun onSurfaceDestroyed(generation: Long) {
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) {
            Log.i(TAG, "Ignoring stale surfaceDestroyed generation=$generation")
            return
        }
        val oldVersion = surfaceEventVersion
        ++surfaceEventVersion
        lastSurface = null
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        AdditionalConsoleSession.attachSurface(null, 0, 0)
        if (AdditionalConsoleSession.isActive) return
        Log.i(TAG, "onSurfaceDestroyed: called, version $oldVersion -> $surfaceEventVersion")
        DsSession.setSurface(null)
        if (DsSession.isActive) return
        NativeApp.setCrashContextString("emu_surface_state", "destroyed")
        NativeApp.logCrashBreadcrumb("surfaceDestroyed")
        runCatching { NativeApp.pause() }
        Log.i(TAG, "onSurfaceDestroyed: pause done, releasing the core surface")
        // Android invalidates the BufferQueue as soon as this callback returns.
        // Detach the window synchronously so it cannot keep presenting to an
        // abandoned Surface.
        try {
            NativeApp.onNativeSurfaceDestroyed()
            Log.i(TAG, "onSurfaceDestroyed: native destroy done")
        } catch (e: Exception) {
            Log.e(TAG, "onSurfaceDestroyed: native destroy failed", e)
        }
    }

    private fun rebindSurface() {
        val surface = lastSurface ?: return
        val width = lastSurfaceWidth
        val height = lastSurfaceHeight
        if (!surface.isValid || width <= 0 || height <= 0) {
            Log.w(TAG, "rebindSurface: skipped (valid=${surface.isValid} w=$width h=$height)")
            return
        }
        // The core session already binds this very surface before loading the
        // game. Rebinding it again on menu resume destroys and recreates the
        // swapchain after the first emulated frame.
        if (NativeApp.hasAttachedSurface(surface, width, height)) return
        Log.i(TAG, "rebindSurface: width=$width height=$height")
        NativeApp.logCrashBreadcrumb("rebindSurface width=$width height=$height")
        try {
            NativeApp.onNativeSurfaceChanged(surface, width, height)
        } catch (_: Exception) { }
    }

    suspend fun setSetting(section: String, key: String, type: String, value: String) {
        if (!isNativeLoaded) return
        CoreOptionStore.set(key, value)
    }

    suspend fun reloadPatches() {
        if (!isNativeLoaded) return
        runSerial { NativeApp.reloadPatches() }
    }

    fun getSetting(section: String, key: String, type: String): String? {
        if (!isNativeLoaded) return null
        return CoreOptionStore.value(key)
    }

    private suspend fun <T> runSerial(block: () -> T): T = withContext(serialDispatcher) { block() }

    private fun launchSerial(block: suspend () -> Unit) {
        serialScope.launch { block() }
    }
}
