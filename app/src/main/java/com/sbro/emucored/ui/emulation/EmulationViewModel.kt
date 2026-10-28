
package com.sbro.emucored.ui.emulation

import com.sbro.emucored.core.CoreRuntime
import kotlinx.coroutines.currentCoroutineContext
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucored.EmuCoreDApp
import com.sbro.emucored.discord.DiscordIntegration
import com.sbro.emucored.core.AndroidGamePerformance
import com.sbro.emucored.core.AndroidGamePhase
import com.sbro.emucored.core.AudioDefaults
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.core.RendererDefaults
import com.sbro.emucored.core.SetupValidator
import com.sbro.emucored.core.EmulatorStorage
import com.sbro.emucored.core.GamepadManager
import com.sbro.emucored.core.GpuHardwareProfiles
import com.sbro.emucored.core.KeyboardMouseManager
import com.sbro.emucored.core.MobileSocNameMapper
import com.sbro.emucored.core.NativeApp
import com.sbro.emucored.core.RuntimeFailure
import com.sbro.emucored.core.AzaharCoreOptions
import com.sbro.emucored.core.CoreOptionStore
import com.sbro.emucored.core.PerformanceProfiles
import com.sbro.emucored.core.resolveAndroidGamePhase
import com.sbro.emucored.core.normalizeUpscale
import com.sbro.emucored.core.normalizeUpscale
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.core.PlatformUiCapabilities
import com.sbro.emucored.core.ConsoleGameDiscovery
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.DsSettingsPolicy
import com.sbro.emucored.data.forPlatform
import com.sbro.emucored.data.SettingsSnapshot
import com.sbro.emucored.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucored.data.AppPreferences.Companion.FPS_OVERLAY_MODE_DETAILED
import com.sbro.emucored.data.CheatBlock
import com.sbro.emucored.data.DisplayCrop
import com.sbro.emucored.data.KeyboardMouseSettings
import com.sbro.emucored.data.OverlayControlLayout
import com.sbro.emucored.data.CheatRepository
import com.sbro.emucored.data.GameRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.OverlayLayoutSnapshot
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.withScreenSwap
import com.sbro.emucored.data.resolveScreenSwap
import com.sbro.emucored.data.PerGameSettingsRepository
import com.sbro.emucored.data.PlayTimeSyncCacheRepository
import com.sbro.emucored.data.PlayerPlayTimeDelta
import com.sbro.emucored.data.PlayerProfileRepository
import com.sbro.emucored.data.RetroAchievementsRepository
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.data.resolveShaderChain
import com.sbro.emucored.data.TouchControlsLayoutProfile
import com.sbro.emucored.data.PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY
import com.sbro.emucored.data.PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
import com.sbro.emucored.data.saveTouchControlsLayout
import com.sbro.emucored.data.ConsoleControlsScope
import com.sbro.emucored.data.ConsoleControlsProfile
import com.sbro.emucored.data.forConsoleControls
import com.sbro.emucored.data.toggleStick
import com.sbro.emucored.data.withCustomTouchControls
import com.sbro.emucored.data.withKeyboardMouseSettings
import com.sbro.emucored.data.withTouchControlsLayout
import com.sbro.emucored.data.withoutTouchControlsLayout
import com.sbro.emucored.data.TouchControlVisualStyle
import com.sbro.emucored.data.TouchControlPressEffect
import com.sbro.emucored.data.CustomTouchControlLibrary
import com.sbro.emucored.data.GameMenuLayoutStyle
import com.sbro.emucored.data.GameMenuTabId
import com.sbro.emucored.data.GameMenuSectionId
import com.sbro.emucored.data.DefaultGameMenuTabOrder
import com.sbro.emucored.data.DefaultGameMenuSectionOrder
import com.sbro.emucored.data.PerformanceOverlayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val PER_GAME_GPU_DRIVER_KEYS = setOf(
    "gpuDriverType",
    "customDriverPath",
    "mediatekAngleOpenGl"
)

private val PER_GAME_AUDIO_KEYS = setOf(
    "audioVolume",
    "audioMuted",
    "audioOutputLatencyMs",
    "audioMinimalOutputLatency"
)

private fun performanceAppVersion(application: Application): String = runCatching {
    application.packageManager.getPackageInfo(application.packageName, 0).versionName
}.getOrNull()?.takeIf(String::isNotBlank) ?: "?"

enum class EmulationTransportMode { None, FastForward, Rewind }

data class EmulationUiState(
    val gamePlatform: GamePlatform = GamePlatform.N3DS,
    val runtimeFailure: RuntimeFailure? = null,
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isPaused: Boolean = false,
    val showMenu: Boolean = false,
    val transportMode: EmulationTransportMode = EmulationTransportMode.None,
    val isActionInProgress: Boolean = false,
    val actionLabel: String? = null,
    val controlsVisible: Boolean = true,
    val showFps: Boolean = true,
    val confirmSaveLoadActions: Boolean = true,
    val backButtonExitsGame: Boolean = false,
    val compactControls: Boolean = true,
    val keepScreenOn: Boolean = true,
    val fpsOverlayCorner: Int = AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT,
    val fpsOverlayScale: Int = AppPreferences.DEFAULT_FPS_OVERLAY_SCALE,
    val fpsOverlayMetrics: Int = PerformanceOverlayMetrics.DEFAULT,
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
    val touchControlVisualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    val touchControlPressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    val customTouchControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
    val gameMenuLayoutStyle: GameMenuLayoutStyle = GameMenuLayoutStyle.SIDEBAR,
    val gameMenuTabOrder: List<GameMenuTabId> = DefaultGameMenuTabOrder,
    val hiddenGameMenuTabs: Set<GameMenuTabId> = emptySet(),
    val gameMenuSectionOrder: List<GameMenuSectionId> = DefaultGameMenuSectionOrder,
    val hiddenGameMenuSections: Set<GameMenuSectionId> = emptySet(),
    val hideOverlayOnGamepad: Boolean = true,
    val dpadOffset: Pair<Float, Float> = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
    val lstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
    val rstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
    val actionOffset: Pair<Float, Float> = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
    val lbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
    val rbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
    val centerOffset: Pair<Float, Float> = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
    val stickScale: Int = 100,
    val stickToggleTarget: Int = AppPreferences.DEFAULT_STICK_TOGGLE_TARGET,
    val leftStickSensitivity: Int = 100,
    val rightStickSensitivity: Int = 100,
    val invertLeftStick: Boolean = false,
    val invertRightStick: Boolean = false,
    val invertLeftStickHorizontal: Boolean = false,
    val invertRightStickHorizontal: Boolean = false,
    val racingMode: Boolean = false,
    val stickyButtons: Set<String> = emptySet(),
    val touchscreenLeftStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_LEFT_STICK,
    val touchscreenLeftStickSensitivity: Int = AppPreferences.DEFAULT_TOUCHSCREEN_LEFT_STICK_SENSITIVITY,
    val touchscreenRightStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK,
    val touchscreenRightStickSensitivity: Int = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY,
    val touchHaptics: Boolean = false,
    val touchHapticsPreset: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET,
    val touchHapticsStrength: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_STRENGTH,
    val gyroMode: Int = AppPreferences.GYRO_MODE_OFF,
    val gyroSensitivity: Int = AppPreferences.DEFAULT_GYRO_SENSITIVITY,
    val gyroSmoothing: Int = AppPreferences.DEFAULT_GYRO_SMOOTHING,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val stickSurfaceMode: Boolean = false,
    val controlLayouts: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
    val fpsOverlayMode: Int = FPS_OVERLAY_MODE_DETAILED,
    val performanceOverlayHeader: String = "",
    val toastMessage: String? = null,
    val statusMessage: String? = null,
    val currentSlot: Int = 1,
    val renderer: Int = RendererDefaults.defaultForHardware(),
    val upscale: Float = 1f,
    val aspectRatio: Int = 1,
    val swapScreens: Boolean = false,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val enableCheats: Boolean = false,
    val frameSkip: Int = 0,
    val cheatsGameKey: String? = null,
    val availableCheats: List<CheatBlock> = emptyList(),
    val frameLimitEnabled: Boolean = true,
    val fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
    val targetFps: Int = 0,
    val currentGameTitle: String = "",
    val currentGameSubtitle: String = "",
    val currentGameCoverPath: String? = null,
    val gameSettingsProfileActive: Boolean = false,
    val perGameCoreOptions: Map<String, String> = emptyMap(),
    val currentSlotLastModified: Long = 0L,
    val currentSlotPreviewPath: String? = null,
    val autoSaveEnabled: Boolean = false,
    val autoSaveIntervalMinutes: Int = 1,
    val autoSaveOnExit: Boolean = false,
    val autoLoadOnStart: Boolean = false,
    val autoSaveLastModified: Long = 0L,
    val autoSavePreviewPath: String? = null,
    val isAutoSaveInProgress: Boolean = false,
    val activePlayTimeMs: Long = 0L,
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
)

private data class EmulationLaunchConfig(
    val emulatorDataPath: String?,
    val renderer: Int,
    val upscaleMultiplier: Float,
    val gpuHardwareProfile: Int,
    val mediatekAngleOpenGl: Boolean,
    val aspectRatio: Int,
    val displayCrop: DisplayCrop,
    val audioVolume: Int,
    val audioFastForwardVolume: Int,
    val audioMuted: Boolean,
    val audioOutputLatencyMs: Int,
    val audioMinimalOutputLatency: Boolean,
    val frameSkip: Int,
    val frameLimitEnabled: Boolean,
    val vSyncEnabled: Boolean,
    val fastForwardSpeed: Float,
    val targetFps: Int,
    val shaderChainEnabled: Boolean,
    val shaderChainPreset: String,
    val textureReplacementsEnabled: Boolean,
    val enableCheats: Boolean,
    val layoutOption: Int = -1,
    val swapScreens: Boolean? = null
)

private data class LiveRuntimeSnapshot(
    val showFps: Boolean,
    val fpsOverlayMode: Int,
    val confirmSaveLoadActions: Boolean,
    val backButtonExitsGame: Boolean,
    val renderer: Int,
    val upscale: Float,
    val aspectRatio: Int,
    val displayCrop: DisplayCrop,
    val frameSkip: Int,
    val frameLimitEnabled: Boolean,
    val fastForwardSpeed: Float,
    val targetFps: Int,
    val enableCheats: Boolean,
    val audioVolume: Int,
    val audioMuted: Boolean,
    val racingMode: Boolean,
    val stickyButtons: Set<String>,
    val touchscreenLeftStick: Boolean,
    val touchscreenLeftStickSensitivity: Int,
    val touchscreenRightStick: Boolean,
    val touchscreenRightStickSensitivity: Int,
    val touchHaptics: Boolean,
    val touchHapticsPreset: Int,
    val touchHapticsStrength: Int,
    val touchControlVisualStyle: TouchControlVisualStyle,
    val touchControlPressEffect: TouchControlPressEffect,
    val gyroMode: Int,
    val gyroSensitivity: Int,
    val gyroSmoothing: Int,
    val gyroInvertX: Boolean,
    val gyroInvertY: Boolean,
    val gamepadRightStickUpToR2: Boolean,
    val gamepadRightStickDownToL2: Boolean,
    val gamepadButtonHaptics: Boolean,
    val gamepadStickDeadzone: Int,
    val gamepadLeftStickSensitivity: Int,
    val gamepadRightStickSensitivity: Int,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>>,
    val autoSaveOnExit: Boolean,
    val autoLoadOnStart: Boolean
)

class EmulationViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "EmulationViewModel"
        private const val AUTO_SAVE_SLOT = 0
        private const val PLAY_TIME_LOCAL_CACHE_INTERVAL_MS = 60_000L
        private const val PLAY_TIME_CLOUD_SYNC_INTERVAL_MS = 10L * 60_000L
        private val SAVE_STATE_FILE_REGEX = Regex("""^(.+?)\.(\d{2})\.cst$""")
    }


    private val preferences = AppPreferences(application)
    private val cheatRepository = CheatRepository(application)
    private val perGameSettingsRepository = PerGameSettingsRepository(application)
    private val gameRepository = GameRepository()
    private val saveStateRepository = SaveStateRepository(application)
    private val playerProfileRepository = PlayerProfileRepository(application)
    private val playTimeSyncCacheRepository = PlayTimeSyncCacheRepository(application)
    private val performanceCpuName = MobileSocNameMapper.currentDeviceName()
    private val performanceGpuName = GpuHardwareProfiles.gpuDisplayName()
    private val androidGamePerformance = AndroidGamePerformance(application)
    private val appPerformanceVersion = performanceAppVersion(application)
    private val _uiState = MutableStateFlow(
        EmulationUiState(performanceOverlayHeader = performanceCoreHeader(appPerformanceVersion, null))
    )
    // Derive terminal presentation from authoritative runtime state. Deferred
    // startup/save-load coroutines cannot overwrite a fatal error with Running.
    val uiState: StateFlow<EmulationUiState> = combine(_uiState, EmulatorBridge.runtimeFailure) {
        state, failure -> state.withRuntimeFailure(failure)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        _uiState.value.withRuntimeFailure(EmulatorBridge.runtimeFailure.value))
    private val _performanceState = MutableStateFlow(EmulationPerformanceState())
    val performanceState: StateFlow<EmulationPerformanceState> = _performanceState.asStateFlow()
    private val _keyboardMouseOverride = MutableStateFlow<KeyboardMouseSettings?>(null)
    val keyboardMouseOverride: StateFlow<KeyboardMouseSettings?> = _keyboardMouseOverride.asStateFlow()
    private val lifecycleMutex = Mutex()
    private var pausedForBackground = false
    @Volatile
    private var isShuttingDown = false
    @Volatile
    private var cancelPendingStart = false
    @Volatile
    private var currentGameTitle: String = ""
    @Volatile
    private var currentGamePath: String? = null
    private var currentAnalyticsAudioBackend: Int = AudioDefaults.BACKEND_DEFAULT
    @Volatile
    private var currentGameSerial: String = ""
    @Volatile
    private var currentGameRegionLabel: String = ""
    @Volatile
    private var currentGameCoverArtPath: String? = null
    @Volatile
    private var currentGameCrc: String = ""
    @Volatile
    private var currentGameSource: String = ""
    /** Per-game core option overrides resolved at launch, applied after boot. */
    private var pendingPerGameCoreOptions: Map<String, String> = emptyMap()
    private var currentTouchControlsLayoutProfile: TouchControlsLayoutProfile? = null
    private var currentCustomTouchControlsProfile: CustomTouchControlLibrary? = null
    private var lastAutoSavePlayTimeMs: Long = 0L
    private var pendingPlayTimeSyncMs: Long = 0L
    private var playTimeSyncJob: Job? = null
    private var savePreviewJob: Job? = null
    @Volatile private var coverDownloadJob: Job? = null
    private val coverDownloadGeneration = AtomicLong()

    private fun cancelCoverDownload() {
        coverDownloadGeneration.incrementAndGet()
        coverDownloadJob?.cancel()
        coverDownloadJob = null
    }

    private fun downloadMissingCover(game: GameItem) {
        if (currentGameCoverArtPath != null || cancelPendingStart || isShuttingDown) return
        val generation = coverDownloadGeneration.get()
        coverDownloadJob = viewModelScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    gameRepository.downloadCoverForGame(game, getApplication())
                } ?: return@launch
                ensureActive()
                lifecycleMutex.withLock {
                    ensureActive()
                    if (generation != coverDownloadGeneration.get() ||
                        currentGamePath != game.path || isShuttingDown ||
                        currentGameCoverArtPath != null) return@withLock
                    currentGameCoverArtPath = downloaded
                    _uiState.update { latest -> latest.copy(currentGameCoverPath = downloaded) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Unable to download game cover", error)
            }
        }
    }
    private val previewRequests = SaveStatePreviewRequests()
    val saveStatePreviewRequests = previewRequests.requests
    private var lastCloudPlayTimeSyncAtMs: Long = 0L
    private var shouldCountCurrentProfileSession = false
    /** Profile play time is only tracked for real game launches, never for BIOS or autotests. */
    private var shouldTrackCurrentProfilePlayTime = false
    private val playTimeSyncMutex = Mutex()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            com.sbro.emucored.data.cheats.CheatRuntimeUpdates.changes.collect { identity ->
                if (identity == currentGamePath && activeGamePlatform() != GamePlatform.N3DS) {
                    refreshAvailableTypedCheats()
                    syncCheatsForCurrentGame()
                }
            }
        }
        viewModelScope.launch {
            uiState
                .map { state ->
                    resolveAndroidGamePhase(
                        isStarting = state.isStarting,
                        isRunning = state.isRunning,
                        isPaused = state.isPaused,
                        showMenu = state.showMenu
                    )
                }
                .distinctUntilChanged()
                .collect(androidGamePerformance::update)
        }
    }

    private inline fun applyGlobalRuntimePreferenceUpdate(
        crossinline transform: (EmulationUiState) -> EmulationUiState
    ) {
        // A running game session owns its per-game overrides: a late global
        // preference emission must not overwrite them (e.g. upscale 2x set in
        // the in-game menu being reset to the global 1x a moment later).
        if (activePerGameKey() != null) return
        _uiState.update { current ->
            if (current.gameSettingsProfileActive) current else transform(current)
        }
        syncNativePerformanceOverlayState(_uiState.value)
        syncGamepadRuntimeSettings(_uiState.value)
    }

    private fun syncNativePerformanceOverlayState(state: EmulationUiState) {
        val detailed = state.showFps && state.fpsOverlayMode != FPS_OVERLAY_MODE_SIMPLE
        NativeApp.setPerformanceMetricsEnabled(visible = state.showFps, detailed = detailed)
    }

    private fun syncGamepadRightStickTriggerMapping(state: EmulationUiState) {
        GamepadManager.setRightStickTriggerMapping(
            upToR2 = state.gamepadRightStickUpToR2,
            downToL2 = state.gamepadRightStickDownToL2
        )
    }

    private fun syncGamepadRuntimeSettings(state: EmulationUiState) {
        syncGamepadRightStickTriggerMapping(state)
        GamepadManager.setButtonHapticsEnabled(
            enabled = state.gamepadButtonHaptics,
            strengthPercent = state.touchHapticsStrength,
            preset = state.touchHapticsPreset
        )
    }

    init {
        viewModelScope.launch {
            NativeApp.timeControlMode.collect { mode ->
                val transport = when (mode) {
                    1 -> EmulationTransportMode.FastForward
                    2 -> EmulationTransportMode.Rewind
                    else -> EmulationTransportMode.None
                }
                if (_uiState.value.transportMode != transport) {
                    _uiState.update { latest -> latest.copy(transportMode = transport) }
                }
            }
        }
        viewModelScope.launch {
            preferences.overlayShow.collect { enabled ->
                _uiState.update { latest -> latest.copy(controlsVisible = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.showFps.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(showFps = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.overlayLayoutSnapshot.collect { snapshot ->
                applyOverlayLayoutSnapshot(snapshot)
            }
        }
        viewModelScope.launch {
            preferences.touchControlVisualStyle.collect { style ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlVisualStyle
                }
                _uiState.update { latest -> latest.copy(touchControlVisualStyle = override ?: style) }
            }
        }
        viewModelScope.launch {
            preferences.touchControlPressEffect.collect { effect ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlPressEffect
                }
                _uiState.update { latest -> latest.copy(touchControlPressEffect = override ?: effect) }
            }
        }
        viewModelScope.launch {
            preferences.stickToggleTarget.collect { target ->
                _uiState.update { latest -> latest.copy(stickToggleTarget = target) }
            }
        }
        viewModelScope.launch {
            preferences.gameMenuLayoutStyle.collect { style ->
                _uiState.update { latest -> latest.copy(gameMenuLayoutStyle = style) }
            }
        }
        viewModelScope.launch {
            preferences.gameMenuTabOrder.collect { order ->
                _uiState.update { latest -> latest.copy(gameMenuTabOrder = order) }
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuTabs.collect { hidden ->
                _uiState.update { latest -> latest.copy(hiddenGameMenuTabs = hidden) }
            }
        }
        viewModelScope.launch {
            preferences.gameMenuSectionOrder.collect { order ->
                _uiState.update { latest -> latest.copy(gameMenuSectionOrder = order) }
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuSections.collect { hidden ->
                _uiState.update { latest -> latest.copy(hiddenGameMenuSections = hidden) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMode.collect { mode ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fpsOverlayMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayCorner.collect { corner ->
                _uiState.update { latest -> latest.copy(fpsOverlayCorner = corner) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayScale.collect { scale ->
                _uiState.update { latest -> latest.copy(fpsOverlayScale = scale) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMetrics.collect { metrics ->
                val updated = _uiState.updateAndGet { it.copy(fpsOverlayMetrics = metrics) }
                syncNativePerformanceOverlayState(updated)
            }
        }
        viewModelScope.launch {
            preferences.compactControls.collect { enabled ->
                _uiState.update { latest -> latest.copy(compactControls = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadStickDeadzone.collect { value ->
                _uiState.update { latest -> latest.copy(gamepadStickDeadzone = value) }
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStick.collect { enabled ->
                _uiState.update { latest -> latest.copy(invertLeftStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.invertRightStick.collect { enabled ->
                _uiState.update { latest -> latest.copy(invertRightStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStickHorizontal.collect { enabled ->
                _uiState.update { latest -> latest.copy(invertLeftStickHorizontal = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.invertRightStickHorizontal.collect { enabled ->
                _uiState.update { latest -> latest.copy(invertRightStickHorizontal = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadLeftStickSensitivity.collect { value ->
                _uiState.update { latest -> latest.copy(gamepadLeftStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickSensitivity.collect { value ->
                _uiState.update { latest -> latest.copy(gamepadRightStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickUpToR2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickUpToR2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickDownToL2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickDownToL2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadButtonHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadButtonHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.confirmSaveLoadActions.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(confirmSaveLoadActions = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.backButtonExitsGame.collect { enabled ->
                _uiState.update { latest -> latest.copy(backButtonExitsGame = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.keepScreenOn.collect { enabled ->
                _uiState.update { latest -> latest.copy(keepScreenOn = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.renderer.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(renderer = value) }
            }
        }
        viewModelScope.launch {
            preferences.upscaleMultiplier.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(upscale = value) }
            }
        }
        viewModelScope.launch {
            preferences.aspectRatio.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(aspectRatio = value) }
            }
        }
        viewModelScope.launch {
            preferences.displayCrop.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(displayCrop = value) }
            }
        }
        viewModelScope.launch {
            preferences.enableCheats.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(enableCheats = value) }
            }
        }
        viewModelScope.launch {
            preferences.frameSkip.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(frameSkip = value) }
            }
        }
        viewModelScope.launch {
            preferences.frameLimitEnabled.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(frameLimitEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.targetFps.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(targetFps = value) }
            }
        }
        viewModelScope.launch {
            preferences.fastForwardSpeed.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fastForwardSpeed = value) }
            }
        }
        viewModelScope.launch {
            preferences.racingMode.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(racingMode = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.stickyButtons.collect { buttons ->
                applyGlobalRuntimePreferenceUpdate { it.copy(stickyButtons = buttons) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenLeftStick.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenLeftStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenLeftStickSensitivity.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenLeftStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStick.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStickSensitivity.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsPreset.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsPreset = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsStrength.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsStrength = value) }
            }
        }
        viewModelScope.launch { preferences.gyroMode.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroMode = value) } } }
        viewModelScope.launch { preferences.gyroSensitivity.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSensitivity = value) } } }
        viewModelScope.launch { preferences.gyroSmoothing.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSmoothing = value) } } }
        viewModelScope.launch { preferences.gyroInvertX.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertX = value) } } }
        viewModelScope.launch { preferences.gyroInvertY.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertY = value) } } }
        viewModelScope.launch {
            preferences.autoSaveEnabled.collect { enabled ->
                _uiState.update { latest -> latest.copy(autoSaveEnabled = enabled) }
                if (enabled) {
                    lastAutoSavePlayTimeMs = _uiState.value.activePlayTimeMs
                }
            }
        }
        viewModelScope.launch {
            preferences.autoSaveIntervalMinutes.collect { value ->
                _uiState.update { latest -> latest.copy(autoSaveIntervalMinutes = value.coerceIn(1, 999)) }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(250.milliseconds)
                pollNativePerformanceMetrics()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000.milliseconds)
                tickActivePlayTimeAndAutoSave()
            }
        }
        syncNativePerformanceOverlayState(_uiState.value)
    }

    private fun tickActivePlayTimeAndAutoSave() {
        val state = _uiState.value
        if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown ||
            EmulatorBridge.runtimeFailure.value != null) {
            return
        }

        val nextPlayTimeMs = state.activePlayTimeMs + 1_000L
        _uiState.update { it.copy(activePlayTimeMs = nextPlayTimeMs) }
        if (shouldTrackCurrentProfilePlayTime) {
            pendingPlayTimeSyncMs += 1_000L
            if (pendingPlayTimeSyncMs >= PLAY_TIME_LOCAL_CACHE_INTERVAL_MS) {
                schedulePlayTimeSync()
            }
        }

        val intervalMs = state.autoSaveIntervalMinutes.coerceIn(1, 999) * 60_000L
        if (!state.autoSaveEnabled ||
            state.isActionInProgress ||
            state.isAutoSaveInProgress ||
            nextPlayTimeMs - lastAutoSavePlayTimeMs < intervalMs
        ) {
            return
        }

        lastAutoSavePlayTimeMs = nextPlayTimeMs
        performAutoSave()
    }

    private fun schedulePlayTimeSync() {
        if (playTimeSyncJob?.isActive == true) return
        playTimeSyncJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    cachePendingPlayTime()
                    flushCachedPlayTimeIfDue(force = false)
                }
            } finally {
                playTimeSyncJob = null
            }
        }
    }

    private suspend fun cachePendingPlayTime() {
        playTimeSyncMutex.withLock {
            if (!shouldTrackCurrentProfilePlayTime) {
                pendingPlayTimeSyncMs = 0L
                shouldCountCurrentProfileSession = false
                return@withLock
            }
            val durationMs = pendingPlayTimeSyncMs
            if (durationMs <= 0L) return@withLock
            val path = currentGamePath
            val title = currentGameTitle
            val serial = currentGameSerial.takeIf { it.isNotBlank() }
            val coverArtPath = currentGameCoverArtPath
            pendingPlayTimeSyncMs = 0L
            val cached = runCatching {
                playTimeSyncCacheRepository.add(
                    PlayerPlayTimeDelta(
                        gamePath = path,
                        title = title,
                        serial = serial,
                        coverArtPath = coverArtPath,
                        durationMs = durationMs,
                        sessionCount = if (shouldCountCurrentProfileSession) 1L else 0L,
                        platform = activeGamePlatform()
                    )
                )
            }.isSuccess
            if (cached) {
                shouldCountCurrentProfileSession = false
            } else {
                pendingPlayTimeSyncMs += durationMs
            }
        }
    }

    private suspend fun flushCachedPlayTimeIfDue(force: Boolean) {
        val nowMs = System.currentTimeMillis()
        val lastSyncAtMs = maxOf(lastCloudPlayTimeSyncAtMs, playTimeSyncCacheRepository.getLastCloudSyncAtMs())
        if (!force && nowMs - lastSyncAtMs < PLAY_TIME_CLOUD_SYNC_INTERVAL_MS) {
            return
        }
        if (!playerProfileRepository.hasSignedInUser()) {
            return
        }
        val entries = playTimeSyncCacheRepository.drain()
        if (entries.isEmpty()) return
        val synced = runCatching {
            playerProfileRepository.recordPlayTimeBatch(entries)
        }.isSuccess
        if (synced) {
            lastCloudPlayTimeSyncAtMs = nowMs
            playTimeSyncCacheRepository.setLastCloudSyncAtMs(nowMs)
        } else {
            playTimeSyncCacheRepository.restore(entries)
        }
    }

    private suspend fun syncPendingPlayTime(forceCloud: Boolean = true) {
        cachePendingPlayTime()
        flushCachedPlayTimeIfDue(force = forceCloud)
    }

    private fun syncCachedPlayTimeInBackground(forceCloud: Boolean = true) {
        val app = getApplication<Application>() as EmuCoreDApp
        app.applicationScope.launch {
            runCatching { syncPendingPlayTime(forceCloud = forceCloud) }
        }
    }

    private fun performAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            saveAutoSaveSlot(allowWhileMenu = false, allowPaused = false, showActionProgress = false)
        }
    }

    private suspend fun saveAutoSaveSlot(
        allowWhileMenu: Boolean,
        allowPaused: Boolean,
        showActionProgress: Boolean,
        allowDuringShutdown: Boolean = false
    ): Boolean {
        val path = currentGamePath ?: return false
        val previousModified = saveStateLastModified(path, AUTO_SAVE_SLOT)
        _uiState.update { before -> before.copy(
            isAutoSaveInProgress = true,
            isActionInProgress = if (showActionProgress) true else before.isActionInProgress,
            actionLabel = if (showActionProgress) "saving" else before.actionLabel
        ) }
        val scheduled = lifecycleMutex.withLock {
            val state = _uiState.value
            if ((isShuttingDown && !allowDuringShutdown) ||
                (!state.isRunning && !allowDuringShutdown) ||
                (!allowPaused && state.isPaused) ||
                (!allowWhileMenu && state.showMenu)
            ) {
                false
            } else {
                try {
                    EmulatorBridge.saveState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        val success = scheduled && waitForSaveStateUpdate(path, AUTO_SAVE_SLOT, previousModified)
        _uiState.update { after -> after.copy(
            isAutoSaveInProgress = false,
            isActionInProgress = if (showActionProgress) false else after.isActionInProgress,
            actionLabel = if (showActionProgress) null else after.actionLabel
        ) }
        refreshSaveStateMetadata()
        if (success) requestSaveStatePreview(path, AUTO_SAVE_SLOT, awaitCompletion = allowDuringShutdown)
        return success
    }

    private suspend fun requestSaveStatePreview(path: String, slot: Int, awaitCompletion: Boolean = false) {
        val savePath = resolveSaveStateFile(path, slot)?.absolutePath ?: return
        previewRequests.request(savePath, DsSession.isActive, awaitCompletion)
    }

    private fun pollNativePerformanceMetrics() {
        val state = _uiState.value
        if (!state.isRunning || isShuttingDown || state.isPaused || !state.showFps) return
        val additional = AdditionalConsoleSession.session?.takeIf { it.isRunning }
        val livePlatform = activePerformancePlatform(additional?.platform, DsSession.isActive, CoreRuntime.isRunning())
        val liveHeader = performanceCoreHeader(appPerformanceVersion, livePlatform)
        if (state.performanceOverlayHeader != liveHeader) {
            _uiState.update { it.copy(performanceOverlayHeader = liveHeader) }
        }
        if (additional != null) {
            val measured = AdditionalConsoleSession.performanceMetricsSnapshot() ?: return
            val measuredFps = measured.fps ?: return
            val details = buildString {
                append("FPS: ").append("%.1f".format(measuredFps))
                measured.speedPercent?.let { append(" | ").append("%.1f%%".format(it)) }
                measured.frameTimeMs?.let { append(" | ").append("%.2f ms".format(it)) }
                if (performanceCpuName.isNotBlank()) append('\n').append("CPU: ").append(performanceCpuName)
                if (performanceGpuName.isNotBlank()) append('\n').append("GPU: ").append(performanceGpuName)
                measured.backend?.let { append('\n').append("Renderer: ").append(it) }
                // Engines report the guest/display size, not the internal render size.
                // Apply the active upscale so this line follows in-game resolution changes.
                measured.width?.let { width ->
                    measured.height?.let { height ->
                        scaledPerformanceResolution(width, height, state.upscale)?.let { resolution ->
                            append('\n').append("Res: ").append(resolution)
                        }
                    }
                }
            }
            _performanceState.value = EmulationPerformanceState(
                fps = "%.1f".format(measuredFps),
                speedPercent = measured.speedPercent?.toFloat() ?: 0f,
                overlayText = details
            )
            return
        }
        val raw = if (DsSession.isActive) {
            DsSession.performanceMetricsSnapshot().orEmpty()
        } else {
            NativeApp.getPerformanceMetricsSnapshot().orEmpty()
        }
        if (raw.isBlank()) return

        val parts = raw.split('\n', limit = 3)
        if (parts.size < 3) return
        val fps = parts[0].toFloatOrNull() ?: return
        val speedPercent = parts[1].toFloatOrNull() ?: return
        val overlayText = buildPerformanceOverlayText(parts[2], state)
        _performanceState.value = EmulationPerformanceState(
            fps = "%.1f".format(fps),
            speedPercent = speedPercent,
            overlayText = overlayText
        )
    }

    private fun buildPerformanceOverlayText(metrics: String, state: EmulationUiState): String {
        val dsActive = DsSession.isActive
        val rendererName = if (dsActive) {
            if (DsSession.currentRenderer() == 13) "Software" else "Vulkan"
        } else {
            RendererDefaults.coreRendererName(
                RendererDefaults.toCoreRenderer(state.renderer)
            )
        }
        val upscale = normalizeUpscale(state.upscale)
        val upscaleLabel = if (upscale <= 1f) "Native" else "${upscale.roundToInt()}x"
        val baseWidth = if (dsActive) 256 else 400
        val baseHeight = if (dsActive) 386 else 240
        val resolution = scaledPerformanceResolution(baseWidth, baseHeight, upscale)
        return buildString {
            append(metrics)
            if (performanceCpuName.isNotBlank()) {
                append('\n').append("CPU: ").append(performanceCpuName)
            }
            if (performanceGpuName.isNotBlank()) {
                append('\n').append("GPU: ").append(performanceGpuName)
            }
            append('\n')
                .append("Renderer: ")
                .append(rendererName)
                .append(" | ")
                .append(upscaleLabel)
            resolution?.let {
                append('\n')
                    .append("Res: ")
                    .append(it)
            }
        }
    }

    fun startEmulation(
        path: String?,
        slotToLoad: Int? = null,
        bootSmokeProbe: Boolean = false,
        autotestMode: Boolean = false,
        rendererOverride: Int? = null,
        launchPathOverride: String? = null
    ) {
        val analyticsLaunchType = when {
            bootSmokeProbe -> "smoke_test"
            autotestMode -> "autotest"
            else -> "game"
        }
        Log.i(
            TAG,
            "startEmulation requested path=$path bootSmoke=$bootSmokeProbe autotest=$autotestMode"
        )
        if (_uiState.value.isStarting) {
            Log.w(TAG, "startEmulation skipped because another start is in progress")
            return
        }
        cancelCoverDownload()
        val normalizedSlotToLoad = slotToLoad?.let { normalizeSaveSlot(it) }
        val hasPendingStateLoad = !bootSmokeProbe && normalizedSlotToLoad != null
        var analyticsPerformanceProfile = PerformanceProfiles.SAFE
        cancelPendingStart = false
        pausedForBackground = false
        if (pendingPlayTimeSyncMs > 0L) {
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    cachePendingPlayTime()
                }
            }
        }
        currentGamePath = path?.takeIf { it.isNotBlank() }
        currentTouchControlsLayoutProfile = null
        currentCustomTouchControlsProfile = null
        currentGameCoverArtPath = null
        lastAutoSavePlayTimeMs = 0L
        pendingPlayTimeSyncMs = 0L
        shouldCountCurrentProfileSession = false
        shouldTrackCurrentProfilePlayTime = false
        _uiState.update { latest -> latest.copy(
            activePlayTimeMs = 0L,
            currentSlotLastModified = 0L,
            autoSaveLastModified = 0L,
            isAutoSaveInProgress = false
        ) }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { latest -> latest.copy(
                statusMessage = "status_preparing"
            ) }

            var knownPlatform: GamePlatform? = null
            currentGamePath?.let { source ->
                val probeContext = currentCoroutineContext()
                knownPlatform = ConsoleGameDiscovery.identify(getApplication(), source,
                    checkActive = { probeContext.ensureActive() })
                    ?: GamePlatform.forPath(source)
                if (knownPlatform != null) {
                    GamePlatform.remember(source, knownPlatform!!)
                    _uiState.update { latest -> latest.copy(gamePlatform = knownPlatform!!) }
                } else if (!autotestMode && !bootSmokeProbe) {
                    // Never fall through to the default 3DS core for an image
                    // whose actual console could not be established.
                    Log.w(TAG, "Unable to identify the console for $source")
                    _uiState.update { latest -> latest.copy(
                        isStarting = false,
                        isRunning = false,
                        statusMessage = null,
                        toastMessage = "launch_failed"
                    ) }
                    delay(2500.milliseconds)
                    _uiState.update { latest -> latest.copy(toastMessage = null) }
                    return@launch
                }
            }

            // A first DS launch may need to materialize its SAF document. Start
            // that work while the previous core is still shutting down so the
            // 3DS -> DS hand-off does not wait twice.
            val dsRomPreparation = if (knownPlatform == GamePlatform.NDS && !path.isNullOrBlank()) {
                async(Dispatchers.IO) {
                    runCatching { DsSession.prepareRom(getApplication(), path) }.getOrNull()
                }
            } else null

            if (_uiState.value.isRunning || EmulatorBridge.isVmActive()) {
                _uiState.update { latest -> latest.copy(
                    isStarting = true,
                    statusMessage = "status_waiting_vm"
                ) }
                performShutdown()
                Log.i(TAG, "Previous session shutdown complete")
            }
            dsRomPreparation?.await()
            Log.i(TAG, "ROM preparation complete")

            var finalLaunchPath: String? = null
            var dsRenderScale = 1
            var dsRenderer = 14
            if (isShuttingDown) {
                Log.i(TAG, "Waiting for the previous session teardown to finish")
                while (isShuttingDown && !cancelPendingStart) delay(25.milliseconds)
            }
            if (cancelPendingStart) {
                Log.i(TAG, "Launch cancelled by a newer session request")
                return@launch
            }
            Log.i(TAG, "Waiting for launch lock shuttingDown=$isShuttingDown cancel=$cancelPendingStart")
            lifecycleMutex.withLock {
                if (isShuttingDown || cancelPendingStart) {
                    Log.i(TAG, "Launch aborted after lock: shuttingDown=$isShuttingDown cancel=$cancelPendingStart")
                    return@launch
                }
                Log.i(TAG, "Launch lifecycle lock acquired")
                _uiState.update { latest -> latest.copy(
                    isStarting = true,
                    statusMessage = "status_checking_bios"
                ) }

                val config = loadLaunchConfig()
                dsRenderScale = config.upscaleMultiplier.roundToInt().coerceIn(1, 8)
                val renderer = rendererOverride ?: config.renderer
                // Preserve DS software (13) before the 3DS config normalizes renderer IDs.
                dsRenderer = renderer
                Log.i(TAG, "Launch config loaded renderer=$renderer override=${rendererOverride != null} platform=${activeGamePlatform()}")
                _uiState.update { latest -> latest.copy(
                    statusMessage = "status_applying_config"
                ) }

                if (activeGamePlatform() == GamePlatform.N3DS) {
                    EmulatorBridge.applyRuntimeConfig(
                        emulatorDataPath = config.emulatorDataPath,
                        gamePath = path,
                        renderer = renderer,
                        upscaleMultiplier = config.upscaleMultiplier,
                        gpuHardwareProfile = config.gpuHardwareProfile,
                        mediatekAngleOpenGl = config.mediatekAngleOpenGl,
                        aspectRatio = config.aspectRatio,
                        displayCrop = config.displayCrop,
                        audioVolume = config.audioVolume,
                        audioFastForwardVolume = config.audioFastForwardVolume,
                        audioMuted = config.audioMuted,
                        audioOutputLatencyMs = config.audioOutputLatencyMs,
                        audioMinimalOutputLatency = config.audioMinimalOutputLatency,
                        frameSkip = config.frameSkip,
                        frameLimitEnabled = config.frameLimitEnabled,
                        vSyncEnabled = config.vSyncEnabled,
                        fastForwardSpeed = config.fastForwardSpeed,
                        shaderChainEnabled = config.shaderChainEnabled,
                        shaderChainPreset = config.shaderChainPreset,
                        textureReplacementsEnabled = config.textureReplacementsEnabled,
                        enableCheats = config.enableCheats,
                        autotestMode = autotestMode,
                        layoutOption = config.layoutOption,
                        swapScreens = config.swapScreens
                    )
                }

                _uiState.update { latest -> latest.copy(
                    statusMessage = "status_loading_game"
                ) }

                val bootSourcePath = launchPathOverride?.takeIf { it.isNotBlank() } ?: path
                val launchPath = when {
                    bootSourcePath.isNullOrBlank() -> null
                    activeGamePlatform() !in setOf(GamePlatform.N3DS, GamePlatform.NDS) -> bootSourcePath
                    bootSourcePath.startsWith("content://") && DocumentPathResolver.getDisplayName(getApplication(), bootSourcePath)
                        .substringAfterLast('.', "").equals("elf", ignoreCase = true) ->
                            DocumentPathResolver.prepareElfLaunchPath(getApplication(), bootSourcePath)
                    else -> DocumentPathResolver.prepareGameLaunchPath(getApplication(), bootSourcePath)
                }

                if (launchPath.isNullOrBlank()) {
                    _uiState.update { latest -> latest.copy(
                        isStarting = false,
                        statusMessage = null,
                        toastMessage = "launch_path_error"
                    ) }
                    delay(2500.milliseconds)
                    _uiState.update { latest -> latest.copy(toastMessage = null) }
                    return@withLock
                }

                finalLaunchPath = launchPath
                Log.i(TAG, "Prepared launch path=$launchPath originalPath=$path")
                if (autotestMode) {
                    val safePath = path.orEmpty()
                    currentGameTitle = File(safePath).nameWithoutExtension.ifBlank { "Autotest ELF" }
                    currentGameSerial = ""
                    currentGameRegionLabel = ""
                    currentGameCoverArtPath = null
                    currentGameCrc = ""
                    currentGameSource = "autotest_elf"
                    shouldCountCurrentProfileSession = false
                    shouldTrackCurrentProfilePlayTime = false
                    pendingPerGameCoreOptions = emptyMap()
                    _uiState.update { latest -> latest.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = false,
                        perGameCoreOptions = emptyMap(),
                        cheatsGameKey = null,
                        availableCheats = emptyList()
                    ) }
                } else {
                    val safePath = path.orEmpty()
                    val existingProfile = currentGamePath?.let(perGameSettingsRepository::get)
                    currentTouchControlsLayoutProfile = existingProfile?.touchControlsLayout
                    currentCustomTouchControlsProfile = existingProfile?.customTouchControls
                    val metadata = EmulatorBridge.getGameMetadata(safePath)
                    currentGameTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, safePath)
                    currentGameSerial = metadata.serial?.takeIf { it.isNotBlank() }
                        ?.uppercase(java.util.Locale.ROOT).orEmpty()
                    currentGameRegionLabel = resolveRegionLabel(safePath)
                    currentGameCoverArtPath = gameRepository.findCoverForGame(
                        path = safePath,
                        context = getApplication(),
                        serial = metadata.serial?.takeIf { it.isNotBlank() },
                        title = currentGameTitle
                    )
                    shouldTrackCurrentProfilePlayTime = !bootSmokeProbe
                    shouldCountCurrentProfileSession = shouldTrackCurrentProfilePlayTime
                    currentGameCrc = metadata.serialWithCrc.extractCrc().orEmpty()
                    currentGameSource = when {
                        safePath.startsWith("content://") -> "content_uri"
                        launchPath?.removePrefix("!")?.startsWith("/") == true -> "file"
                        else -> "unknown"
                    }
                    pendingPerGameCoreOptions = if (activeGamePlatform() != GamePlatform.N3DS) emptyMap() else (
                        existingProfile
                            ?: safePath.takeIf { it.isNotBlank() }?.let(perGameSettingsRepository::get)
                        )?.coreOptions.orEmpty()
                        .filterKeys { !AzaharCoreOptions.isManagedKey(it) && it != "swap_screen" }
                    refreshCurrentGameCheats(metadata, config.enableCheats)
                    _uiState.update { latest -> latest.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = existingProfile != null,
                        perGameCoreOptions = pendingPerGameCoreOptions
                    ) }
                    downloadMissingCover(GameItem(
                        title = currentGameTitle,
                        path = safePath,
                        fileName = currentGameTitle,
                        fileSize = 0L,
                        lastModified = 0L,
                        serial = currentGameSerial.takeIf { it.isNotBlank() },
                        platform = PlatformUiCapabilities.forGame(safePath).platform
                    ))
                    syncCurrentGameProfileMetadata()
                    if (!bootSmokeProbe) {
                        DiscordIntegration.setPlaying(
                            title = currentGameTitle,
                            serial = currentGameSerial.takeIf { it.isNotBlank() }
                        )
                    }
                }
                applyOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )

                _uiState.update { latest -> latest.copy(
                    isRunning = true,
                    isStarting = false,
                    statusMessage = "status_starting_core"
                ) }
                refreshSaveStateMetadata()
                if (!autotestMode && !bootSmokeProbe && !path.isNullOrBlank()) {
                    preferences.markGameLaunched(
                        path = path,
                        title = currentGameTitle.ifBlank {
                            DocumentPathResolver.getDisplayName(getApplication(), path).substringBeforeLast('.')
                        },
                        serial = currentGameSerial.takeIf { it.isNotBlank() }
                    )
                }

                val liveRuntime = loadLiveRuntimeSnapshot()
                val overlaySnapshot = preferences.overlayLayoutSnapshot.first()

                _uiState.update { latest ->
                    val runtimeState = latest.copy(
                        showFps = liveRuntime.showFps,
                        fpsOverlayMode = liveRuntime.fpsOverlayMode,
                        confirmSaveLoadActions = liveRuntime.confirmSaveLoadActions,
                        backButtonExitsGame = liveRuntime.backButtonExitsGame,
                        renderer = renderer,
                        swapScreens = config.swapScreens ?: false,
                        upscale = liveRuntime.upscale,
                        aspectRatio = liveRuntime.aspectRatio,
                        displayCrop = liveRuntime.displayCrop,
                        enableCheats = liveRuntime.enableCheats,
                        frameSkip = liveRuntime.frameSkip,
                        frameLimitEnabled = liveRuntime.frameLimitEnabled,
                        fastForwardSpeed = liveRuntime.fastForwardSpeed,
                        racingMode = liveRuntime.racingMode,
                        stickyButtons = liveRuntime.stickyButtons,
                        touchscreenLeftStick = liveRuntime.touchscreenLeftStick,
                        touchscreenLeftStickSensitivity = liveRuntime.touchscreenLeftStickSensitivity,
                        touchscreenRightStick = liveRuntime.touchscreenRightStick,
                        touchscreenRightStickSensitivity = liveRuntime.touchscreenRightStickSensitivity,
                        touchHaptics = liveRuntime.touchHaptics,
                        touchHapticsPreset = liveRuntime.touchHapticsPreset,
                        touchHapticsStrength = liveRuntime.touchHapticsStrength,
                        touchControlVisualStyle = liveRuntime.touchControlVisualStyle,
                        touchControlPressEffect = liveRuntime.touchControlPressEffect,
                        gyroMode = liveRuntime.gyroMode,
                        gyroSensitivity = liveRuntime.gyroSensitivity,
                        gyroSmoothing = liveRuntime.gyroSmoothing,
                        gyroInvertX = liveRuntime.gyroInvertX,
                        gyroInvertY = liveRuntime.gyroInvertY,
                        gamepadRightStickUpToR2 = liveRuntime.gamepadRightStickUpToR2,
                        gamepadRightStickDownToL2 = liveRuntime.gamepadRightStickDownToL2,
                        gamepadButtonHaptics = liveRuntime.gamepadButtonHaptics,
                        gamepadStickDeadzone = liveRuntime.gamepadStickDeadzone,
                        gamepadLeftStickSensitivity = liveRuntime.gamepadLeftStickSensitivity,
                        gamepadRightStickSensitivity = liveRuntime.gamepadRightStickSensitivity,
                        gamepadBindingsByPad = liveRuntime.gamepadBindingsByPad,
                        autoSaveOnExit = liveRuntime.autoSaveOnExit,
                        autoLoadOnStart = liveRuntime.autoLoadOnStart,
                        targetFps = liveRuntime.targetFps,
                    )
                    val overlayState = runtimeState.withOverlayLayoutSnapshot(overlaySnapshot)
                    currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
                }
                syncNativePerformanceOverlayState(_uiState.value)
                syncGamepadRuntimeSettings(_uiState.value)
                if (_uiState.value.gameSettingsProfileActive) {
                    val state = _uiState.value
                    GamepadManager.applyPerGameOverrides(
                        bindingsByPad = state.gamepadBindingsByPad,
                        deadzone = state.gamepadStickDeadzone,
                        leftSensitivity = state.gamepadLeftStickSensitivity,
                        rightSensitivity = state.gamepadRightStickSensitivity
                    )
                }
                refreshKeyboardMouseOverride()
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )
            }

            if (hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var vmReadyWaitFrames = 0
                    while (vmReadyWaitFrames < 60 && isActive) {
                        try {
                            if (EmulatorBridge.hasValidVm()) break
                        } catch (_: Exception) { }
                        delay(250.milliseconds)
                        vmReadyWaitFrames++
                    }
                    if (!isActive) return@launch

                    if (!EmulatorBridge.hasValidVm()) {
                        _uiState.update { latest -> latest.copy(
                            statusMessage = null,
                            toastMessage = "load_failed"
                        ) }
                        delay(2500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.update { latest -> latest.copy(toastMessage = null) }
                        }
                        return@launch
                    }

                    delay(500.milliseconds)
                    val loaded = EmulatorBridge.loadState(normalizedSlotToLoad)
                    _uiState.update { latest -> latest.copy(
                        isRunning = true,
                        isPaused = false,
                        statusMessage = if (loaded) "status_running" else null,
                        toastMessage = if (loaded) null else "load_failed"
                    ) }
                    refreshSaveStateMetadata()
                    delay(2000.milliseconds)
                    if (_uiState.value.statusMessage == "status_running") {
                        _uiState.update { latest -> latest.copy(statusMessage = null) }
                    }
                    if (_uiState.value.toastMessage == "load_failed") {
                        delay(500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.update { latest -> latest.copy(toastMessage = null) }
                        }
                    }
                }
            }

            val pathToLaunch = finalLaunchPath ?: return@launch

            if (cancelPendingStart) {
                _uiState.update { latest -> latest.copy(
                    isRunning = false,
                    isStarting = false,
                    statusMessage = null
                ) }
                return@launch
            }

            if (!hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var waitFrames = 0
                    while (waitFrames < 60 && isActive) {
                        if (EmulatorBridge.hasValidVm()) {
                            if (tryAutoLoadOnStart()) {
                                break
                            }
                            _uiState.update { latest -> latest.copy(statusMessage = "status_running") }
                            delay(2000.milliseconds)
                            if (_uiState.value.statusMessage == "status_running") {
                                _uiState.update { latest -> latest.copy(statusMessage = null) }
                            }
                            break
                        }
                        delay(250.milliseconds)
                        waitFrames++
                    }
                }
            }

            val started = try {
                EmulatorBridge.startEmulation(
                    pathToLaunch,
                    saveStateIdentityPath = path,
                    dsRenderScale = dsRenderScale,
                    dsRenderer = dsRenderer,
                    dsSwapScreens = _uiState.value.swapScreens,
                    platformOverride = activeGamePlatform()
                )
            } catch (error: Exception) {
                Log.e(TAG, "EmulatorBridge.startEmulation failed", error)
                false
            }
            Log.i(TAG, "EmulatorBridge.startEmulation returned $started path=$pathToLaunch")
            if (started) {
                val livePlatform = activePerformancePlatform(
                    AdditionalConsoleSession.session?.takeIf { it.isRunning }?.platform,
                    DsSession.isActive, CoreRuntime.isRunning())
                _uiState.update { latest -> latest.copy(
                    performanceOverlayHeader = performanceCoreHeader(appPerformanceVersion, livePlatform)
                ) }
            }
            if (started) {
                EmulatorBridge.setSwapScreens(_uiState.value.swapScreens)
                RetroAchievementsRepository.get(getApplication()).onGameStarted(pathToLaunch)
                syncCheatsForCurrentGame()
                if (activeGamePlatform() !in setOf(GamePlatform.N3DS, GamePlatform.NDS)) {
                    val effective = CoreOptionPreferences(getApplication())
                        .effectiveOptions(activeGamePlatform().console, currentGamePath)
                    _uiState.update { latest -> latest.copy(perGameCoreOptions = effective) }
                }
                // Per-game core options win over the global store.
                pendingPerGameCoreOptions.forEach { (coreKey, coreValue) ->
                    NativeApp.applyCoreOption(coreKey, coreValue)
                }
            }
            updateCrashContext(
                launchState = if (started) "running" else "launch_failed",
                launchPath = path
            )

            if (!started &&
                !_uiState.value.isPaused &&
                !cancelPendingStart &&
                !isShuttingDown
            ) {
                _uiState.update { latest -> latest.copy(
                    isRunning = false,
                    statusMessage = null,
                    toastMessage = "launch_failed"
                ) }
                delay(2500.milliseconds)
                _uiState.update { latest -> latest.copy(toastMessage = null) }
            }
        }
    }

    fun togglePause() {
        val state = _uiState.value
        if (state.showMenu) {
            closeMenu()
            return
        }

        val isPaused = state.isPaused
        pausedForBackground = false
        _uiState.update { latest -> latest.copy(
            isPaused = !isPaused,
            showMenu = if (isPaused) false else latest.showMenu
        ) }
        DiscordIntegration.setPaused(!isPaused)
        updateCrashContext(launchState = if (!isPaused) "paused" else "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isPaused) {
                    EmulatorBridge.resume()
                } else {
                    EmulatorBridge.pause()
                }
            } catch (_: Exception) { }
        }
    }

    fun toggleMenu() {
        val showMenu = !_uiState.value.showMenu
        if (showMenu) {
            pausedForBackground = false
            EmulatorBridge.resetKeyStatus()
            refreshSaveStateMetadata()
            _uiState.update { latest -> latest.copy(showMenu = true, isPaused = true) }
            DiscordIntegration.setPaused(true)
            updateCrashContext(launchState = "paused")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    EmulatorBridge.pause()
                } catch (_: Exception) { }
            }
        } else {
            closeMenu()
        }
    }

    private fun closeMenu() {
        pausedForBackground = false
        _uiState.update { latest -> latest.copy(showMenu = false, isPaused = false) }
        DiscordIntegration.setPaused(false)
        updateCrashContext(launchState = "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EmulatorBridge.resume()
            } catch (_: Exception) { }
        }
    }

    fun toggleControlsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.controlsVisible
            preferences.setOverlayShow(newValue)
            _uiState.update { latest -> latest.copy(controlsVisible = newValue) }
        }
    }

    fun saveCurrentGameSettingsProfile() {
        viewModelScope.launch {
            persistRuntimeState({ it })
        }
    }

    private fun refreshKeyboardMouseOverride() {
        val override = activePerGameKey()
            ?.let(perGameSettingsRepository::get)
            ?.keyboardMouse
        _keyboardMouseOverride.value = override
        KeyboardMouseManager.setPerGameOverride(override)
    }

    fun setKeyboardMouseSettings(settings: KeyboardMouseSettings?) {
        viewModelScope.launch {
            val gameKey = activePerGameKey() ?: return@launch
            _keyboardMouseOverride.value = settings
            KeyboardMouseManager.setPerGameOverride(settings)
            val existing = withContext(Dispatchers.IO) { perGameSettingsRepository.get(gameKey) }
            if (settings == null && existing == null) {
                refreshKeyboardMouseOverride()
                return@launch
            }
            val base = existing ?: PerGameSettings(
                gameKey = gameKey,
                gameTitle = resolvePerGameTitle(_uiState.value),
                gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                providedKeys = emptySet()
            )
            val updated = base.withKeyboardMouseSettings(settings)
            runCatching {
                withContext(Dispatchers.IO) {
                    if (settings == null && updated.providedKeys?.isEmpty() == true) {
                        perGameSettingsRepository.delete(gameKey)
                    } else {
                        perGameSettingsRepository.save(updated)
                    }
                }
            }
            val profileActive = withContext(Dispatchers.IO) {
                perGameSettingsRepository.get(gameKey) != null
            }
            _uiState.update { latest -> latest.copy(gameSettingsProfileActive = profileActive) }
            refreshKeyboardMouseOverride()
        }
    }

    fun resetCurrentGameSettingsProfile() {
        viewModelScope.launch {
            lifecycleMutex.withLock {
                if (!isShuttingDown) resetCurrentGameProfile()
            }
        }
    }

    /**
     * Applies an Azahar core option while a game session is running.
     *
     * Core options edited in-game belong to the running game, so they are stored
     * in that game's profile instead of the global core-option store. A change
     * made while the core is live (BIOS only) still persists globally.
     */
    fun setCoreOption(key: String, value: String) {
        val selectedPlatform = activeGamePlatform()
        if (selectedPlatform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)) {
            val option = CoreOptionSchema.options(selectedPlatform.console).find { it.key == key } ?: return
            if (!option.accepts(value)) return
            viewModelScope.launch(Dispatchers.IO) {
                val identity = currentGamePath ?: return@launch
                if (!option.restartRequired && AdditionalConsoleSession.session?.applyOption(key, value) != true) return@launch
                val store = CoreOptionPreferences(getApplication())
                if (!store.setGameOverride(selectedPlatform.console, identity, key, value)) return@launch
                val existing = perGameSettingsRepository.get(identity)
                val coreOptions = store.gameOverrides(selectedPlatform.console, identity)
                val profile = existing?.copy(gamePlatform = selectedPlatform, coreOptions = coreOptions,
                    providedKeys = existing.providedKeys?.plus("coreOptions")) ?: PerGameSettings(
                    gameKey = identity, gameTitle = resolvePerGameTitle(_uiState.value), gamePlatform = selectedPlatform,
                    gameSerial = currentGameSerial.takeIf(String::isNotBlank), coreOptions = coreOptions,
                    providedKeys = setOf("coreOptions"))
                perGameSettingsRepository.save(profile)
                _uiState.update { latest -> latest.copy(gameSettingsProfileActive = true,
                    perGameCoreOptions = store.effectiveOptions(selectedPlatform.console, identity)) }
            }
            return
        }
        if (key == "swap_screen") {
            value.toBooleanStrictOrNull()?.let { swapped -> updateScreenSwap { swapped } }
            return
        }
        if (activeGamePlatform() == GamePlatform.NDS) return
        viewModelScope.launch {
            val gameKey = activePerGameKey()
            if (gameKey == null) {
                NativeApp.setCoreOption(key, value)
                return@launch
            }
            NativeApp.applyCoreOption(key, value)
            val existing = perGameSettingsRepository.get(gameKey)
            val coreOptions = (existing?.coreOptions ?: emptyMap()) + (key to value)
            val profile = existing?.copy(coreOptions = coreOptions)
                ?: PerGameSettings(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(_uiState.value),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    coreOptions = coreOptions,
                    providedKeys = setOf("coreOptions")
                )
            perGameSettingsRepository.save(profile)
            pendingPerGameCoreOptions =
                coreOptions.filterKeys { !AzaharCoreOptions.isManagedKey(it) }
            _uiState.update { latest -> latest.copy(
                gameSettingsProfileActive = true,
                perGameCoreOptions = pendingPerGameCoreOptions
            ) }
        }
    }

    fun setOverlayScale(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayScale(value)
            _uiState.update { latest -> latest.copy(overlayScale = value.coerceIn(50, 150)) }
        }
    }

    fun setOverlayOpacity(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayOpacity(value)
            _uiState.update { latest -> latest.copy(
                overlayOpacity = value.coerceIn(
                    AppPreferences.OVERLAY_OPACITY_MIN,
                    AppPreferences.OVERLAY_OPACITY_MAX
                )
            ) }
        }
    }

    fun setHideOverlayOnGamepad(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHideOverlayOnGamepad(enabled)
            _uiState.update { latest -> latest.copy(hideOverlayOnGamepad = enabled) }
        }
    }

    fun setCompactControls(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setCompactControls(enabled)
            _uiState.update { latest -> latest.copy(compactControls = enabled) }
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setKeepScreenOn(enabled)
            _uiState.update { latest -> latest.copy(keepScreenOn = enabled) }
        }
    }

    fun setStickScale(value: Int) {
        viewModelScope.launch {
            val scaledValue = value.coerceIn(
                AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                AppPreferences.OVERLAY_CONTROL_SCALE_MAX
            )
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            listOf("left_stick", "right_stick").forEach { id ->
                val existing = updatedLayouts[id]
                if (existing != null) {
                    updatedLayouts[id] = existing.copy(scale = scaledValue)
                }
            }
            persistTouchControlsLayout(
                current.copy(
                    stickScale = scaledValue,
                    controlLayouts = updatedLayouts
                )
            )
        }
    }

    fun setLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setLeftStickSensitivity(normalized)
            _uiState.update { latest -> latest.copy(leftStickSensitivity = normalized) }
        }
    }

    fun setRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setRightStickSensitivity(normalized)
            _uiState.update { latest -> latest.copy(rightStickSensitivity = normalized) }
        }
    }

    fun setInvertLeftStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStick(enabled)
            _uiState.update { latest -> latest.copy(invertLeftStick = enabled) }
        }
    }

    fun setInvertRightStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStick(enabled)
            _uiState.update { latest -> latest.copy(invertRightStick = enabled) }
        }
    }

    fun setInvertLeftStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStickHorizontal(enabled)
            _uiState.update { latest -> latest.copy(invertLeftStickHorizontal = enabled) }
        }
    }

    fun setInvertRightStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStickHorizontal(enabled)
            _uiState.update { latest -> latest.copy(invertRightStickHorizontal = enabled) }
        }
    }

    fun setTouchscreenRightStick(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(touchscreenRightStick = enabled) }) {
                preferences.setTouchscreenRightStick(enabled)
            }
        }
    }

    fun setTouchscreenRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
            )
            persistRuntimeState({ it.copy(touchscreenRightStickSensitivity = normalized) }) {
                preferences.setTouchscreenRightStickSensitivity(normalized)
            }
        }
    }

    fun setTouchscreenLeftStick(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(touchscreenLeftStick = enabled) }) {
                preferences.setTouchscreenLeftStick(enabled)
            }
        }
    }

    fun setTouchscreenLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
            )
            persistRuntimeState({ it.copy(touchscreenLeftStickSensitivity = normalized) }) {
                preferences.setTouchscreenLeftStickSensitivity(normalized)
            }
        }
    }

    fun setGamepadStickDeadzone(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(0, 35)
            preferences.setGamepadStickDeadzone(normalized)
            _uiState.update { latest -> latest.copy(gamepadStickDeadzone = normalized) }
        }
    }

    fun setGamepadLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadLeftStickSensitivity(normalized)
            _uiState.update { latest -> latest.copy(gamepadLeftStickSensitivity = normalized) }
        }
    }

    fun setGamepadRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadRightStickSensitivity(normalized)
            _uiState.update { latest -> latest.copy(gamepadRightStickSensitivity = normalized) }
        }
    }

    fun setGamepadRightStickUpToR2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = persistRuntimeState({ it.copy(gamepadRightStickUpToR2 = enabled) }) {
                preferences.setGamepadRightStickUpToR2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadRightStickDownToL2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = persistRuntimeState({ it.copy(gamepadRightStickDownToL2 = enabled) }) {
                preferences.setGamepadRightStickDownToL2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadBindingsByPad(bindingsByPad: Map<Int, Map<String, Int>>) {
        viewModelScope.launch {
            _uiState.update { it.copy(gamepadBindingsByPad = bindingsByPad) }
            GamepadManager.applyPerGameOverrides(
                bindingsByPad = bindingsByPad,
                deadzone = null,
                leftSensitivity = null,
                rightSensitivity = null
            )
        }
    }

    fun toggleLeftInputMode() {
        viewModelScope.launch {
            val current = _uiState.value
            val profile = current.toTouchControlsLayoutProfile()
            val toggled = profile.toggleStick(current.stickToggleTarget)
            persistTouchControlsLayout(current.withTouchControlsLayout(toggled))
        }
    }

    fun setStickToggleTarget(target: Int) {
        val normalized = AppPreferences.normalizeStickToggleTarget(target)
        _uiState.update { latest -> latest.copy(stickToggleTarget = normalized) }
        viewModelScope.launch {
            preferences.setStickToggleTarget(normalized)
        }
    }

    fun updateTouchControlOffset(controlId: String, offset: Pair<Float, Float>) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(offset = offset)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOffsets(offsets: Map<String, Pair<Float, Float>>) {
        if (offsets.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            offsets.forEach { (controlId, offset) ->
                val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
                updatedLayouts[controlId] = control.copy(offset = offset)
            }
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlScale(controlId: String, scale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                scale = scale.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                    AppPreferences.OVERLAY_CONTROL_SCALE_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlWidthScale(controlId: String, widthScale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(widthScale = widthScale.coerceIn(100, 240))
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOpacity(controlId: String, opacity: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                opacity = opacity.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MIN,
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchControlVisible(controlId: String, visible: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(visible = visible)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchStickSurfaceMode(controlId: String, enabled: Boolean) {
        if (controlId != "left_stick" && controlId != "right_stick") return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(surfaceOnly = enabled)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun resetTouchControlsLayout() {
        viewModelScope.launch {
            resetTouchControlsLayoutForCurrentScope()
        }
    }

    fun setCustomTouchControls(library: CustomTouchControlLibrary) {
        viewModelScope.launch {
            val sanitized = library.sanitized()
            val gameKey = activePerGameKey()
            if (gameKey != null) {
                withContext(Dispatchers.IO) {
                    val existing = perGameSettingsRepository.get(gameKey)
                    perGameSettingsRepository.save(
                        existing.withCustomTouchControls(
                            gameKey = gameKey,
                            gameTitle = resolvePerGameTitle(_uiState.value),
                            gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                            library = sanitized
                        )
                    )
                }
                currentCustomTouchControlsProfile = sanitized
                _uiState.update { latest -> latest.copy(
                    customTouchControls = sanitized,
                    gameSettingsProfileActive = true
                ) }
            } else {
                currentCustomTouchControlsProfile = null
                val state = _uiState.value
                preferences.saveConsoleControls(controlsScope(state),
                    ConsoleControlsProfile(state.toTouchControlsLayoutProfile(), sanitized))
                _uiState.update { latest -> latest.copy(customTouchControls = sanitized) }
            }
        }
    }

    fun toggleFpsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.showFps
            persistRuntimeState({ it.copy(showFps = newValue) }) {
                preferences.setShowFps(newValue)
            }
        }
    }

    fun setFpsOverlayMode(mode: Int) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(fpsOverlayMode = mode) }) {
                preferences.setFpsOverlayMode(mode)
            }
        }
    }

    fun setFpsOverlayCorner(corner: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayCorner(corner)
            _uiState.update { latest -> latest.copy(fpsOverlayCorner = corner) }
        }
    }

    fun setRenderer(renderer: Int) {
        viewModelScope.launch {
            val isDs = activeGamePlatform() == GamePlatform.NDS
            val normalized = if (isDs) DsSettingsPolicy.normalizeRenderer(renderer) else renderer
            if (!EmulatorBridge.setRenderer(normalized)) {
                if (isDs && !DsSession.isActive) {
                    _uiState.update { it.withRuntimeFailure(RuntimeFailure(
                        "DS renderer restart and rollback failed. The recovery checkpoint was retained in the app cache."
                    )) }
                }
                return@launch
            }
            // Only Azahar's session recreation drops its core-option overrides.
            if (!isDs) pendingPerGameCoreOptions.forEach { (key, value) -> NativeApp.applyCoreOption(key, value) }
            persistRuntimeState({ it.copy(renderer = normalized) }) {
                if (isDs) preferences.setDsRenderer(normalized) else preferences.setRenderer(normalized)
            }
            EmulatorBridge.setSwapScreens(_uiState.value.swapScreens)
            updateCrashContext()
        }
    }

    fun setUpscale(upscale: Float) {
        viewModelScope.launch {
            val isDs = activeGamePlatform() == GamePlatform.NDS
            val normalizedUpscale = if (isDs) DsSettingsPolicy.normalizeUpscaleMultiplier(upscale)
                else normalizeUpscale(upscale)
            persistRuntimeState({ it.copy(upscale = normalizedUpscale) }) {
                if (isDs) preferences.setDsUpscaleMultiplier(normalizedUpscale)
                else preferences.setUpscaleMultiplier(normalizedUpscale)
            }
            EmulatorBridge.setUpscaleMultiplier(normalizedUpscale)
            // Refresh the HUD immediately instead of waiting for the next 250 ms sample.
            pollNativePerformanceMetrics()
            updateCrashContext()
        }
    }

    fun toggleScreenSwap() = updateScreenSwap { !it }

    private fun updateScreenSwap(transform: (Boolean) -> Boolean) {
        viewModelScope.launch {
            lifecycleMutex.withLock {
                val gameKey = activePerGameKey() ?: return@withLock
                if (isShuttingDown) return@withLock
                val swapped = transform(_uiState.value.swapScreens)
                val profile = perGameSettingsRepository.get(gameKey) ?: PerGameSettings(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(_uiState.value),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    providedKeys = emptySet()
                )
                withContext(Dispatchers.IO) { perGameSettingsRepository.save(profile.withScreenSwap(swapped)) }
                _uiState.update { latest -> latest.copy(
                    swapScreens = swapped,
                    gameSettingsProfileActive = true,
                    perGameCoreOptions = latest.perGameCoreOptions - "swap_screen"
                ) }
                pendingPerGameCoreOptions = pendingPerGameCoreOptions - "swap_screen"
                EmulatorBridge.setSwapScreens(swapped)
            }
        }
    }

    fun setAspectRatio(value: Int) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(aspectRatio = value) }) {
                preferences.setAspectRatio(value)
            }
            EmulatorBridge.setAspectRatio(value)
            updateCrashContext()
        }
    }


    fun setDisplayCrop(value: DisplayCrop) {
        viewModelScope.launch {
            val crop = value.sanitized()
            persistRuntimeState({ it.copy(displayCrop = crop) }) {
                preferences.setDisplayCrop(crop)
            }
            EmulatorBridge.setDisplayCrop(crop)
            updateCrashContext()
        }
    }

    fun setFpsOverlayScale(scale: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayScale(scale)
        }
    }

    fun setFpsOverlayMetrics(metrics: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayMetrics(metrics)
        }
    }

    fun setEnableCheats(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(enableCheats = enabled) }) {
                preferences.setEnableCheats(enabled)
            }
            if (activeGamePlatform() == GamePlatform.N3DS)
                CoreOptionStore.set("cheats_enabled", enabled.toString())
            withContext(Dispatchers.IO) {
                syncCheatsForCurrentGame()
            }
            updateCrashContext()
        }
    }

    fun setCheatEnabled(blockId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id == blockId) block.copy(enabled = enabled) else block
            }
            // Only this ID is written, so toggles made in the cheat manager
            // (which share the same state file) are preserved.
            cheatRepository.setBlockEnabled(gameKey, blockId, enabled)
            persistRuntimeState({ it.copy(
                availableCheats = updatedBlocks,
                enableCheats = true
            ) }) {
                preferences.setEnableCheats(true)
            }
            syncCheatsForCurrentGame(gameKey)
            if (activeGamePlatform() == GamePlatform.N3DS)
                CoreOptionStore.set("cheats_enabled", "true")
        }
    }

    /** Enables or disables every cheat in a group (category) at once. */
    fun setCheatGroupEnabled(blockIds: Collection<String>, enabled: Boolean) {
        if (blockIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val idSet = blockIds.toSet()
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id in idSet) block.copy(enabled = enabled) else block
            }
            cheatRepository.setBlocksEnabled(gameKey, idSet, enabled)
            persistRuntimeState({ it.copy(
                availableCheats = updatedBlocks,
                enableCheats = true
            ) }) {
                preferences.setEnableCheats(true)
            }
            syncCheatsForCurrentGame(gameKey)
            if (activeGamePlatform() == GamePlatform.N3DS)
                CoreOptionStore.set("cheats_enabled", "true")
        }
    }

    /**
     * Re-reads the installed cheats from storage so changes made in the cheat
     * manager (or a freshly installed pack) are reflected in the in-game menu.
     */
    fun refreshAvailableCheats() {
        viewModelScope.launch(Dispatchers.IO) {
            if (activeGamePlatform() != GamePlatform.N3DS) {
                refreshAvailableTypedCheats()
                return@launch
            }
            val path = currentGamePath
            val metadata = path?.takeIf { it.isNotBlank() }?.let { EmulatorBridge.getGameMetadata(it) }
            val serial = currentGameSerial.takeIf { it.isNotBlank() }
                ?: metadata?.serial?.takeIf { it.isNotBlank() }
                .orEmpty()
            val crc = currentGameCrc.takeIf { it.isNotBlank() }
                ?: metadata?.serialWithCrc.extractCrc()
                .orEmpty()
            val keys = linkedSetOf<String>()
            metadata?.let { keys.addAll(cheatLookupKeys(it)) }
            if (serial.isNotBlank() && crc.isNotBlank()) keys.add("${serial}_$crc")
            if (crc.isNotBlank()) keys.add(crc)
            if (serial.isNotBlank()) keys.add(serial)
            val config = cheatRepository.getGameConfig(
                gameKeys = keys.toList(),
                serial = serial,
                crc = crc.takeIf { it.isNotBlank() }
            ) ?: return@launch
            _uiState.update { latest -> latest.copy(
                cheatsGameKey = config.gameKey,
                availableCheats = config.blocks
            ) }
        }
    }

    fun setFrameSkip(value: Int) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(frameSkip = value) }) {
                preferences.setFrameSkip(value)
            }
            EmulatorBridge.setFrameSkip(value)
            updateCrashContext()
        }
    }


    fun toggleFrameLimit() {
        setFrameLimitEnabled(!_uiState.value.frameLimitEnabled)
    }

    fun setFrameLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(frameLimitEnabled = enabled) }) {
                preferences.setFrameLimitEnabled(enabled)
            }
            EmulatorBridge.setFrameLimitEnabled(enabled)
            updateCrashContext()
        }
    }

    private fun isShadeBoostActive(
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int
    ): Boolean {
        return brightness != 50 || contrast != 50 || saturation != 50 || gamma != 50
    }

    fun setAudioVolume(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX)
            persistRuntimeState({ it.copy(audioVolume = clamped) }) {
                preferences.setAudioVolume(clamped)
            }
            CoreOptionStore.set("volume", clamped.toString())
            updateCrashContext()
        }
    }

    fun setAudioMuted(muted: Boolean) {
        viewModelScope.launch {
            persistRuntimeState({ it.copy(audioMuted = muted) }) {
                preferences.setAudioMuted(muted)
            }
            CoreOptionStore.set("audio_muted", muted.toString())
            updateCrashContext()
        }
    }

    private fun applyOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot) {
        _uiState.update { latest ->
            val overlayState = latest.withOverlayLayoutSnapshot(snapshot)
            currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
        }
    }

    private fun controlsScope(state: EmulationUiState = _uiState.value): ConsoleControlsScope {
        val platform = state.gamePlatform.console
        val actual = AdditionalConsoleSession.activeWiiControllerProfile.takeIf {
            AdditionalConsoleSession.activeIdentityPath == currentGamePath
        }
        val profile = actual ?: state.perGameCoreOptions["wii_profile"]
            ?: CoreOptionPreferences(getApplication()).effectiveOptions(platform, currentGamePath)["wii_profile"]
            ?: "nunchuk"
        return ConsoleControlsScope(platform, profile)
    }

    private fun EmulationUiState.withOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot): EmulationUiState {
        val scoped = snapshot.forConsoleControls(controlsScope(this))
        return copy(
            overlayScale = snapshot.overlayScale,
            overlayOpacity = snapshot.overlayOpacity,
            hideOverlayOnGamepad = snapshot.hideOverlayOnGamepad,
            dpadOffset = scoped.dpadOffset,
            lstickOffset = scoped.lstickOffset,
            rstickOffset = scoped.rstickOffset,
            actionOffset = scoped.actionOffset,
            lbtnOffset = scoped.lbtnOffset,
            rbtnOffset = scoped.rbtnOffset,
            centerOffset = scoped.centerOffset,
            stickScale = scoped.stickScale,
            leftStickSensitivity = snapshot.leftStickSensitivity,
            rightStickSensitivity = snapshot.rightStickSensitivity,
            invertLeftStick = snapshot.invertLeftStick,
            invertRightStick = snapshot.invertRightStick,
            invertLeftStickHorizontal = snapshot.invertLeftStickHorizontal,
            invertRightStickHorizontal = snapshot.invertRightStickHorizontal,
            stickSurfaceMode = snapshot.stickSurfaceMode,
            controlLayouts = scoped.controlLayouts,
            customTouchControls = currentCustomTouchControlsProfile ?: scoped.customTouchControls
        )
    }

    private fun EmulationUiState.withTouchControlsLayout(profile: TouchControlsLayoutProfile): EmulationUiState {
        return copy(
            dpadOffset = profile.dpadOffset,
            lstickOffset = profile.lstickOffset,
            rstickOffset = profile.rstickOffset,
            actionOffset = profile.actionOffset,
            lbtnOffset = profile.lbtnOffset,
            rbtnOffset = profile.rbtnOffset,
            centerOffset = profile.centerOffset,
            stickScale = profile.stickScale,
            controlLayouts = profile.controlLayouts
        )
    }

    private fun EmulationUiState.toTouchControlsLayoutProfile(): TouchControlsLayoutProfile {
        return TouchControlsLayoutProfile(
            dpadOffset = dpadOffset,
            lstickOffset = lstickOffset,
            rstickOffset = rstickOffset,
            actionOffset = actionOffset,
            lbtnOffset = lbtnOffset,
            rbtnOffset = rbtnOffset,
            centerOffset = centerOffset,
            stickScale = stickScale,
            controlLayouts = controlLayouts
        )
    }

    private fun currentGameSubtitle(): String = buildList {
        currentGameSerial.takeIf { it.isNotBlank() }?.let(::add)
        currentGameRegionLabel.takeIf { it.isNotBlank() }?.let(::add)
        currentGameCrc.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  /  ")

    private fun resolveRegionLabel(path: String): String {
        return filenameRegionLabel(path).orEmpty()
    }

    private fun filenameRegionLabel(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = if (path.startsWith("content://")) {
            DocumentPathResolver.getDisplayName(getApplication(), path)
        } else {
            File(path).name
        }
        val lower = name.lowercase()
        return when {
            "usa" in lower || "(u)" in lower || "ntsc-u" in lower || "us " in lower -> "NTSC"
            "japan" in lower || "jpn" in lower || "(j)" in lower || "ntsc-j" in lower -> "NTSC"
            "europe" in lower || "eur" in lower || "(e)" in lower || "pal" in lower -> "PAL"
            else -> null
        }
    }

    private fun activePerGameKey(): String? = currentGamePath?.takeIf { it.isNotBlank() }

    private fun activeGamePlatform(): GamePlatform = GamePlatform.forPath(currentGamePath.orEmpty()) ?: _uiState.value.gamePlatform

    private fun resolvePerGameTitle(state: EmulationUiState): String {
        return state.currentGameTitle
            .takeIf { it.isNotBlank() && it != "HOME Menu" }
            ?: currentGameTitle.takeIf { it.isNotBlank() && it != "HOME Menu" }
            ?: activePerGameKey()?.let { DocumentPathResolver.getDisplayName(getApplication(), it).substringBeforeLast('.') }
            ?: "Unknown Game"
    }

    private suspend fun persistGlobalTouchControlsLayout(state: EmulationUiState) {
        preferences.saveConsoleControls(controlsScope(state),
            ConsoleControlsProfile(state.toTouchControlsLayoutProfile(), state.customTouchControls))
    }

    private suspend fun persistTouchControlsLayout(updatedState: EmulationUiState): EmulationUiState {
        val gameKey = activePerGameKey()
        val layout = updatedState.toTouchControlsLayoutProfile()
        return if (gameKey != null) {
            val existing = perGameSettingsRepository.get(gameKey)
            perGameSettingsRepository.save(
                existing.withTouchControlsLayout(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(updatedState),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    layout = layout
                )
            )
            if (activePerGameKey() != gameKey) return _uiState.value
            currentTouchControlsLayoutProfile = layout
            _uiState.updateAndGet { it.withTouchControlsLayout(layout).copy(gameSettingsProfileActive = true) }
        } else {
            persistGlobalTouchControlsLayout(updatedState)
            if (activePerGameKey() != gameKey) return _uiState.value
            currentTouchControlsLayoutProfile = null
            _uiState.updateAndGet { it.withTouchControlsLayout(layout) }
        }
    }

    private suspend fun resetTouchControlsLayoutForCurrentScope() {
        val gameKey = activePerGameKey()
        if (gameKey == null) {
            currentTouchControlsLayoutProfile = null
            currentCustomTouchControlsProfile = null
            preferences.resetConsoleControls(controlsScope())
            val overlay = preferences.overlayLayoutSnapshot.first()
            _uiState.update { it.withOverlayLayoutSnapshot(overlay) }
            return
        }

        val existing = perGameSettingsRepository.get(gameKey)
        if (existing != null) {
            val updated = existing.withoutTouchControlsLayout()
            if (updated == null) {
                perGameSettingsRepository.delete(gameKey)
            } else {
                perGameSettingsRepository.save(updated)
            }
        }

        currentTouchControlsLayoutProfile = null
        currentCustomTouchControlsProfile = null
        val profileStillActive = perGameSettingsRepository.get(gameKey) != null
        val overlay = preferences.overlayLayoutSnapshot.first()
        _uiState.update { it.withOverlayLayoutSnapshot(overlay).copy(
                gameSettingsProfileActive = profileStillActive
            ) }
    }

    private suspend fun persistRuntimeState(
        transform: (EmulationUiState) -> EmulationUiState,
        persistGlobal: suspend () -> Unit = {}
    ): EmulationUiState {
        val gameKey = activePerGameKey()
        val updatedState = transform(_uiState.value)
        return if (gameKey != null) {
            val existingProfile = perGameSettingsRepository.get(gameKey)
            val touchControlsLayout = existingProfile?.touchControlsLayout ?: currentTouchControlsLayoutProfile
            val customTouchControls = existingProfile?.customTouchControls
                ?: currentCustomTouchControlsProfile
            val runtimeProfile = updatedState.toPerGameSettings(
                gameKey = gameKey,
                gameTitle = resolvePerGameTitle(updatedState),
                gameSerial = currentGameSerial.takeIf { it.isNotBlank() }
            )
            val visualStyleOverride = existingProfile?.touchControlVisualStyle
                ?: runtimeProfile.touchControlVisualStyle
            val pressEffectOverride = existingProfile?.touchControlPressEffect
                ?: runtimeProfile.touchControlPressEffect
            val visualOverrideKeys = buildSet {
                if (visualStyleOverride != null) add("touchControlVisualStyle")
                if (pressEffectOverride != null) add("touchControlPressEffect")
            }
            val audioOverrideKeys = existingProfile?.let { profile ->
                if (profile.providedKeys == null) {
                    PER_GAME_AUDIO_KEYS
                } else {
                    profile.providedKeys.intersect(PER_GAME_AUDIO_KEYS)
                }
            }.orEmpty()
            val providedKeys = when {
                runtimeProfile.providedKeys == null -> null
                else -> buildSet<String> {
                    addAll(runtimeProfile.providedKeys)
                    addAll(visualOverrideKeys)
                    addAll(audioOverrideKeys)
                    if (existingProfile?.swapScreens != null) add("swapScreens")
                    if (existingProfile?.keyboardMouse != null) add("keyboardMouse")
                    if (touchControlsLayout != null) add(PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY)
                    if (customTouchControls != null) add(PER_GAME_CUSTOM_TOUCH_CONTROLS_KEY)
                }
            }
            perGameSettingsRepository.save(
                runtimeProfile.copy(
                    swapScreens = existingProfile?.swapScreens,
                    keyboardMouse = existingProfile?.keyboardMouse,
                    touchControlsLayout = touchControlsLayout,
                    customTouchControls = customTouchControls,
                    touchControlVisualStyle = visualStyleOverride,
                    touchControlPressEffect = pressEffectOverride,
                    coreOptions = existingProfile?.coreOptions ?: runtimeProfile.coreOptions,
                    mediatekAngleOpenGl = existingProfile?.mediatekAngleOpenGl ?: runtimeProfile.mediatekAngleOpenGl,
                    shaderChainOverrideEnabled = existingProfile?.shaderChainOverrideEnabled,
                    shaderChainPreset = existingProfile?.shaderChainPreset.orEmpty(),
                    audioVolume = if ("audioVolume" in audioOverrideKeys) {
                        existingProfile?.audioVolume ?: runtimeProfile.audioVolume
                    } else {
                        runtimeProfile.audioVolume
                    },
                    audioMuted = if ("audioMuted" in audioOverrideKeys) {
                        existingProfile?.audioMuted ?: runtimeProfile.audioMuted
                    } else {
                        runtimeProfile.audioMuted
                    },
                    audioOutputLatencyMs = if ("audioOutputLatencyMs" in audioOverrideKeys) {
                        existingProfile?.audioOutputLatencyMs ?: runtimeProfile.audioOutputLatencyMs
                    } else {
                        runtimeProfile.audioOutputLatencyMs
                    },
                    audioMinimalOutputLatency = if ("audioMinimalOutputLatency" in audioOverrideKeys) {
                        existingProfile?.audioMinimalOutputLatency ?: runtimeProfile.audioMinimalOutputLatency
                    } else {
                        runtimeProfile.audioMinimalOutputLatency
                    },
                    providedKeys = providedKeys
                )
            )
            if (activePerGameKey() != gameKey) return _uiState.value
            val finalState = _uiState.updateAndGet { transform(it).copy(gameSettingsProfileActive = true) }
            syncNativePerformanceOverlayState(finalState)
            finalState
        } else {
            persistGlobal()
            if (activePerGameKey() != gameKey) return _uiState.value
            val finalState = _uiState.updateAndGet(transform)
            syncNativePerformanceOverlayState(finalState)
            finalState
        }
    }

    private fun syncCurrentGameProfileMetadata() {
        val gameKey = activePerGameKey() ?: return
        val profile = perGameSettingsRepository.get(gameKey) ?: return
        val resolvedTitle = resolvePerGameTitle(_uiState.value)
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        if (profile.gameTitle != resolvedTitle || profile.gameSerial != serial) {
            perGameSettingsRepository.save(
                profile.copy(
                    gameTitle = resolvedTitle,
                    gameSerial = serial
                )
            )
        }
    }

    private suspend fun resetCurrentGameProfile() {
        val gameKey = activePerGameKey() ?: return
        val platform = activeGamePlatform()
        val settings = preferences.settingsSnapshot.first()
        if (platform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)) {
            val options = CoreOptionPreferences(getApplication())
            val previous = options.effectiveOptions(platform.console, gameKey)
            val global = CoreOptionSchema.resolve(platform.console, options.globalOptions(platform.console))
            val changed = mutableListOf<String>()
            val guest = AdditionalConsoleSession.session
            for (definition in CoreOptionSchema.options(platform.console).filterNot { it.restartRequired }) {
                val value = global.getValue(definition.key)
                if (value == previous[definition.key]) continue
                if (guest != null && !guest.applyOption(definition.key, value)) {
                    for (key in changed.asReversed()) previous[key]?.let { guest.applyOption(key, it) }
                    return
                }
                changed += definition.key
            }
            options.resetGame(platform.console, gameKey)
            perGameSettingsRepository.delete(gameKey)
            currentTouchControlsLayoutProfile = null
            currentCustomTouchControlsProfile = null
            pendingPerGameCoreOptions = emptyMap()
            GamepadManager.clearPerGameOverrides()
            refreshKeyboardMouseOverride()
            val actualRenderer = _uiState.value.renderer
            val overlay = preferences.overlayLayoutSnapshot.first()
            _uiState.update { it.withGlobalRuntimeDefaults(settings, platform, false)
                .withOverlayLayoutSnapshot(overlay).copy(
                    renderer = actualRenderer, upscale = global["resolution"]?.toFloatOrNull() ?: 1f,
                    gameSettingsProfileActive = false, perGameCoreOptions = global) }
            syncGamepadRuntimeSettings(_uiState.value)
            syncNativePerformanceOverlayState(_uiState.value)
            updateCrashContext()
            return
        }
        val defaults = settings.forPlatform(platform)
        val config = loadLaunchConfig(includeProfile = false, globalSettings = settings)
        val layout = preferences.overlayLayoutSnapshot.first()
        if (activePerGameKey() != gameKey || isShuttingDown) return

        // A failed renderer restart must retain the saved profile and live UI.
        // Use the normal state-preserving renderer path before deleting anything.
        if (_uiState.value.renderer != defaults.renderer && !EmulatorBridge.setRenderer(defaults.renderer)) {
            if (platform == GamePlatform.NDS && !DsSession.isActive) {
                _uiState.update { it.withRuntimeFailure(RuntimeFailure(
                    "DS renderer restart and rollback failed. The recovery checkpoint was retained in the app cache."
                )) }
            }
            return
        }
        EmulatorBridge.setUpscaleMultiplier(defaults.upscaleMultiplier)
        if (platform == GamePlatform.N3DS) {
            EmulatorBridge.applyRuntimeConfig(
                emulatorDataPath = config.emulatorDataPath,
                gamePath = gameKey,
                renderer = config.renderer,
                upscaleMultiplier = config.upscaleMultiplier,
                gpuHardwareProfile = config.gpuHardwareProfile,
                mediatekAngleOpenGl = config.mediatekAngleOpenGl,
                aspectRatio = config.aspectRatio,
                displayCrop = config.displayCrop,
                audioVolume = config.audioVolume,
                audioFastForwardVolume = config.audioFastForwardVolume,
                audioMuted = config.audioMuted,
                audioOutputLatencyMs = config.audioOutputLatencyMs,
                audioMinimalOutputLatency = config.audioMinimalOutputLatency,
                frameSkip = config.frameSkip,
                frameLimitEnabled = config.frameLimitEnabled,
                vSyncEnabled = config.vSyncEnabled,
                fastForwardSpeed = config.fastForwardSpeed,
                shaderChainEnabled = config.shaderChainEnabled,
                shaderChainPreset = config.shaderChainPreset,
                textureReplacementsEnabled = config.textureReplacementsEnabled,
                enableCheats = config.enableCheats,
                layoutOption = config.layoutOption,
                swapScreens = config.swapScreens
            )
        }
        perGameSettingsRepository.delete(gameKey)
        currentTouchControlsLayoutProfile = null
        currentCustomTouchControlsProfile = null
        GamepadManager.clearPerGameOverrides()
        refreshKeyboardMouseOverride()
        // Restore the global/core-default value of any option that was overridden
        // only by this game, so the live core matches the reset profile.
        if (platform == GamePlatform.N3DS) pendingPerGameCoreOptions.keys.forEach { key ->
            NativeApp.getCoreOption(key)?.let { baseValue -> NativeApp.applyCoreOption(key, baseValue) }
        }
        pendingPerGameCoreOptions = emptyMap()
        val resetSwap = config.swapScreens ?: false
        _uiState.update { it
            .withGlobalRuntimeDefaults(settings, platform, resetSwap)
            .withOverlayLayoutSnapshot(layout) }
        EmulatorBridge.setSwapScreens(resetSwap)
        syncNativePerformanceOverlayState(_uiState.value)
        syncGamepadRuntimeSettings(_uiState.value)
        if (platform == GamePlatform.N3DS) withContext(Dispatchers.IO) { syncCheatsForCurrentGame() }
        updateCrashContext()
    }

    private suspend fun loadLaunchConfig(includeProfile: Boolean = true, globalSettings: SettingsSnapshot? = null): EmulationLaunchConfig {
        val profile = if (includeProfile) activePerGameKey()?.let(perGameSettingsRepository::get) else null
        val settings = (globalSettings ?: preferences.settingsSnapshot.first()).forPlatform(activeGamePlatform())
        val secondScreenMode = preferences.secondScreenMode.first()
        val secondScreenSwap = preferences.secondScreenSwap.first()
        val detachedSecondScreen = secondScreenMode == AppPreferences.SECOND_SCREEN_MODE_PANEL ||
            secondScreenMode == AppPreferences.SECOND_SCREEN_MODE_FLOATING
        val fallbackSwap = when {
            activeGamePlatform() == GamePlatform.NDS -> false
            detachedSecondScreen -> secondScreenSwap
            else -> CoreOptionStore.value("swap_screen")?.toBooleanStrictOrNull() ?: false
        }
        return EmulationLaunchConfig(
            emulatorDataPath = settings.emulatorDataPath,
            renderer = settings.renderer,
            upscaleMultiplier = settings.upscaleMultiplier,
            gpuHardwareProfile = settings.gpuHardwareProfile,
            mediatekAngleOpenGl = settings.mediatekAngleOpenGl,
            aspectRatio = settings.aspectRatio,
            displayCrop = settings.displayCrop,
            audioVolume = settings.audioVolume,
            audioFastForwardVolume = settings.audioFastForwardVolume,
            audioMuted = settings.audioMuted,
            audioOutputLatencyMs = settings.audioOutputLatencyMs,
            audioMinimalOutputLatency = settings.audioMinimalOutputLatency,
            frameSkip = settings.frameSkip,
            frameLimitEnabled = settings.frameLimitEnabled,
            vSyncEnabled = settings.vSyncEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            targetFps = settings.targetFps,
            shaderChainEnabled = settings.shaderChainEnabled,
            shaderChainPreset = settings.shaderChainPreset,
            textureReplacementsEnabled = settings.textureReplacementsEnabled,
            enableCheats = settings.enableCheats,
            layoutOption = if (detachedSecondScreen) AppPreferences.SECOND_SCREEN_PANEL_LAYOUT else -1,
            swapScreens = profile?.resolveScreenSwap(fallbackSwap) ?: fallbackSwap
        ).applyProfile(profile).let { config ->
            if (activeGamePlatform() == GamePlatform.NDS) config.copy(
                renderer = DsSettingsPolicy.normalizeRenderer(config.renderer),
                upscaleMultiplier = DsSettingsPolicy.normalizeUpscaleMultiplier(config.upscaleMultiplier)
            ) else if (activeGamePlatform() != GamePlatform.N3DS) {
                val platform = activeGamePlatform()
                val options = CoreOptionPreferences(getApplication()).effectiveOptions(platform.console, currentGamePath)
                config.copy(renderer = if (platform in setOf(GamePlatform.GAMECUBE, GamePlatform.WII))
                    (if (options["graphics_backend"] == "opengl") 12 else 14) else 13,
                    upscaleMultiplier = options["resolution"]?.toFloatOrNull() ?: 1f,
                    swapScreens = false, shaderChainEnabled = false)
            } else config
        }
    }

    private suspend fun loadLiveRuntimeSnapshot(): LiveRuntimeSnapshot {
        val profile = activePerGameKey()?.let(perGameSettingsRepository::get)
        val settings = preferences.settingsSnapshot.first().forPlatform(activeGamePlatform())
        return LiveRuntimeSnapshot(
            showFps = settings.showFps,
            fpsOverlayMode = settings.fpsOverlayMode,
            confirmSaveLoadActions = settings.confirmSaveLoadActions,
            backButtonExitsGame = settings.backButtonExitsGame,
            renderer = settings.renderer,
            upscale = settings.upscaleMultiplier,
            aspectRatio = settings.aspectRatio,
            displayCrop = settings.displayCrop,
            frameSkip = settings.frameSkip,
            frameLimitEnabled = settings.frameLimitEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            targetFps = settings.targetFps,
            enableCheats = settings.enableCheats,
            audioVolume = settings.audioVolume,
            audioMuted = settings.audioMuted,
            racingMode = settings.racingMode,
            stickyButtons = settings.stickyButtons,
            touchscreenLeftStick = settings.touchscreenLeftStick,
            touchscreenLeftStickSensitivity = settings.touchscreenLeftStickSensitivity,
            touchscreenRightStick = settings.touchscreenRightStick,
            touchscreenRightStickSensitivity = settings.touchscreenRightStickSensitivity,
            touchHaptics = settings.touchHaptics,
            touchHapticsPreset = settings.touchHapticsPreset,
            touchHapticsStrength = settings.touchHapticsStrength,
            touchControlVisualStyle = settings.touchControlVisualStyle,
            touchControlPressEffect = settings.touchControlPressEffect,
            gyroMode = settings.gyroMode,
            gyroSensitivity = settings.gyroSensitivity,
            gyroSmoothing = settings.gyroSmoothing,
            gyroInvertX = settings.gyroInvertX,
            gyroInvertY = settings.gyroInvertY,
            gamepadRightStickUpToR2 = settings.gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = settings.gamepadRightStickDownToL2,
            gamepadButtonHaptics = settings.gamepadButtonHaptics,
            gamepadStickDeadzone = settings.gamepadStickDeadzone,
            gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity,
            gamepadBindingsByPad = settings.gamepadBindingsByPad,
            autoSaveOnExit = false,
            autoLoadOnStart = false
        ).applyProfile(profile).let { runtime ->
            if (activeGamePlatform() == GamePlatform.NDS) runtime.copy(
                renderer = DsSettingsPolicy.normalizeRenderer(runtime.renderer),
                upscale = DsSettingsPolicy.normalizeUpscaleMultiplier(runtime.upscale)
            ) else runtime
        }
    }

    private fun EmulationLaunchConfig.applyProfile(profile: PerGameSettings?): EmulationLaunchConfig {
        if (profile == null) return this
        val resolvedShaderChain = profile.resolveShaderChain(
            globalEnabled = shaderChainEnabled,
            globalPreset = shaderChainPreset
        )
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            renderer = pick("renderer", renderer) { renderer },
            mediatekAngleOpenGl = pick("mediatekAngleOpenGl", mediatekAngleOpenGl) { mediatekAngleOpenGl },
            upscaleMultiplier = pick("upscaleMultiplier", upscaleMultiplier) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            frameSkip = pick("frameSkip", frameSkip) { frameSkip },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            shaderChainEnabled = resolvedShaderChain.enabled,
            shaderChainPreset = resolvedShaderChain.preset,
            enableCheats = pick("enableCheats", enableCheats) { enableCheats },
            audioVolume = pick("audioVolume", audioVolume) { audioVolume },
            audioMuted = pick("audioMuted", audioMuted) { audioMuted },
            audioOutputLatencyMs = pick("audioOutputLatencyMs", audioOutputLatencyMs) { audioOutputLatencyMs },
            audioMinimalOutputLatency = pick("audioMinimalOutputLatency", audioMinimalOutputLatency) {
                audioMinimalOutputLatency
            }
        )
    }

    private fun LiveRuntimeSnapshot.applyProfile(profile: PerGameSettings?): LiveRuntimeSnapshot {
        if (profile == null) return this
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            showFps = pick("showFps", showFps) { showFps },
            fpsOverlayMode = pick("fpsOverlayMode", fpsOverlayMode) { fpsOverlayMode },
            racingMode = pick("racingMode", racingMode) { racingMode },
            stickyButtons = pick("stickyButtons", stickyButtons) { stickyButtons },
            touchscreenLeftStick = pick("touchscreenLeftStick", touchscreenLeftStick) { touchscreenLeftStick },
            touchscreenLeftStickSensitivity = pick(
                "touchscreenLeftStickSensitivity",
                touchscreenLeftStickSensitivity
            ) { touchscreenLeftStickSensitivity },
            touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick) { touchscreenRightStick },
            touchscreenRightStickSensitivity = pick(
                "touchscreenRightStickSensitivity",
                touchscreenRightStickSensitivity
            ) { touchscreenRightStickSensitivity },
            touchHaptics = pick("touchHaptics", touchHaptics) { touchHaptics },
            touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset) { touchHapticsPreset },
            touchHapticsStrength = touchHapticsStrength,
            touchControlVisualStyle = profile.touchControlVisualStyle ?: touchControlVisualStyle,
            touchControlPressEffect = profile.touchControlPressEffect ?: touchControlPressEffect,
            gyroMode = pick("gyroMode", gyroMode) { gyroMode },
            gyroSensitivity = pick("gyroSensitivity", gyroSensitivity) { gyroSensitivity },
            gyroSmoothing = pick("gyroSmoothing", gyroSmoothing) { gyroSmoothing },
            gyroInvertX = pick("gyroInvertX", gyroInvertX) { gyroInvertX },
            gyroInvertY = pick("gyroInvertY", gyroInvertY) { gyroInvertY },
            gamepadRightStickUpToR2 = pick("gamepadRightStickUpToR2", gamepadRightStickUpToR2) { gamepadRightStickUpToR2 },
            gamepadRightStickDownToL2 = pick("gamepadRightStickDownToL2", gamepadRightStickDownToL2) {
                gamepadRightStickDownToL2
            },
            gamepadButtonHaptics = pick("gamepadButtonHaptics", gamepadButtonHaptics) { gamepadButtonHaptics },
            gamepadStickDeadzone = pick("gamepadStickDeadzone", gamepadStickDeadzone) { gamepadStickDeadzone },
            gamepadLeftStickSensitivity = pick("gamepadLeftStickSensitivity", gamepadLeftStickSensitivity) {
                gamepadLeftStickSensitivity
            },
            gamepadRightStickSensitivity = pick("gamepadRightStickSensitivity", gamepadRightStickSensitivity) {
                gamepadRightStickSensitivity
            },
            gamepadBindingsByPad = if (profile.providedKeys == null || "gamepadBindingsByPad" in profile.providedKeys) {
                profile.gamepadBindingsByPad
            } else {
                gamepadBindingsByPad
            },
            autoSaveOnExit = pick("autoSaveOnExit", autoSaveOnExit) { autoSaveOnExit },
            autoLoadOnStart = pick("autoLoadOnStart", autoLoadOnStart) { autoLoadOnStart },
            renderer = pick("renderer", renderer) { renderer },
            upscale = pick("upscaleMultiplier", upscale) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            frameSkip = pick("frameSkip", frameSkip) { frameSkip },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            enableCheats = pick("enableCheats", enableCheats) { enableCheats },
            audioVolume = pick("audioVolume", audioVolume) { audioVolume },
            audioMuted = pick("audioMuted", audioMuted) { audioMuted }
        )
    }

    private suspend fun EmulationUiState.toPerGameSettings(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?
    ): PerGameSettings {
        val settings = preferences.settingsSnapshot.first().forPlatform(activeGamePlatform())
        val profile = buildPerGameSettingsProfile(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            settings = settings
        )
        val providedKeys = computeProvidedKeys(settings, profile)
        return profile.copy(providedKeys = providedKeys)
    }

    private fun EmulationUiState.buildPerGameSettingsProfile(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?,
        settings: SettingsSnapshot
    ): PerGameSettings {
        return PerGameSettings(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            gamePlatform = activeGamePlatform(),
            renderer = renderer,
            upscaleMultiplier = upscale,
            aspectRatio = aspectRatio,
            displayCrop = displayCrop,
            showFps = showFps,
            fpsOverlayMode = fpsOverlayMode,
            enableCheats = enableCheats,
            frameSkip = frameSkip,
            frameLimitEnabled = frameLimitEnabled,
            racingMode = racingMode,
            stickyButtons = stickyButtons,
            touchscreenLeftStick = touchscreenLeftStick,
            touchscreenLeftStickSensitivity = touchscreenLeftStickSensitivity,
            touchscreenRightStick = touchscreenRightStick,
            touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
            touchHaptics = touchHaptics,
            touchHapticsPreset = touchHapticsPreset,
            touchControlVisualStyle = touchControlVisualStyle.takeIf { it != settings.touchControlVisualStyle },
            touchControlPressEffect = touchControlPressEffect.takeIf { it != settings.touchControlPressEffect },
            gyroMode = gyroMode,
            gyroSensitivity = gyroSensitivity,
            gyroSmoothing = gyroSmoothing,
            gyroInvertX = gyroInvertX,
            gyroInvertY = gyroInvertY,
            gamepadRightStickUpToR2 = gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = gamepadRightStickDownToL2,
            gamepadButtonHaptics = gamepadButtonHaptics,
            gamepadStickDeadzone = gamepadStickDeadzone,
            gamepadLeftStickSensitivity = gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = gamepadRightStickSensitivity,
            gamepadBindingsByPad = gamepadBindingsByPad,
            autoSaveOnExit = autoSaveOnExit,
            autoLoadOnStart = autoLoadOnStart,
            targetFps = targetFps,
            audioVolume = audioVolume,
            audioMuted = audioMuted
        )
    }

    private fun EmulationUiState.computeProvidedKeys(
        settings: SettingsSnapshot,
        profile: PerGameSettings
    ): Set<String> = buildSet {
        if (renderer != settings.renderer) add("renderer")
        if (upscale != settings.upscaleMultiplier) add("upscaleMultiplier")
        if (aspectRatio != settings.aspectRatio) add("aspectRatio")
        if (displayCrop != settings.displayCrop) add("displayCrop")
        if (showFps != settings.showFps) add("showFps")
        if (fpsOverlayMode != settings.fpsOverlayMode) add("fpsOverlayMode")
        if (enableCheats != settings.enableCheats) add("enableCheats")
        if (frameSkip != settings.frameSkip) add("frameSkip")
        if (frameLimitEnabled != settings.frameLimitEnabled) add("frameLimitEnabled")
        if (targetFps != settings.targetFps) add("targetFps")
        if (audioVolume != settings.audioVolume) add("audioVolume")
        if (audioMuted != settings.audioMuted) add("audioMuted")
        if (racingMode != settings.racingMode) add("racingMode")
        if (stickyButtons != settings.stickyButtons) add("stickyButtons")
        if (touchscreenLeftStick != settings.touchscreenLeftStick) add("touchscreenLeftStick")
        if (touchscreenLeftStickSensitivity != settings.touchscreenLeftStickSensitivity) {
            add("touchscreenLeftStickSensitivity")
        }
        if (touchscreenRightStick != settings.touchscreenRightStick) add("touchscreenRightStick")
        if (touchscreenRightStickSensitivity != settings.touchscreenRightStickSensitivity) {
            add("touchscreenRightStickSensitivity")
        }
        if (touchHaptics != settings.touchHaptics) add("touchHaptics")
        if (touchHapticsPreset != settings.touchHapticsPreset) add("touchHapticsPreset")
        if (profile.touchControlVisualStyle != null) add("touchControlVisualStyle")
        if (profile.touchControlPressEffect != null) add("touchControlPressEffect")
        if (gyroMode != settings.gyroMode) add("gyroMode")
        if (gyroSensitivity != settings.gyroSensitivity) add("gyroSensitivity")
        if (gyroSmoothing != settings.gyroSmoothing) add("gyroSmoothing")
        if (gyroInvertX != settings.gyroInvertX) add("gyroInvertX")
        if (gyroInvertY != settings.gyroInvertY) add("gyroInvertY")
        if (gamepadRightStickUpToR2 != settings.gamepadRightStickUpToR2) add("gamepadRightStickUpToR2")
        if (gamepadRightStickDownToL2 != settings.gamepadRightStickDownToL2) add("gamepadRightStickDownToL2")
        if (gamepadButtonHaptics != settings.gamepadButtonHaptics) add("gamepadButtonHaptics")
        if (gamepadStickDeadzone != settings.gamepadStickDeadzone) add("gamepadStickDeadzone")
        if (gamepadLeftStickSensitivity != settings.gamepadLeftStickSensitivity) add("gamepadLeftStickSensitivity")
        if (gamepadRightStickSensitivity != settings.gamepadRightStickSensitivity) add("gamepadRightStickSensitivity")
        if (gamepadBindingsByPad.isNotEmpty()) add("gamepadBindingsByPad")
        if (autoSaveOnExit) add("autoSaveOnExit")
        if (autoLoadOnStart) add("autoLoadOnStart")
    }

    private fun refreshCurrentGameCheats(
        metadata: com.sbro.emucored.core.GameMetadata,
        cheatsEnabled: Boolean = _uiState.value.enableCheats
    ) {
        if (activeGamePlatform() != GamePlatform.N3DS) {
            refreshAvailableTypedCheats()
            return
        }
        val serial = metadata.serial.orEmpty()
        val crc = metadata.serialWithCrc.extractCrc()
        val config = cheatRepository.getGameConfig(
            gameKeys = cheatLookupKeys(metadata),
            serial = serial,
            crc = crc
        )
        _uiState.update { latest -> latest.copy(
            cheatsGameKey = config?.gameKey,
            availableCheats = config?.blocks.orEmpty()
        ) }
        if (config != null && cheatsEnabled) {
            cheatRepository.syncActiveCheats(config.gameKey, serial, crc)
        }
    }

    private fun refreshAvailableTypedCheats() {
        val identity = currentGamePath ?: return
        val platform = activeGamePlatform()
        val config = cheatRepository.typedGameConfig(platform, identity)
        if (identity != currentGamePath || platform != activeGamePlatform()) return
        _uiState.update { latest -> latest.copy(cheatsGameKey = config?.gameKey, availableCheats = config?.blocks.orEmpty()) }
    }

    private suspend fun syncCheatsForCurrentGame(gameKeyOverride: String? = null) {
        if (activeGamePlatform() != GamePlatform.N3DS) {
            val identity = currentGamePath ?: return
            val platform = activeGamePlatform()
            val codes = if (_uiState.value.enableCheats) cheatRepository.enabledTypedCodes(platform, identity) else emptyList()
            if (identity == currentGamePath && platform == activeGamePlatform()) EmulatorBridge.replaceCheats(codes)
            return
        }
        val state = _uiState.value
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        val crc = currentGameCrc.takeIf { it.isNotBlank() }
        val gameKey = gameKeyOverride
            ?: state.cheatsGameKey
            ?: serial
            ?: crc
            ?: "game"
        cheatRepository.syncActiveCheats(
            gameKey = gameKey,
            serial = serial,
            crc = crc,
            includeCheats = state.enableCheats
        )
        val coreCheatFile = cheatRepository.activeCoreCheatFile(gameKey, serial, crc)
        if (coreCheatFile != null) {
            NativeApp.loadCheats(coreCheatFile.absolutePath)
        } else {
            NativeApp.clearCheats()
        }
    }

    private fun cheatLookupKeys(metadata: com.sbro.emucored.core.GameMetadata): List<String> {
        val keys = linkedSetOf<String>()
        metadata.serialWithCrc?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.serialWithCrc.extractSerialAndCrcKey()?.let(keys::add)
        metadata.serialWithCrc.extractCrc()?.let(keys::add)
        metadata.serial?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.title.trim().takeIf { it.isNotBlank() }?.let(keys::add)
        return keys.toList()
    }

    fun setSlot(slot: Int) {
        _uiState.update { latest -> latest.copy(currentSlot = normalizeManualSaveSlot(slot)) }
        refreshSaveStateMetadata()
    }

    private fun normalizeSaveSlot(slot: Int): Int = slot.coerceIn(AUTO_SAVE_SLOT, 10)

    private fun normalizeManualSaveSlot(slot: Int): Int = slot.coerceIn(1, 10)

    fun setAutoSaveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoSaveEnabled(enabled)
        }
    }

    fun setAutoSaveIntervalMinutes(value: Int) {
        viewModelScope.launch {
            preferences.setAutoSaveIntervalMinutes(value)
        }
    }

    fun setAutoSaveOnExit(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            persistRuntimeState({ it.copy(autoSaveOnExit = enabled) })
        }
    }

    fun setAutoLoadOnStart(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            persistRuntimeState({ it.copy(autoLoadOnStart = enabled) })
        }
    }

    private fun refreshSaveStateMetadata() {
        val path = currentGamePath
        if (path.isNullOrBlank()) {
            savePreviewJob?.cancel()
            _uiState.update { latest -> latest.copy(
                currentSlotLastModified = 0L,
                currentSlotPreviewPath = null,
                autoSaveLastModified = 0L,
                autoSavePreviewPath = null
            ) }
            return
        }

        val currentSlotFile = resolveSaveStateFile(path, _uiState.value.currentSlot)
            ?.takeIf { it.exists() }
        val autoSaveFile = resolveSaveStateFile(path, AUTO_SAVE_SLOT)
            ?.takeIf { it.exists() }
        if (DsSession.isActive) {
            _uiState.update { latest -> latest.copy(
                currentSlotLastModified = currentSlotFile?.lastModified() ?: 0L,
                autoSaveLastModified = autoSaveFile?.lastModified() ?: 0L
            ) }
            refreshSaveStatePreviews(currentSlotFile, autoSaveFile)
            return
        }
        _uiState.update { latest -> latest.copy(
            currentSlotLastModified = currentSlotFile?.lastModified() ?: 0L,
            autoSaveLastModified = autoSaveFile?.lastModified() ?: 0L
        ) }
        refreshSaveStatePreviews(currentSlotFile, autoSaveFile)
    }

    private fun refreshSaveStatePreviews(currentSlotFile: File?, autoSaveFile: File?) {
        savePreviewJob?.cancel()
        savePreviewJob = viewModelScope.launch(Dispatchers.IO) {
            val currentPreview = currentSlotFile?.let { file ->
                runCatching { saveStateRepository.getPreviewImagePath(file) }.getOrNull()
            }
            ensureActive()
            val autoSavePreview = autoSaveFile?.let { file ->
                runCatching { saveStateRepository.getPreviewImagePath(file) }.getOrNull()
            }
            ensureActive()
            _uiState.update { latest -> latest.copy(
                currentSlotPreviewPath = currentPreview,
                autoSavePreviewPath = autoSavePreview
            ) }
        }
    }

    fun refreshSaveStateMetadataNow() {
        refreshSaveStateMetadata()
    }

    private fun saveStateLastModified(gamePath: String, slot: Int): Long {
        val file = resolveSaveStateFile(gamePath, slot) ?: return 0L
        return file.takeIf { it.exists() }?.lastModified() ?: 0L
    }

    private suspend fun waitForSaveStateUpdate(gamePath: String, slot: Int, previousModified: Long): Boolean {
        if (AdditionalConsoleSession.isActive) return AdditionalConsoleSession.stateFile(slot)?.isFile == true
        if (DsSession.isActive) {
            return awaitSaveStateFileUpdate(previousModified) { DsSession.statePath(slot)?.let(::File) }
        }
        val statePath = runCatching { NativeApp.getCurrentSaveStatePath(slot) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }.getOrNull()
            ?: return false
        val fallbackFile = File(statePath)
        return awaitSaveStateFileUpdate(previousModified) {
            resolveSaveStateFile(gamePath, slot) ?: fallbackFile
        }
    }

    private fun resolveSaveStateFile(gamePath: String, slot: Int): File? {
        val platform = activeGamePlatform()
        if (platform !in setOf(GamePlatform.N3DS, GamePlatform.NDS))
            return ConsoleSessionStorage.stateFile(getApplication<Application>().filesDir, platform.console, gamePath, slot)
        if (DsSession.isActive) {
            return DsSession.statePath(slot)?.let(::File)
        }
        // The identity path is what the native writer actually uses; fall back
        // to the caller's path and finally to a serial-named scan.
        val identityFile = runCatching { NativeApp.getCurrentSaveStatePath(slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (identityFile?.exists() == true) return identityFile
        val nativeFile = runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (nativeFile?.exists() == true) return nativeFile
        return findSaveStateFileForCurrentGame(slot) ?: nativeFile
    }

    private fun findSaveStateFileForCurrentGame(slot: Int): File? {
        val targetSerial = currentGameSerial.normalizeSaveSerialKey() ?: return null
        val matches = EmulatorStorage.saveStatesDir(getApplication(), preferences.getEmulatorDataPathSync())
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val parsed = SAVE_STATE_FILE_REGEX.matchEntire(file.name) ?: return@mapNotNull null
                val fileSlot = parsed.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (fileSlot != slot) return@mapNotNull null
                val fileSerial = parsed.groupValues[1].normalizeSaveSerialKey() ?: return@mapNotNull null
                if (fileSerial != targetSerial) return@mapNotNull null
                file
            }
        if (matches.isEmpty()) return null
        return matches.maxByOrNull { it.lastModified() }
    }

    fun quickSave() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            val path = currentGamePath
            val previousModified = path?.let { saveStateLastModified(it, slot) } ?: 0L
            _uiState.update { latest -> latest.copy(
                isActionInProgress = true,
                actionLabel = "saving"
            ) }
            val scheduled = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.saveState(slot)
                    } catch (_: Exception) { false }
                }
            }
            val success = if (DsSession.isActive) {
                scheduled
            } else {
                scheduled && path != null && waitForSaveStateUpdate(path, slot, previousModified)
            }
            _uiState.update { latest -> latest.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "saved" else "save_failed"
            ) }
            // Refresh even on failure: whatever is on disk is the truth, and a
            // silent failure used to leave the slot looking empty.
            refreshSaveStateMetadata()
            if (success && path != null) requestSaveStatePreview(path, slot)
            delay(2000.milliseconds)
            _uiState.update { latest -> latest.copy(toastMessage = null) }
        }
    }

    fun quickLoad() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { latest -> latest.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            ) }
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(slot)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.update { latest -> latest.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            ) }
            delay(2000.milliseconds)
            _uiState.update { latest -> latest.copy(toastMessage = null) }
        }
    }

    fun loadAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { latest -> latest.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            ) }
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.update { latest -> latest.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            ) }
            delay(2000.milliseconds)
            _uiState.update { latest -> latest.copy(toastMessage = null) }
        }
    }

    private suspend fun tryAutoLoadOnStart(): Boolean {
        val path = currentGamePath ?: return false
        if (!_uiState.value.autoLoadOnStart || saveStateLastModified(path, AUTO_SAVE_SLOT) <= 0L) {
            return false
        }
        _uiState.update { latest -> latest.copy(
            isActionInProgress = true,
            actionLabel = "loading",
            statusMessage = "status_loading_state"
        ) }
        val success = lifecycleMutex.withLock {
            if (isShuttingDown || !_uiState.value.isRunning) {
                false
            } else {
                try {
                    EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        _uiState.update { latest -> latest.copy(
            isActionInProgress = false,
            actionLabel = null,
            statusMessage = if (success) "status_running" else null,
            toastMessage = if (success) "loaded" else "load_failed"
        ) }
        refreshSaveStateMetadata()
        delay(2000.milliseconds)
        if (_uiState.value.statusMessage == "status_running") {
            _uiState.update { latest -> latest.copy(statusMessage = null) }
        }
        if (_uiState.value.toastMessage == "loaded" || _uiState.value.toastMessage == "load_failed") {
            _uiState.update { latest -> latest.copy(toastMessage = null) }
        }
        return success
    }

    /**
     * A route transition can dispose the old composition after the next game
     * has already claimed the ViewModel. Only tear down the session that
     * belonged to the disposed screen.
     */
    fun stopEmulationIfCurrent(expectedPath: String?, onExit: (() -> Unit)? = null) {
        val current = currentGamePath
        if (!expectedPath.isNullOrBlank() && !current.isNullOrBlank() && current != expectedPath) {
            Log.i(TAG, "Ignoring stale stop for $expectedPath; current=$current")
            return
        }
        stopEmulation(onExit)
    }

    fun stopEmulation(onExit: (() -> Unit)? = null) {
        Log.i(TAG, "stopEmulation requested")
        cancelCoverDownload()
        cancelPendingStart = true
        pausedForBackground = false
        val app = getApplication<EmuCoreDApp>()
        app.applicationScope.launch(Dispatchers.IO) {
            var shouldAutoSave = _uiState.value.shouldAutoSaveOnExit(
                currentGamePath,
                EmulatorBridge.runtimeFailure.value
            )

            val shouldStop = lifecycleMutex.withLock {
                if (isShuttingDown) return@withLock false
                isShuttingDown = true
                try {
                    EmulatorBridge.resetKeyStatus()
                } catch (_: Exception) { }
                try {
                    GamepadManager.clearPerGameOverrides()
                } catch (_: Exception) { }
                try {
                    _keyboardMouseOverride.value = null
                    KeyboardMouseManager.setPerGameOverride(null)
                } catch (_: Exception) { }
                if (shouldAutoSave) {
                    // A failed pause must still take the normal teardown path;
                    // never save a potentially invalid session or strand exit.
                    shouldAutoSave = runCatching { EmulatorBridge.pause() }.isSuccess
                }
                if (!shouldAutoSave) EmulatorBridge.requestShutdown()
                _uiState.update { latest -> latest.copy(
                    isRunning = false,
                    isStarting = false,
                    isPaused = false,
                    showMenu = false,
                    isActionInProgress = false,
                    actionLabel = null,
                    statusMessage = null
                ) }
                _performanceState.value = EmulationPerformanceState(fps = "0")
                syncNativePerformanceOverlayState(_uiState.value)
                true
            }
            if (!shouldStop) return@launch

            cachePendingPlayTime()

            if (shouldAutoSave) {
                runCatching {
                    saveAutoSaveSlot(
                        allowWhileMenu = true,
                        allowPaused = true,
                        showActionProgress = false,
                        allowDuringShutdown = true
                    )
                }
                EmulatorBridge.requestShutdown()
            }
            // Keep the destination, SurfaceView and preview collector alive
            // until the exit save and its bounded capture have completed.
            if (onExit != null) {
                withContext(Dispatchers.Main) { onExit.invoke() }
            }
            val teardown = EmulatorBridge.shutdownAsync()
            syncCachedPlayTimeInBackground()
            try {
                teardown.join()
                Log.i(TAG, "stopEmulation: teardown joined")
            } finally {
                lifecycleMutex.withLock {
                    clearCrashContext()
                    RetroAchievementsRepository.get(getApplication()).onGameStopped()
                    isShuttingDown = false
                    Log.i(TAG, "stopEmulation: lifecycle released")
                }
            }
        }
    }

    fun onHostBackgrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown) {
                    return@withLock
                }
                try {
                    EmulatorBridge.pause()
                    pausedForBackground = true
                    _uiState.update { it.copy(isPaused = true) }
                    DiscordIntegration.setPaused(true)
                    syncPendingPlayTime(forceCloud = false)
                    updateCrashContext(launchState = "paused")
                } catch (_: Exception) { }
            }
        }
    }

    fun onHostForegrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!pausedForBackground ||
                    !state.isRunning ||
                    state.isStarting ||
                    !state.isPaused ||
                    state.showMenu ||
                    isShuttingDown
                ) {
                    return@withLock
                }
                try {
                    EmulatorBridge.resume()
                    pausedForBackground = false
                    _uiState.update { it.copy(isPaused = false) }
                    DiscordIntegration.setPaused(false)
                    updateCrashContext(launchState = "running")
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun performShutdown() {
        cancelCoverDownload()
        lifecycleMutex.withLock {
            if (!_uiState.value.isRunning && !_uiState.value.isStarting &&
                !EmulatorBridge.isVmActive() && EmulatorBridge.runtimeFailure.value == null) return
            if (isShuttingDown) return
            val analyticsState = _uiState.value
            val completedRunningSession = analyticsState.isRunning
            isShuttingDown = true
            pausedForBackground = false
            try {
                try {
                    EmulatorBridge.resetKeyStatus()
                } catch (_: Exception) { }
                try {
                    GamepadManager.clearPerGameOverrides()
                } catch (_: Exception) { }
                try {
                    _keyboardMouseOverride.value = null
                    KeyboardMouseManager.setPerGameOverride(null)
                } catch (_: Exception) { }
                try {
                    EmulatorBridge.shutdown()
                    Log.i(TAG, "performShutdown: core shutdown returned")
                } catch (_: Exception) { }
                _uiState.update { latest -> latest.copy(
                    isRunning = false,
                    isStarting = false,
                    isPaused = false,
                    showMenu = false,
                    isActionInProgress = false,
                    actionLabel = null,
                    statusMessage = null
                ) }
                _performanceState.value = EmulationPerformanceState(fps = "0")
                syncNativePerformanceOverlayState(_uiState.value)
                clearCrashContext()
                RetroAchievementsRepository.get(getApplication()).onGameStopped()
                Log.i(TAG, "performShutdown: session bookkeeping complete")
            } finally {
                isShuttingDown = false
            }
        }
    }

    private fun updateCrashContext(
        launchState: String? = null,
        launchPath: String? = null
    ) {
        val state = _uiState.value
        NativeApp.setCrashContextString("emu_launch_state", launchState ?: when {
            state.isStarting -> "starting"
            state.isPaused -> "paused"
            state.isRunning -> "running"
            else -> "idle"
        })
        NativeApp.setCrashContextString("emu_game_title", currentGameTitle)
        NativeApp.setCrashContextString("emu_game_serial", currentGameSerial)
        NativeApp.setCrashContextString("emu_game_source", currentGameSource)
        NativeApp.setCrashContextString("emu_game_path_hint", launchPath?.let { File(it).name }.orEmpty())
        NativeApp.setCrashContextInt("emu_renderer", state.renderer)
        NativeApp.setCrashContextString("emu_renderer_name", when (state.renderer) {
            12 -> "OpenGL"
            13 -> "Software"
            14 -> "Vulkan"
            else -> "Unknown(${state.renderer})"
        })
        NativeApp.setCrashContextString("emu_upscale", state.upscale.toString())
        NativeApp.setCrashContextInt("emu_aspect_ratio", state.aspectRatio)
        NativeApp.setCrashContextInt("emu_crop_left", state.displayCrop.left)
        NativeApp.setCrashContextInt("emu_crop_top", state.displayCrop.top)
        NativeApp.setCrashContextInt("emu_crop_right", state.displayCrop.right)
        NativeApp.setCrashContextInt("emu_crop_bottom", state.displayCrop.bottom)
        NativeApp.setCrashContextBool("emu_enable_cheats", state.enableCheats)
        NativeApp.setCrashContextInt("emu_frame_skip", state.frameSkip)
        NativeApp.setCrashContextBool("emu_frame_limit_enabled", state.frameLimitEnabled)
        NativeApp.setCrashContextInt("emu_target_fps", state.targetFps)
        NativeApp.setCrashContextString("emu_device_model", Build.MODEL.orEmpty())
        NativeApp.setCrashContextString("emu_soc_model", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        NativeApp.setCrashContextBool("emu_running", state.isRunning)
        NativeApp.setCrashContextBool("emu_paused", state.isPaused)
    }

    private fun clearCrashContext() {
        cancelCoverDownload()
        DiscordIntegration.clearGame()
        _keyboardMouseOverride.value = null
        KeyboardMouseManager.setPerGameOverride(null)
        currentGameTitle = ""
        currentGamePath = null
        currentTouchControlsLayoutProfile = null
        currentCustomTouchControlsProfile = null
        currentGameSerial = ""
        currentGameCoverArtPath = null
        currentGameCrc = ""
        // The sidebar can still be drawing its exit animation after shutdown.
        // Keep its game identity until navigation disposes the screen; the next
        // launch replaces it while the menu is hidden.
        _uiState.update { latest -> latest.copy(
            gameSettingsProfileActive = false
        ) }
        currentGameSource = ""
        NativeApp.setCrashContextString("emu_launch_state", "idle")
        NativeApp.setCrashContextString("emu_game_title", "")
        NativeApp.setCrashContextString("emu_game_serial", "")
        NativeApp.setCrashContextString("emu_game_source", "")
        NativeApp.setCrashContextString("emu_game_path_hint", "")
        NativeApp.setCrashContextBool("emu_running", false)
        NativeApp.setCrashContextBool("emu_paused", false)
    }

    fun onPadInput(padIndex: Int, keyCode: Int, range: Int = 0, pressed: Boolean) {
        try {
            EmulatorBridge.setPadButton(padIndex, keyCode, range, pressed)
        } catch (_: Exception) { }
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared running=${_uiState.value.isRunning} shuttingDown=$isShuttingDown")
        DiscordIntegration.clearGame()
        androidGamePerformance.update(AndroidGamePhase.Idle)
        NativeApp.setPerformanceMetricsEnabled(visible = false, detailed = false)
        if (_uiState.value.isRunning && !isShuttingDown) {
            EmulatorBridge.resetKeyStatus()
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    EmulatorBridge.shutdown()
                }
            }
        }
    }
}

private fun String?.extractCrc(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val parenthesized = raw.substringAfter('(', "").substringBefore(')').trim()
    if (parenthesized.matches(Regex("[0-9A-Fa-f]{8}"))) return parenthesized.uppercase()
    return Regex("([0-9A-Fa-f]{8})(?!.*[0-9A-Fa-f]{8})")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase()
}

private fun String?.extractSerialAndCrcKey(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val crc = raw.extractCrc()
    val serial = raw.substringBefore('(')
        .replace(Regex("_[0-9A-Fa-f]{8}$"), "")
        .trim()
    return if (serial.isNotBlank() && !crc.isNullOrBlank()) "${serial}_$crc" else null
}

private fun String?.normalizeSaveSerialKey(): String? {
    if (this.isNullOrBlank()) return null
    val cleanSerial = trim().uppercase(Locale.ROOT)
    val splitRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{3})[^A-Z0-9]*([0-9]{2})")
    val compactRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{5})")
    splitRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
    }
    compactRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }
    return cleanSerial.replace(Regex("[^A-Z0-9_-]"), "").takeIf { it.isNotBlank() }
}
