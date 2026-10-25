
package com.sbro.emucored.ui.emulation

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import com.sbro.emucored.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucored.R
import com.sbro.emucored.core.AudioDefaults
import com.sbro.emucored.core.AndroidTouchHaptics
import com.sbro.emucored.core.AndroidGyroscopeInput
import com.sbro.emucored.core.AndroidTouchHaptics.ButtonPhase
import com.sbro.emucored.core.CoreOptionStore
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.NativeApp
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.GamepadManager
import com.sbro.emucored.core.KeyboardMouseManager
import com.sbro.emucored.core.LocalTvUiEnvironment
import com.sbro.emucored.core.SoftwareKeyboard
import com.sbro.emucored.core.buildUpscaleOptions
import com.sbro.emucored.core.upscaleKeyToMultiplier
import com.sbro.emucored.core.upscaleMultiplierValue
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.AppPreferences.Companion.FPS_OVERLAY_MODE_DETAILED
import com.sbro.emucored.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucored.data.DisplayCrop
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.core.platform.SettingsCategory
import com.sbro.emucored.ui.settings.ConsoleCoreOptionsPanel
import com.sbro.emucored.data.OverlayControlLayout
import com.sbro.emucored.data.OverlayLayoutSnapshot
import com.sbro.emucored.data.PerformanceOverlayMetrics
import com.sbro.emucored.data.SettingsSnapshot
import com.sbro.emucored.data.TouchControlVisualStyle
import com.sbro.emucored.data.TouchControlPressEffect
import com.sbro.emucored.data.CustomTouchControl
import com.sbro.emucored.data.CustomTouchControlPressMode
import com.sbro.emucored.data.GameMenuTabId
import com.sbro.emucored.data.GameMenuSectionId
import com.sbro.emucored.data.GameMenuLayoutStyle
import com.sbro.emucored.data.gameMenuSectionsForTab
import com.sbro.emucored.ui.common.CustomControlVisual
import com.sbro.emucored.ui.common.gameCoverAspectRatio
import com.sbro.emucored.ui.common.gameCoverContentScale
import com.sbro.emucored.ui.common.isGenerated3dCover
import com.sbro.emucored.ui.common.actionDrawableRes
import com.sbro.emucored.ui.common.composeShape
import com.sbro.emucored.ui.common.BitmapPathImage
import com.sbro.emucored.ui.common.EmulationSideArtworkOverlay
import com.sbro.emucored.ui.common.GameCoverArt
import com.sbro.emucored.ui.common.ProvideGamepadMenuAction
import com.sbro.emucored.ui.common.ProvideGamepadShoulderActions
import com.sbro.emucored.ui.common.ProvideGamepadUiNavigation
import com.sbro.emucored.ui.common.SettingHelpButton
import com.sbro.emucored.ui.common.OverlayDpadDirection
import com.sbro.emucored.ui.common.VectorAnalogStick
import com.sbro.emucored.ui.common.VectorDpadCluster
import com.sbro.emucored.ui.common.VectorOverlayButton
import com.sbro.emucored.ui.common.buildOverlayCanvasLayout
import com.sbro.emucored.ui.common.gamepadFocusableCard
import com.sbro.emucored.ui.common.horizontalViewportBleed
import com.sbro.emucored.ui.common.premiumFilterChipColors
import com.sbro.emucored.ui.common.rememberDebouncedClick
import com.sbro.emucored.ui.common.tvFocusGroup
import com.sbro.emucored.ui.settings.ControlsEditorScreen
import com.sbro.emucored.ui.settings.LocalSettingsHorizontalPadding
import com.sbro.emucored.ui.settings.LocalSettingsHorizontalScrollBleed
import com.sbro.emucored.ui.settings.KeyboardMouseSettingsDialog
import com.sbro.emucored.ui.settings.toControlsEditorState
import com.sbro.emucored.data.forPlatform
import com.sbro.emucored.data.ConsoleControlsScope
import com.sbro.emucored.data.forConsoleControls
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import com.sbro.emucored.core.PlatformUiCapabilities
import com.sbro.emucored.core.AzaharCoreOptions
import com.sbro.emucored.core.azaharCoreOptionHelpRes
import com.sbro.emucored.core.AzaharCoreOptionLocalization
import com.sbro.emucored.data.AchievementItem
import com.sbro.emucored.data.RetroAchievementsEvent
import com.sbro.emucored.data.RetroAchievementsRepository
import com.sbro.emucored.data.RetroAchievementsState
import coil3.compose.AsyncImage
import com.sbro.emucored.ui.theme.GradientEnd
import com.sbro.emucored.ui.theme.GradientStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import com.sbro.emucored.ui.theme.neon.LocalNeonTheme
import com.sbro.emucored.ui.theme.neon.NeonCrtOverlay
import com.sbro.emucored.ui.theme.neon.neonChipShape
import com.sbro.emucored.ui.theme.neon.neonShape
import com.sbro.emucored.ui.theme.neon.neonShapeCorners

private object PadKey {
    const val UP = 19
    const val RIGHT = 22
    const val DOWN = 20
    const val LEFT = 21
    const val X = 100
    const val A = 97
    const val B = 96
    const val Y = 99
    const val SELECT = 109
    const val START = 108
    const val L = 102
    const val ZL = 104
    const val R = 103
    const val ZR = 105
    const val HOME = 106
    const val TOUCH = 107
    const val LEFT_STICK_UP = 110
    const val LEFT_STICK_RIGHT = 111
    const val LEFT_STICK_DOWN = 112
    const val LEFT_STICK_LEFT = 113
    const val RIGHT_STICK_UP = 120
    const val RIGHT_STICK_RIGHT = 121
    const val RIGHT_STICK_DOWN = 122
    const val RIGHT_STICK_LEFT = 123
}

private enum class EmulationMenuTab {
    Session,
    Controls,
    Emulation,
    Cheats,
    Graphics,
    Achievements
}

internal data class TouchButtonSpec(
    val id: String,
    val drawableRes: Int? = null,
    val width: Dp,
    val height: Dp,
    val x: Dp,
    val y: Dp,
    val shape: androidx.compose.ui.graphics.Shape,
    val opacity: Float = 1f,
    val customControl: CustomTouchControl? = null,
    val haptics: Boolean = true,
    val pressActions: List<TouchButtonPressAction> = emptyList(),
    val onClick: (() -> Unit)? = null,
    val tapToHold: Boolean = false,
    val longPressDelayMs: Long = 0L,
    val onLongPressChange: ((Boolean) -> Unit)? = null,
    val labelOverride: String? = null
)

internal data class TouchButtonPressAction(
    val ownerId: String,
    val onPressChange: (Boolean) -> Unit
)

internal fun touchButtonActionOwner(actionId: String): String = when (actionId) {
    "dpad_up" -> "up"
    "dpad_down" -> "down"
    "dpad_left" -> "left"
    "dpad_right" -> "right"
    else -> actionId
}

private data class TouchButtonLayoutKey(
    val id: String,
    val width: Dp,
    val height: Dp,
    val x: Dp,
    val y: Dp,
    val tapToHold: Boolean,
    val hasLongPressAction: Boolean
)

private data class LiveSelectionOption(
    val value: Int,
    val label: String? = null,
    val icon: ImageVector? = null,
    val contentDescription: String = label.orEmpty()
)

@Composable
private fun fpsOverlayCornerLiveOptions(): List<LiveSelectionOption> = listOf(
    LiveSelectionOption(AppPreferences.FPS_OVERLAY_CORNER_TOP_LEFT, stringResource(R.string.settings_fps_overlay_corner_top_left)),
    LiveSelectionOption(AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT, stringResource(R.string.settings_fps_overlay_corner_top_right)),
    LiveSelectionOption(AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_LEFT, stringResource(R.string.settings_fps_overlay_corner_bottom_left)),
    LiveSelectionOption(AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_RIGHT, stringResource(R.string.settings_fps_overlay_corner_bottom_right))
)

@Composable
private fun fpsOverlayMetricLiveOptions(): List<Pair<Int, String>> = listOf(
    PerformanceOverlayMetrics.FPS to stringResource(R.string.settings_fps_metric_fps),
    PerformanceOverlayMetrics.SPEED to stringResource(R.string.settings_fps_metric_speed),
    PerformanceOverlayMetrics.RENDERER to stringResource(R.string.settings_fps_metric_renderer),
    PerformanceOverlayMetrics.FRAME_TIME to stringResource(R.string.settings_fps_metric_frame_time),
    PerformanceOverlayMetrics.RESOLUTION to stringResource(R.string.settings_fps_metric_resolution),
    PerformanceOverlayMetrics.HOST_CPU to stringResource(R.string.settings_fps_metric_host_cpu),
    PerformanceOverlayMetrics.AUDIO to stringResource(R.string.settings_fps_metric_audio)
)

@Composable
private fun eeCycleRateLiveOptions(): List<LiveSelectionOption> = listOf(
    LiveSelectionOption(-3, "50%"),
    LiveSelectionOption(-2, "60%"),
    LiveSelectionOption(-1, "75%"),
    LiveSelectionOption(0, "100%"),
    LiveSelectionOption(1, "130%"),
    LiveSelectionOption(2, "180%"),
    LiveSelectionOption(3, "300%")
)

@Composable
private fun eeCycleSkipLiveOptions(): List<LiveSelectionOption> = listOf(
    LiveSelectionOption(0, stringResource(R.string.settings_ee_cycle_disabled)),
    LiveSelectionOption(1, stringResource(R.string.settings_ee_cycle_mild)),
    LiveSelectionOption(2, stringResource(R.string.settings_ee_cycle_moderate)),
    LiveSelectionOption(3, stringResource(R.string.settings_ee_cycle_maximum))
)

private fun Int.toOverlayAlignment(): Alignment = when (this) {
    AppPreferences.FPS_OVERLAY_CORNER_TOP_LEFT -> Alignment.TopStart
    AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_LEFT -> Alignment.BottomStart
    AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_RIGHT -> Alignment.BottomEnd
    else -> Alignment.TopEnd
}

private fun Int.isTopOverlayCorner(): Boolean {
    return this == AppPreferences.FPS_OVERLAY_CORNER_TOP_LEFT ||
        this == AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT
}

private fun Int.isBottomOverlayCorner(): Boolean {
    return this == AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_LEFT ||
        this == AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_RIGHT
}

private fun Int.isRightOverlayCorner(): Boolean {
    return this == AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT ||
        this == AppPreferences.FPS_OVERLAY_CORNER_BOTTOM_RIGHT
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun EmulationScreen(
    gamePath: String? = null,
    installedPath: String? = null,
    bootSmokeProbe: Boolean = false,
    saveSlot: Int? = null,
    autotestMode: Boolean = false,
    rendererOverride: Int? = null,
    restoredAfterProcessDeath: Boolean = false,
    onExit: (activePlayTimeMs: Long) -> Unit,
    viewModel: EmulationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val presentationSurfaceGeneration by EmulatorBridge.presentationSurfaceGeneration.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val originalRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val restoreHostUi = remember(activity, originalRequestedOrientation) {
        {
            activity?.requestedOrientation = originalRequestedOrientation
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.navigationBars())
                controller.hide(WindowInsetsCompat.Type.statusBars())
            }
            Unit
        }
    }
    val currentOnExit by rememberUpdatedState(onExit)
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { AppPreferences(context) }
    val globalDefaults by preferences.settingsSnapshot.collectAsState(initial = SettingsSnapshot())
    val overlayDefaults by preferences.overlayLayoutSnapshot.collectAsState(initial = OverlayLayoutSnapshot())
    val gamepadBindingsByPad by preferences.gamepadBindingsByPad.collectAsState(initial = emptyMap())
    val floatingQuickActionsEnabled by preferences.floatingQuickActionsEnabled.collectAsState(initial = false)
    val persistedFloatingQuickSavePosition by preferences.floatingQuickSavePosition.collectAsState(
        initial = AppPreferences.DEFAULT_FLOATING_QUICK_SAVE_POSITION_X to
            AppPreferences.DEFAULT_FLOATING_QUICK_SAVE_POSITION_Y
    )
    val persistedFloatingQuickLoadPosition by preferences.floatingQuickLoadPosition.collectAsState(
        initial = AppPreferences.DEFAULT_FLOATING_QUICK_LOAD_POSITION_X to
            AppPreferences.DEFAULT_FLOATING_QUICK_LOAD_POSITION_Y
    )
    val orientationLockPreference by preferences.orientationLock.collectAsState(initial = null)
    val emulationAllowsBothOrientations by preferences.emulationAllowsBothOrientations.collectAsState(
        initial = null
    )
    val secondScreenMode by preferences.secondScreenMode.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_MODE_PANEL
    )
    val isNds = uiState.gamePlatform == GamePlatform.NDS
    val singleScreenConsole = uiState.gamePlatform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)
    val overlayWiiProfile = com.sbro.emucored.core.platform.session.AdditionalConsoleSession.activeWiiControllerProfile
        ?: uiState.perGameCoreOptions["wii_profile"] ?: "nunchuk"
    val overlayPolicy = remember(uiState.gamePlatform, overlayWiiProfile) {
        ConsoleOverlayPolicy.forPlatform(uiState.gamePlatform.console, overlayWiiProfile)
    }
    val effectiveSecondScreenMode = if (isNds || singleScreenConsole) {
        AppPreferences.SECOND_SCREEN_MODE_INTEGRATED
    } else secondScreenMode
    val secondScreenWidth by preferences.secondScreenWidth.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_WIDTH_DEFAULT
    )
    val secondScreenOpen by preferences.secondScreenOpen.collectAsState(initial = false)
    val secondScreenPanelPosition by preferences.secondScreenPanelPosition.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_PANEL_X_DEFAULT to
            AppPreferences.SECOND_SCREEN_PANEL_Y_DEFAULT
    )
    val secondScreenFloatPosition by preferences.secondScreenFloatPosition.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_FLOAT_X_DEFAULT to
            AppPreferences.SECOND_SCREEN_FLOAT_Y_DEFAULT
    )
    val secondScreenFloatSize by preferences.secondScreenFloatSize.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_FLOAT_SIZE_DEFAULT
    )
    val effectiveGamepadBindingsByPad = if (uiState.gameSettingsProfileActive && uiState.gamepadBindingsByPad.isNotEmpty()) {
        uiState.gamepadBindingsByPad
    } else {
        gamepadBindingsByPad
    }
    val gamepadActions = remember { GamepadManager.mappableButtonActions() }
    val scope = rememberCoroutineScope()
    val achievementsRepository = remember(context) { RetroAchievementsRepository.get(context) }
    var achievementUnlockBanner by remember { mutableStateOf<RetroAchievementsEvent?>(null) }
    LaunchedEffect(Unit) {
        achievementsRepository.ensureInitialized()
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) { achievementsRepository.pollEvents() }
            }.getOrDefault(emptyList())
                .lastOrNull { it.isAchievementUnlock || it.type == "game_loaded" }
                ?.let { achievementUnlockBanner = it }
            delay(800L)
        }
    }
    LaunchedEffect(achievementUnlockBanner) {
        if (achievementUnlockBanner != null) {
            delay(5_000L)
            achievementUnlockBanner = null
        }
    }
    val rootCutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val gameCutoutPadding = if (globalDefaults.respectDisplayCutout) {
        rootCutoutPadding
    } else {
        PaddingValues(0.dp)
    }
    val rootNavPadding = WindowInsets.navigationBars.asPaddingValues()
    val overlayLeftSafeInset = maxOf(
        rootCutoutPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        rootNavPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    )
    val overlayRightSafeInset = maxOf(
        rootCutoutPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        rootNavPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    )
    val overlayHorizontalSafeInset = maxOf(overlayLeftSafeInset, overlayRightSafeInset)
    val overlayTopSafeInset = maxOf(
        rootCutoutPadding.calculateTopPadding(),
        rootNavPadding.calculateTopPadding()
    )
    val overlayBottomSafeInset = maxOf(
        rootCutoutPadding.calculateBottomPadding(),
        rootNavPadding.calculateBottomPadding()
    )

    val view = LocalView.current
    var showExitDialog by remember { mutableStateOf(false) }
    var exitDispatched by remember { mutableStateOf(false) }
    var showQuickSaveDialog by remember { mutableStateOf(false) }
    var showQuickLoadDialog by remember { mutableStateOf(false) }
    var secondScreenTabRevealSignal by remember { mutableIntStateOf(0) }
    var floatingQuickSavePosition by remember { mutableStateOf<Offset?>(null) }
    var floatingQuickLoadPosition by remember { mutableStateOf<Offset?>(null) }
    var showAutoSaveLoadDialog by remember { mutableStateOf(false) }
    var showControlsEditor by remember { mutableStateOf(false) }
    var showGamepadMappingDialog by remember { mutableStateOf(false) }
    var showKeyboardMouseSettingsDialog by remember { mutableStateOf(false) }
    var pendingGamepadActionId by remember { mutableStateOf<String?>(null) }
    var pendingGamepadPadIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedGamepadPadIndex by rememberSaveable { mutableIntStateOf(0) }
    var showOverlayShortcut by remember { mutableStateOf(false) }
    var resumeAfterEditor by remember { mutableStateOf(false) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }
    var lastTapX by remember { mutableFloatStateOf(0f) }
    var lastTapY by remember { mutableFloatStateOf(0f) }
    val softwareKeyboardRequest by SoftwareKeyboard.request.collectAsState()
    val connectedGamepadCount by GamepadManager.connectedGamepadCountState.collectAsState()
    val gamepadConnected = connectedGamepadCount > 0
    val keyboardMouseConnection by KeyboardMouseManager.connection.collectAsState()
    val keyboardMouseOverride by viewModel.keyboardMouseOverride.collectAsState()
    val globalKeyboardMouseSettings by KeyboardMouseManager.globalSettings.collectAsState()
    val keyboardConnected = keyboardMouseConnection.keyboardConnected
    val mouseConnected = keyboardMouseConnection.mouseConnected
    val touchPadIndex = GamepadManager.resolveTouchPadIndex()
    val overlayPadIndex = touchPadIndex ?: 0
    val currentOverlayPadIndex by rememberUpdatedState(overlayPadIndex)
    val currentActivePlayTimeMs by rememberUpdatedState(uiState.activePlayTimeMs)
    val gyroController = remember(context) {
        AndroidGyroscopeInput(context) { emittedMode, x, y ->
            val targetRightStick = emittedMode == AppPreferences.GYRO_MODE_AIM
            updateAnalogStick(
                x = x,
                y = y,
                upKey = if (targetRightStick) PadKey.RIGHT_STICK_UP else PadKey.LEFT_STICK_UP,
                rightKey = if (targetRightStick) PadKey.RIGHT_STICK_RIGHT else PadKey.LEFT_STICK_RIGHT,
                downKey = if (targetRightStick) PadKey.RIGHT_STICK_DOWN else PadKey.LEFT_STICK_DOWN,
                leftKey = if (targetRightStick) PadKey.RIGHT_STICK_LEFT else PadKey.LEFT_STICK_LEFT,
                onPadInput = { key, range, pressed -> viewModel.onPadInput(currentOverlayPadIndex, key, range, pressed) }
            )
        }
    }
    DisposableEffect(
        lifecycleOwner,
        uiState.isRunning,
        uiState.gyroMode,
        uiState.gyroSensitivity,
        uiState.gyroSmoothing,
        uiState.gyroInvertX,
        uiState.gyroInvertY
    ) {
        fun startGyro() {
            if (uiState.isRunning && uiState.gyroMode != AppPreferences.GYRO_MODE_OFF) {
                gyroController.start(
                    mode = uiState.gyroMode,
                    sensitivityPercent = uiState.gyroSensitivity,
                    smoothingPercent = uiState.gyroSmoothing,
                    invertX = uiState.gyroInvertX,
                    invertY = uiState.gyroInvertY
                )
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startGyro()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> gyroController.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startGyro()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gyroController.stop()
        }
    }
    var showGamepadIndicator by remember { mutableStateOf(gamepadConnected) }

    val tvUiEnabled = LocalTvUiEnvironment.current.enabled
    val dsControlsScale = (uiState.overlayScale / 100f).coerceIn(0.8f, 1.4f)
    val dsControlsHeight = 260.dp * dsControlsScale
    val viewportControls = emulationViewportControls(
        dsMode = isNds,
        controlsVisible = uiState.controlsVisible,
        gamepadConnected = gamepadConnected,
        hideOverlayOnGamepad = uiState.hideOverlayOnGamepad,
        tvUiEnabled = tvUiEnabled,
        touchPadMapped = touchPadIndex != null,
        dsControlsHeightDp = dsControlsHeight.value
    )
    val shouldShowOverlay = viewportControls.showOverlay

    val toggleMenuClick = rememberDebouncedClick(onClick = { viewModel.toggleMenu() })
    val toggleControlsClick = rememberDebouncedClick(onClick = { viewModel.toggleControlsVisibility() })
    val togglePauseClick = rememberDebouncedClick(onClick = { viewModel.togglePause() })
    val requestQuickSaveClick = rememberDebouncedClick(onClick = {
        if (uiState.confirmSaveLoadActions) {
            showQuickSaveDialog = true
        } else {
            viewModel.quickSave()
        }
    })
    val requestQuickLoadClick = rememberDebouncedClick(onClick = {
        if (uiState.confirmSaveLoadActions) {
            showQuickLoadDialog = true
        } else {
            viewModel.quickLoad()
        }
    })
    val requestAutoSaveLoadClick = rememberDebouncedClick(onClick = { showAutoSaveLoadDialog = true })
    val confirmQuickSaveClick = rememberDebouncedClick(onClick = {
        showQuickSaveDialog = false
        viewModel.quickSave()
    })
    val confirmQuickLoadClick = rememberDebouncedClick(onClick = {
        showQuickLoadDialog = false
        viewModel.quickLoad()
    })
    val confirmAutoSaveLoadClick = rememberDebouncedClick(onClick = {
        showAutoSaveLoadDialog = false
        viewModel.loadAutoSave()
    })
    val dismissQuickSaveClick = rememberDebouncedClick(onClick = { showQuickSaveDialog = false })
    val dismissQuickLoadClick = rememberDebouncedClick(onClick = { showQuickLoadDialog = false })
    val dismissAutoSaveLoadClick = rememberDebouncedClick(onClick = { showAutoSaveLoadDialog = false })
    // A retained emulation destination can be reused for another game. Reset
    // the one-shot exit gate when presenting a fresh confirmation dialog.
    val requestExitClick = rememberDebouncedClick(onClick = {
        if (uiState.isRunning) exitDispatched = false
        showExitDialog = true
    })
    val confirmExitClick = rememberDebouncedClick(onClick = {
        if (exitDispatched) return@rememberDebouncedClick
        exitDispatched = true
        showExitDialog = false
        val completedActivePlayTimeMs = currentActivePlayTimeMs
        viewModel.stopEmulation(onExit = {
            completeEmulationExit(
                activePlayTimeMs = completedActivePlayTimeMs,
                restoreHostUi = restoreHostUi,
                navigateFromEmulation = currentOnExit
            )
        })
    })
    val dismissExitClick = rememberDebouncedClick(onClick = { showExitDialog = false })
    val dismissGamepadMappingDialog: () -> Unit = { showGamepadMappingDialog = false }
    val dismissGamepadMappingDialogClick = rememberDebouncedClick(onClick = dismissGamepadMappingDialog)
    val sessionRestoredUnavailableMessage = stringResource(R.string.emulation_session_restored_unavailable)
    val gamepadUiActive = uiState.showMenu ||
        showControlsEditor ||
        showExitDialog ||
        showQuickSaveDialog ||
        showQuickLoadDialog ||
        showAutoSaveLoadDialog ||
        showGamepadMappingDialog ||
        showKeyboardMouseSettingsDialog ||
        pendingGamepadActionId != null

    BackHandler(enabled = true) {
        when (resolveEmulationBackAction(uiState.backButtonExitsGame)) {
            EmulationBackAction.OpenGameMenu -> toggleMenuClick()
            EmulationBackAction.RequestExit -> requestExitClick()
        }
    }

    ProvideGamepadMenuAction(onMenu = toggleMenuClick)
    ProvideGamepadUiNavigation(
        enabled = gamepadUiActive,
        onBack = {
            when {
                pendingGamepadActionId != null -> {
                    pendingGamepadActionId = null
                    true
                }
                showGamepadMappingDialog -> {
                    dismissGamepadMappingDialog()
                    true
                }
                showKeyboardMouseSettingsDialog -> {
                    showKeyboardMouseSettingsDialog = false
                    true
                }
                showQuickLoadDialog -> {
                    showQuickLoadDialog = false
                    true
                }
                showAutoSaveLoadDialog -> {
                    showAutoSaveLoadDialog = false
                    true
                }
                showQuickSaveDialog -> {
                    showQuickSaveDialog = false
                    true
                }
                showExitDialog -> {
                    showExitDialog = false
                    true
                }
                showControlsEditor -> {
                    showControlsEditor = false
                    true
                }
                uiState.showMenu -> {
                    toggleMenuClick()
                    true
                }
                else -> false
            }
        }
    )

    LaunchedEffect(restoredAfterProcessDeath) {
        if (!restoredAfterProcessDeath) return@LaunchedEffect
        Toast.makeText(
            context,
            sessionRestoredUnavailableMessage,
            Toast.LENGTH_LONG
        ).show()
        if (!exitDispatched) {
            exitDispatched = true
            completeEmulationExit(
                activePlayTimeMs = 0L,
                restoreHostUi = restoreHostUi,
                navigateFromEmulation = currentOnExit
            )
        }
    }

    val emulationOrientation: Int? = when {
        isNds -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationLockPreference == null || emulationAllowsBothOrientations == null -> null
        tvUiEnabled -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        orientationLockPreference == AppPreferences.ORIENTATION_LOCK_PORTRAIT ->
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationLockPreference == AppPreferences.ORIENTATION_LOCK_LANDSCAPE ->
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        emulationAllowsBothOrientations == true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    if (emulationOrientation != null) {
        DisposableEffect(activity, originalRequestedOrientation, emulationOrientation) {
            activity?.requestedOrientation = emulationOrientation
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                restoreHostUi()
            }
        }
    }

    DisposableEffect(lifecycleOwner, context, showControlsEditor, uiState.showMenu) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    if (!showControlsEditor && !uiState.showMenu) {
                        viewModel.onHostForegrounded()
                    }
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    val activity = context as? android.app.Activity
                    if (showControlsEditor || activity?.isChangingConfigurations == true) {
                        return@LifecycleEventObserver
                    }
                    viewModel.onHostBackgrounded()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        gamePath,
        installedPath,
        bootSmokeProbe,
        autotestMode,
        rendererOverride,
        restoredAfterProcessDeath
    ) {
        if (restoredAfterProcessDeath) return@LaunchedEffect
        viewModel.startEmulation(
            path = gamePath,
            slotToLoad = saveSlot,
            bootSmokeProbe = bootSmokeProbe,
            autotestMode = autotestMode,
            rendererOverride = rendererOverride,
            launchPathOverride = installedPath
        )
    }

    // Keep the running core in sync with the second-screen presentation mode:
    // panel mode shows only the top screen in the main view and lets the
    // secondary window render the bottom screen.
    LaunchedEffect(isNds, effectiveSecondScreenMode, uiState.swapScreens, uiState.isRunning) {
        if (!uiState.isRunning || singleScreenConsole) return@LaunchedEffect
        if (isNds) {
            EmulatorBridge.setDsScreenLayout(DsSession.LAYOUT_STACKED)
            DsSession.setSwapScreens(uiState.swapScreens)
            return@LaunchedEffect
        }
        if (effectiveSecondScreenMode != AppPreferences.SECOND_SCREEN_MODE_INTEGRATED) {
            EmulatorBridge.setScreenLayout(AppPreferences.SECOND_SCREEN_PANEL_LAYOUT)
            EmulatorBridge.setSwapScreens(uiState.swapScreens)
        } else {
            val storedLayout = CoreOptionStore.value("layout_option")?.toIntOrNull() ?: 0
            EmulatorBridge.setScreenLayout(storedLayout)
            EmulatorBridge.setSwapScreens(uiState.swapScreens)
        }
    }

    LaunchedEffect(gamepadConnected, keyboardConnected, mouseConnected, uiState.showMenu) {
        val anyConnected = gamepadConnected || keyboardConnected || mouseConnected
        if (!anyConnected || uiState.showMenu) {
            if (showGamepadIndicator) {
                delay(700.milliseconds)
                val stillDisconnected =
                    !(gamepadConnected || keyboardConnected || mouseConnected) &&
                        !uiState.showMenu
                if (stillDisconnected) showGamepadIndicator = false
            }
            return@LaunchedEffect
        }
        showGamepadIndicator = true
        delay(5000.milliseconds)
        showGamepadIndicator = false
    }

    LaunchedEffect(showOverlayShortcut, uiState.showMenu) {
        if (uiState.showMenu) {
            showOverlayShortcut = false
        } else if (showOverlayShortcut) {
            delay(2200.milliseconds)
            showOverlayShortcut = false
        }
    }

    LaunchedEffect(showControlsEditor) {
        if (showControlsEditor && !uiState.isPaused) {
            resumeAfterEditor = true
            viewModel.togglePause()
        } else if (!showControlsEditor && resumeAfterEditor && uiState.isPaused) {
            resumeAfterEditor = false
            viewModel.togglePause()
        }
    }

    DisposableEffect(pendingGamepadActionId) {
        val actionId = pendingGamepadActionId
        if (actionId != null) {
            GamepadManager.startBindingCapture(pendingGamepadPadIndex) { keyCode ->
                scope.launch {
                    if (uiState.gameSettingsProfileActive) {
                        val currentBindings = uiState.gamepadBindingsByPad.toMutableMap()
                        val padBindings = currentBindings[pendingGamepadPadIndex].orEmpty().toMutableMap()
                        padBindings[actionId] = keyCode
                        currentBindings[pendingGamepadPadIndex] = padBindings
                        viewModel.setGamepadBindingsByPad(currentBindings)
                    } else {
                        preferences.setGamepadBinding(pendingGamepadPadIndex, actionId, keyCode)
                    }
                }
                pendingGamepadActionId = null
            }
        }
        onDispose {
            GamepadManager.cancelBindingCapture()
        }
    }

    LaunchedEffect(uiState.confirmSaveLoadActions) {
        GamepadManager.gamepadShortcutActions.collect { action ->
            when (action.actionId) {
                GamepadManager.ACTION_QUICK_SAVE -> {
                    if (uiState.confirmSaveLoadActions) {
                        showQuickSaveDialog = true
                    } else {
                        viewModel.quickSave()
                    }
                }
                GamepadManager.ACTION_QUICK_LOAD -> {
                    if (uiState.confirmSaveLoadActions) {
                        showQuickLoadDialog = true
                    } else {
                        viewModel.quickLoad()
                    }
                }
                GamepadManager.ACTION_TOGGLE_SECOND_SCREEN -> {
                    scope.launch { preferences.setSecondScreenOpen(!secondScreenOpen) }
                }
                GamepadManager.ACTION_SWAP_SCREENS -> {
                    viewModel.toggleScreenSwap()
                }
            }
        }
    }

    LaunchedEffect(gamepadUiActive) {
        GamepadManager.setEmulationInputEnabled(!gamepadUiActive)
    }

    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(gamepadUiActive) {
        if (!gamepadUiActive) {
            softwareKeyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    DisposableEffect(touchPadIndex, shouldShowOverlay, uiState.showMenu, showControlsEditor) {
        val activeTouchPadIndex = touchPadIndex?.takeIf {
            shouldShowOverlay && !uiState.showMenu && !showControlsEditor
        }
        onDispose {
            activeTouchPadIndex?.let(EmulatorBridge::resetPadState)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            SoftwareKeyboard.cancelPending()
            GamepadManager.setEmulationInputEnabled(false)
            viewModel.stopEmulationIfCurrent(gamePath)
        }
    }

    DisposableEffect(uiState.keepScreenOn) {
        val previousValue = view.keepScreenOn
        view.keepScreenOn = uiState.keepScreenOn
        onDispose {
            view.keepScreenOn = previousValue
        }
    }

    val gameSurfaceView = remember { mutableStateOf<SurfaceView?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.saveStatePreviewRequests.collect { request ->
            var captured = false
            try {
                val surfaceView = gameSurfaceView.value
                if (request.isPending && surfaceView != null) {
                    captured = captureSaveStatePreview(surfaceView, request.savePath, request.dualScreenPortrait)
                    if (captured) viewModel.refreshSaveStateMetadataNow()
                }
            } finally {
                request.complete(captured)
            }
        }
    }

    val nativeDisplayDrawRect by produceState<FloatArray?>(
        initialValue = null,
        key1 = uiState.isRunning
    ) {
        if (!uiState.isRunning) return@produceState
        while (true) {
            val next = runCatching { NativeApp.getDisplayDrawRect() }
                .getOrNull()
                ?.takeIf { it.size >= 4 && it[2] > it[0] && it[3] > it[1] }
            val current = value
            // Only publish actual changes: a fresh array every poll would
            // recompose the whole screen several times per second, which makes
            // the side artwork visibly jitter over the emulated image.
            if (next == null) {
                if (current != null) value = null
            } else if (current == null || !current.contentEquals(next)) {
                value = next
            }
            delay(100.milliseconds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Game surface
        val surfaceGeneration = presentationSurfaceGeneration
        key(surfaceGeneration) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(gameCutoutPadding)
                    .padding(bottom = viewportControls.reservedBottomDp.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            gameSurfaceView.value = this
                            isClickable = false
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setZOrderOnTop(false)
                            setZOrderMediaOverlay(false)
                            holder.setFormat(PixelFormat.OPAQUE)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    try { EmulatorBridge.onSurfaceCreated(surfaceGeneration) } catch (_: Exception) { }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    try {
                                        EmulatorBridge.onSurfaceChanged(
                                            holder.surface,
                                            width,
                                            height,
                                            surfaceGeneration
                                        )
                                    } catch (_: Exception) { }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    try { EmulatorBridge.onSurfaceDestroyed(surfaceGeneration) } catch (_: Exception) { }
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInteropFilter { event ->
                            if (!isNds && !singleScreenConsole && !showControlsEditor && !uiState.showMenu && event.actionMasked == MotionEvent.ACTION_DOWN) {
                                val timestamp = event.eventTime
                                val dx = event.x - lastTapX
                                val dy = event.y - lastTapY
                                val distanceSquared = (dx * dx) + (dy * dy)
                                val isDoubleTap = timestamp - lastTapTimestamp in 40L..300L && distanceSquared <= 14400f
                                if (isDoubleTap) {
                                    showOverlayShortcut = true
                                    secondScreenTabRevealSignal++
                                    lastTapTimestamp = 0L
                                } else {
                                    lastTapTimestamp = timestamp
                                    lastTapX = event.x
                                    lastTapY = event.y
                                }
                            }
                            false
                        }
                )
                if (isNds) {
                    DsTouchscreenLayer(
                        modifier = Modifier.fillMaxSize(),
                        swapped = uiState.swapScreens,
                        framebufferHeight = if (uiState.renderer == 13) 384 else 386,
                        enabled = !showControlsEditor && !uiState.showMenu,
                        onTouch = { x, y, pressed -> DsSession.touch(x, y, pressed) }
                    )
                }
            }
        }

        // SurfaceView can retain the previous game's final buffer across a fast restart.
        // Keep it covered until the new renderer publishes its first authoritative draw rect.
        if (nativeDisplayDrawRect == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        EmulationSideArtworkOverlay(
            artwork = globalDefaults.emulationSideArtwork,
            revision = globalDefaults.emulationSideArtworkRevision,
            aspectRatioMode = uiState.aspectRatio,
            nativeDrawRect = nativeDisplayDrawRect,
            modifier = Modifier.fillMaxSize(),
            dimPercent = globalDefaults.emulationSideArtworkDim
        )

        if (!showControlsEditor) {
        // Top systemic gamepad overlay.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = overlayHorizontalSafeInset, end = overlayHorizontalSafeInset),
            contentAlignment = Alignment.TopCenter
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connected input indicators
                AnimatedVisibility(
                    visible = (gamepadConnected || keyboardConnected || mouseConnected) &&
                        !uiState.showMenu &&
                        showGamepadIndicator,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (gamepadConnected) {
                            ConnectedInputBadge(
                                icon = Icons.Rounded.Gamepad,
                                contentDescription = stringResource(R.string.gamepad_connected)
                            )
                        }
                        if (keyboardConnected) {
                            ConnectedInputBadge(
                                icon = Icons.Rounded.Keyboard,
                                contentDescription =
                                    stringResource(R.string.settings_keyboard_connected)
                            )
                        }
                        if (mouseConnected) {
                            ConnectedInputBadge(
                                icon = Icons.Rounded.Mouse,
                                contentDescription =
                                    stringResource(R.string.settings_mouse_connected)
                            )
                        }
                    }
                }

            }
        }

        // The native core owns performance statistics when its OSD is enabled.
        // Toast notifications
        AnimatedVisibility(
            visible = showOverlayShortcut && !uiState.showMenu && !showControlsEditor,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.88f, animationSpec = tween(180)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.88f, animationSpec = tween(160)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = overlayTopSafeInset + 12.dp)
        ) {
            Surface(
                onClick = {
                    showOverlayShortcut = false
                    toggleMenuClick()
                },
                shape = neonShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = stringResource(R.string.emulation_settings),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Toast notifications
        AnimatedVisibility(
            visible = uiState.toastMessage != null,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = overlayHorizontalSafeInset, end = overlayHorizontalSafeInset)
                .zIndex(40f)
        ) {
            val message = when (uiState.toastMessage) {
                "saved" -> stringResource(R.string.emulation_saved)
                "save_failed" -> stringResource(R.string.emulation_save_failed)
                "loaded" -> stringResource(R.string.emulation_loaded)
                "load_failed" -> stringResource(R.string.emulation_load_failed)
                "launch_failed" -> stringResource(R.string.emulation_launch_failed)
                "launch_path_error" -> stringResource(R.string.emulation_launch_path_error)
                else -> ""
            }
            Box(
                modifier = Modifier
                    .clip(neonShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                GradientStart.copy(alpha = 0.9f),
                                GradientEnd.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
            }
        }

        // RetroAchievements banner: session notification and achievement unlocks
        achievementUnlockBanner?.let { banner ->
            val isGameLoaded = banner.type == "game_loaded"
            val accent = if (isGameLoaded) Color(0xFF7DDFA8) else Color(0xFFFFD35A)
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { height -> -height },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { height -> -height },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        top = overlayTopSafeInset + 18.dp,
                        start = overlayHorizontalSafeInset + 12.dp,
                        end = overlayHorizontalSafeInset
                    )
                    .zIndex(41f)
            ) {
                Surface(
                    onClick = { achievementUnlockBanner = null },
                    shape = neonShape(18.dp),
                    color = Color(0xF214151A),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                    shadowElevation = 12.dp,
                    modifier = Modifier.widthIn(min = 300.dp, max = 430.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(neonShape(14.dp))
                                .background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (banner.badgeUrl.isNotBlank()) {
                                AsyncImage(
                                    model = banner.badgeUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(neonShape(14.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.EmojiEvents,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isGameLoaded) {
                                        "RetroAchievements"
                                    } else {
                                        stringResource(R.string.achievements_title)
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = accent,
                                    maxLines = 1
                                )
                                if (!isGameLoaded && banner.points > 0) {
                                    RaBadge(
                                        text = stringResource(R.string.achievements_points_badge, banner.points),
                                        containerColor = accent.copy(alpha = 0.18f),
                                        contentColor = accent
                                    )
                                }
                            }
                            Text(
                                text = if (isGameLoaded) {
                                    stringResource(R.string.achievements_game_loaded, banner.title)
                                } else {
                                    banner.title
                                },
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (banner.description.isNotBlank()) {
                                Text(
                                    text = banner.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Full screen loading overlay specifically for loading save state
        AnimatedVisibility(
            visible = (uiState.isActionInProgress && uiState.actionLabel == "loading") || uiState.statusMessage == "status_loading_state",
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .clip(neonShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                    Text(
                        text = stringResource(R.string.emulation_status_loading_state),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.statusMessage != null && !uiState.isActionInProgress && uiState.statusMessage != "status_loading_state",
            enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.9f, animationSpec = tween(250)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            val statusText = when (uiState.statusMessage) {
                "status_preparing" -> stringResource(R.string.emulation_status_preparing)
                "status_checking_bios" -> stringResource(R.string.emulation_status_checking_bios)
                "status_loading_game" -> stringResource(R.string.emulation_status_loading_game)
                "status_starting_core" -> stringResource(R.string.emulation_status_starting_core)
                "status_waiting_vm" -> stringResource(R.string.emulation_status_waiting_vm)
                "status_running" -> stringResource(R.string.emulation_status_running)
                "status_applying_config" -> stringResource(R.string.emulation_status_applying_config)
                "status_saving" -> stringResource(R.string.emulation_status_saving)
                "status_loading_state" -> stringResource(R.string.emulation_status_loading_state)
                else -> ""
            }
            val isRunningStatus = uiState.statusMessage == "status_running"
            Box(
                modifier = Modifier
                    .clip(neonShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = if (isRunningStatus) listOf(
                                Color(0xFF50D9A0).copy(alpha = 0.85f),
                                Color(0xFF3ABB88).copy(alpha = 0.85f)
                            ) else listOf(
                                Color.Black.copy(alpha = 0.7f),
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = if (isRunningStatus) Color(0xFF50D9A0).copy(alpha = 0.4f)
                                else Color.White.copy(alpha = 0.12f),
                        shape = neonShape(18.dp)
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isRunningStatus) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White.copy(alpha = 0.85f),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        EmulationPerformanceOverlay(
            viewModel = viewModel,
            uiState = uiState,
            showControlsEditor = showControlsEditor,
            overlayHorizontalSafeInset = overlayHorizontalSafeInset,
            overlayTopSafeInset = overlayTopSafeInset,
            overlayBottomSafeInset = overlayBottomSafeInset
        )

        AnimatedVisibility(
            visible = uiState.transportMode != EmulationTransportMode.None &&
                !uiState.showMenu &&
                !showControlsEditor,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.94f, animationSpec = tween(120)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = overlayTopSafeInset + 14.dp)
                .zIndex(32f)
        ) {
            TransportStatusOverlay(uiState.transportMode)
        }

        // On-screen controls
        if (shouldShowOverlay && !uiState.showMenu && !showControlsEditor) {
            val scaleFactor = uiState.overlayScale / 100f
            val alpha = uiState.overlayOpacity / 100f
            // A gamepad is a physical control surface, so its layout must not mirror with
            // the app's RTL locale. The touch hit boxes use absolute screen coordinates;
            // keeping the visual overlay LTR guarantees that visible and logical buttons
            // stay aligned in Arabic, Persian, and every other RTL language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OnScreenControls(
                    modifier = if (isNds) {
                        Modifier
                            .fillMaxWidth()
                            .height(dsControlsHeight)
                            .align(Alignment.BottomCenter)
                            .zIndex(20f)
                            .graphicsLayer(alpha = alpha)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .zIndex(20f)
                            .graphicsLayer(alpha = alpha)
                    },
                    scaleFactor = if (isNds) dsControlsScale else scaleFactor,
                    stickScaleFactor = uiState.stickScale / 100f,
                    leftStickSensitivity = uiState.leftStickSensitivity / 100f,
                    rightStickSensitivity = uiState.rightStickSensitivity / 100f,
                    invertLeftStick = uiState.invertLeftStick,
                    invertRightStick = uiState.invertRightStick,
                    invertLeftStickHorizontal = uiState.invertLeftStickHorizontal,
                    invertRightStickHorizontal = uiState.invertRightStickHorizontal,
                    rightStickUpToR2 = uiState.gamepadRightStickUpToR2 && overlayPolicy.supportsAction("zr"),
                    rightStickDownToL2 = uiState.gamepadRightStickDownToL2 && overlayPolicy.supportsAction("zl"),
                    touchscreenRightStick = overlayPolicy.rightAnalog && uiState.touchscreenRightStick,
                    touchscreenRightStickSensitivity = uiState.touchscreenRightStickSensitivity / 100f,
                    touchscreenLeftStick = overlayPolicy.leftAnalog && uiState.touchscreenLeftStick,
                    touchscreenLeftStickSensitivity = uiState.touchscreenLeftStickSensitivity / 100f,
                    touchHaptics = uiState.touchHaptics,
                    touchHapticsPreset = uiState.touchHapticsPreset,
                    touchHapticsStrength = uiState.touchHapticsStrength,
                    visualStyle = uiState.touchControlVisualStyle,
                    pressEffect = uiState.touchControlPressEffect,
                    customControls = overlayPolicy.customControls(uiState.customTouchControls.controls),
                    dpadOffset = uiState.dpadOffset,
                    lstickOffset = uiState.lstickOffset,
                    rstickOffset = uiState.rstickOffset,
                    actionOffset = uiState.actionOffset,
                    lbtnOffset = uiState.lbtnOffset,
                    rbtnOffset = uiState.rbtnOffset,
                    centerOffset = uiState.centerOffset,
                    controlLayouts = uiState.controlLayouts,
                    racingMode = if (isNds) false else uiState.racingMode,
                    stickyButtons = if (isNds) emptySet() else uiState.stickyButtons,
                    stickToggleTarget = uiState.stickToggleTarget,
                    onToggleLeftInputMode = viewModel::toggleLeftInputMode,
                    onSwapScreens = viewModel::toggleScreenSwap,
                    onPadInput = { keyCode, range, pressed ->
                        viewModel.onPadInput(overlayPadIndex, keyCode, range, pressed)
                    },
                    // Keep controls clear of the camera cutout and system bars, regardless
                    // of whether the user allows the game image itself into the cutout.
                    safeInsets = PaddingValues(
                        start = overlayLeftSafeInset,
                        top = overlayTopSafeInset,
                        end = overlayRightSafeInset,
                        bottom = overlayBottomSafeInset
                    ),
                    onScreenDoubleTap = { if (!isNds && !singleScreenConsole) secondScreenTabRevealSignal++ },
                    dsMode = isNds,
                    consolePolicy = overlayPolicy
                )
            }
        }
        }

        if (floatingQuickActionsEnabled && !uiState.showMenu && !showControlsEditor) {
            FloatingQuickActionsLayer(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(26f),
                savePosition = floatingQuickSavePosition
                    ?: Offset(
                        persistedFloatingQuickSavePosition.first,
                        persistedFloatingQuickSavePosition.second
                    ),
                loadPosition = floatingQuickLoadPosition
                    ?: Offset(
                        persistedFloatingQuickLoadPosition.first,
                        persistedFloatingQuickLoadPosition.second
                    ),
                saveEnabled = !uiState.isActionInProgress,
                loadEnabled = !uiState.isActionInProgress,
                saveInProgress = uiState.actionLabel == "saving",
                loadInProgress = uiState.actionLabel == "loading",
                onSavePositionChange = { floatingQuickSavePosition = it },
                onLoadPositionChange = { floatingQuickLoadPosition = it },
                onSavePositionCommit = {
                    floatingQuickSavePosition?.let { position ->
                        scope.launch {
                            preferences.setFloatingQuickSavePosition(position.x, position.y)
                        }
                    }
                },
                onLoadPositionCommit = {
                    floatingQuickLoadPosition?.let { position ->
                        scope.launch {
                            preferences.setFloatingQuickLoadPosition(position.x, position.y)
                        }
                    }
                },
                onSave = requestQuickSaveClick,
                onLoad = requestQuickLoadClick
            )
        }

        if (showControlsEditor) {
            ControlsEditorScreen(
                state = uiState.toControlsEditorState(),
                onBackClick = { showControlsEditor = false },
                manageActivityOrientation = false,
                overlayLeftSafeInset = overlayLeftSafeInset,
                overlayRightSafeInset = overlayRightSafeInset,
                overlayTopSafeInset = overlayTopSafeInset,
                overlayBottomSafeInset = overlayBottomSafeInset,
                onUpdateControlOffset = viewModel::updateTouchControlOffset,
                onUpdateControlOffsets = viewModel::updateTouchControlOffsets,
                onUpdateControlScale = viewModel::updateTouchControlScale,
                onUpdateControlWidthScale = viewModel::updateTouchControlWidthScale,
                onUpdateControlOpacity = viewModel::updateTouchControlOpacity,
                onSetControlVisible = viewModel::setTouchControlVisible,
                onSetStickSurfaceMode = viewModel::setTouchStickSurfaceMode,
                onResetLayout = viewModel::resetTouchControlsLayout,
                onCustomControlsChange = viewModel::setCustomTouchControls,
                dsMode = isNds,
                consolePolicy = overlayPolicy
            )
        }

        // Pull-out second screen panel (bottom 3DS screen). Its z-index sits
        // above the full-screen touch controls layer so the docked tab stays
        // clickable, while the sidebar menu stays above the panel.
        if (!isNds && !singleScreenConsole && effectiveSecondScreenMode == AppPreferences.SECOND_SCREEN_MODE_PANEL &&
            !showControlsEditor
        ) {
            SecondScreenPanel(
                widthPercent = secondScreenWidth,
                position = secondScreenPanelPosition,
                open = secondScreenOpen,
                revealSignal = secondScreenTabRevealSignal,
                onOpenChange = { open ->
                    scope.launch { preferences.setSecondScreenOpen(open) }
                },
                onWidthPercentChange = { width ->
                    scope.launch { preferences.setSecondScreenWidth(width) }
                },
                onSwapScreens = {
                    viewModel.toggleScreenSwap()
                },
                onPositionChange = { x, y ->
                    scope.launch { preferences.setSecondScreenPanelPosition(x, y) }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(22f)
            )
        }

        // Free-floating bottom screen window.
        if (!isNds && !singleScreenConsole && effectiveSecondScreenMode == AppPreferences.SECOND_SCREEN_MODE_FLOATING &&
            !showControlsEditor
        ) {
            FloatingSecondScreen(
                position = secondScreenFloatPosition,
                sizePercent = secondScreenFloatSize,
                onPositionChange = { x, y ->
                    scope.launch { preferences.setSecondScreenFloatPosition(x, y) }
                },
                onSizePercentChange = { size ->
                    scope.launch { preferences.setSecondScreenFloatSize(size) }
                },
                onSwapScreens = {
                    viewModel.toggleScreenSwap()
                },
                onDock = {
                    scope.launch { preferences.setSecondScreenMode(AppPreferences.SECOND_SCREEN_MODE_PANEL) }
                },
                onClose = {
                    scope.launch { preferences.setSecondScreenMode(AppPreferences.SECOND_SCREEN_MODE_INTEGRATED) }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(22f)
            )
        }

        // Sidebar Menu
        AnimatedVisibility(
            visible = uiState.showMenu && !showControlsEditor,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) + fadeIn(tween(300)),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }) + fadeOut(tween(250)),
            modifier = Modifier
                .fillMaxHeight()
                .zIndex(50f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = toggleMenuClick
                        )
                )

                EmulationSidebarMenu(
                    uiState = uiState,
                    isNds = isNds,
                    gamepadConnected = gamepadConnected,
                    currentGamePath = gamePath,
                    globalDefaults = globalDefaults.forPlatform(PlatformUiCapabilities.forGame(gamePath).platform),
                    overlayDefaults = overlayDefaults.forConsoleControls(
                        ConsoleControlsScope(overlayPolicy.platform, overlayPolicy.wiiProfile)),
                    onClose = toggleMenuClick,
                    onSwapScreens = viewModel::toggleScreenSwap,
                    onPauseToggle = togglePauseClick,
                    onQuickSave = requestQuickSaveClick,
                    onQuickLoad = requestQuickLoadClick,
                    onLoadAutoSave = requestAutoSaveLoadClick,
                    onSetAutoSaveEnabled = { viewModel.setAutoSaveEnabled(it) },
                    onSetAutoSaveIntervalMinutes = { viewModel.setAutoSaveIntervalMinutes(it) },
                    onSetAutoSaveOnExit = { viewModel.setAutoSaveOnExit(it) },
                    onSetAutoLoadOnStart = { viewModel.setAutoLoadOnStart(it) },
                    onSaveGameSettingsProfile = { viewModel.saveCurrentGameSettingsProfile() },
                    onResetGameSettingsProfile = { viewModel.resetCurrentGameSettingsProfile() },
                    onSetCoreOption = { key, value -> viewModel.setCoreOption(key, value) },
                    onNextSlot = { viewModel.setSlot(uiState.currentSlot + 1) },
                    onPrevSlot = { viewModel.setSlot(uiState.currentSlot - 1) },
                    onToggleFps = { viewModel.toggleFpsVisibility() },
                    onSetFrameLimit = { viewModel.toggleFrameLimit() },
                    onSetFrameSkip = { viewModel.setFrameSkip(it) },
                    onSetFpsOverlayMode = { viewModel.setFpsOverlayMode(it) },
                    onSetFpsOverlayCorner = { viewModel.setFpsOverlayCorner(it) },
                    onSetFpsOverlayScale = { viewModel.setFpsOverlayScale(it) },
                    onSetFpsOverlayMetrics = { viewModel.setFpsOverlayMetrics(it) },
                    onSetOverlayScale = { viewModel.setOverlayScale(it) },
                    onSetOverlayOpacity = { viewModel.setOverlayOpacity(it) },
                    onSetHideOverlayOnGamepad = { viewModel.setHideOverlayOnGamepad(it) },
                    onSetKeepScreenOn = { viewModel.setKeepScreenOn(it) },
                    onSetStickScale = { viewModel.setStickScale(it) },
                    onSetLeftStickSensitivity = { viewModel.setLeftStickSensitivity(it) },
                    onSetRightStickSensitivity = { viewModel.setRightStickSensitivity(it) },
                    onSetInvertLeftStick = { viewModel.setInvertLeftStick(it) },
                    onSetInvertRightStick = { viewModel.setInvertRightStick(it) },
                    onSetInvertLeftStickHorizontal = { viewModel.setInvertLeftStickHorizontal(it) },
                    onSetInvertRightStickHorizontal = { viewModel.setInvertRightStickHorizontal(it) },
                    onSetGamepadStickDeadzone = { viewModel.setGamepadStickDeadzone(it) },
                    onSetGamepadLeftStickSensitivity = { viewModel.setGamepadLeftStickSensitivity(it) },
                    onSetGamepadRightStickSensitivity = { viewModel.setGamepadRightStickSensitivity(it) },
                    onSetGamepadRightStickUpToR2 = { viewModel.setGamepadRightStickUpToR2(it) },
                    onSetGamepadRightStickDownToL2 = { viewModel.setGamepadRightStickDownToL2(it) },
                    onSetTouchscreenRightStick = { viewModel.setTouchscreenRightStick(it) },
                    onSetTouchscreenRightStickSensitivity = { viewModel.setTouchscreenRightStickSensitivity(it) },
                    onSetTouchscreenLeftStick = { viewModel.setTouchscreenLeftStick(it) },
                    onSetTouchscreenLeftStickSensitivity = { viewModel.setTouchscreenLeftStickSensitivity(it) },
                    onToggleControls = toggleControlsClick,
                    onOpenControlsEditor = { showControlsEditor = true },
                    onOpenGamepadMapping = { showGamepadMappingDialog = true },
                    onOpenKeyboardMouseSettings = { showKeyboardMouseSettingsDialog = true },
                    onSetRenderer = { viewModel.setRenderer(it) },
                    onSetUpscale = { viewModel.setUpscale(it) },
                    onSetAspectRatio = { viewModel.setAspectRatio(it) },
                    onSetDisplayCrop = { viewModel.setDisplayCrop(it) },
                    onSetEnableCheats = { viewModel.setEnableCheats(it) },
                    onToggleCheat = { id, enabled -> viewModel.setCheatEnabled(id, enabled) },
                    onSetCheatGroupEnabled = { ids, enabled -> viewModel.setCheatGroupEnabled(ids, enabled) },
                    onRefreshCheats = { viewModel.refreshAvailableCheats() },
                    onSetAudioVolume = { viewModel.setAudioVolume(it) },
                    onSetAudioMuted = { viewModel.setAudioMuted(it) },
                    onExit = requestExitClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                )
                if (LocalNeonTheme.current) {
                    NeonCrtOverlay()
                }
            }
        }
    }

    uiState.runtimeFailure?.let { failure ->
        RuntimeFailureDialog(failure, onExit = confirmExitClick)
    }

    if (showExitDialog && uiState.runtimeFailure == null) {
        AlertDialog(
            onDismissRequest = dismissExitClick,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.emulation_exit_confirm),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.emulation_exit_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = confirmExitClick) {
                    Text(
                        text = stringResource(R.string.yes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = dismissExitClick) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    if (showQuickSaveDialog && uiState.runtimeFailure == null) {
        AlertDialog(
            onDismissRequest = dismissQuickSaveClick,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = neonShape(24.dp),
            title = {
                Text(
                    stringResource(R.string.emulation_quick_save_confirm_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    stringResource(R.string.emulation_quick_save_confirm_desc, uiState.currentSlot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = confirmQuickSaveClick) {
                    Text(stringResource(R.string.emulation_quick_save))
                }
            },
            dismissButton = {
                TextButton(onClick = dismissQuickSaveClick) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    if (showQuickLoadDialog && uiState.runtimeFailure == null) {
        AlertDialog(
            onDismissRequest = dismissQuickLoadClick,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = neonShape(24.dp),
            title = {
                Text(
                    stringResource(R.string.emulation_quick_load_confirm_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    stringResource(R.string.emulation_quick_load_confirm_desc, uiState.currentSlot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = confirmQuickLoadClick) {
                    Text(stringResource(R.string.emulation_quick_load))
                }
            },
            dismissButton = {
                TextButton(onClick = dismissQuickLoadClick) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    if (showAutoSaveLoadDialog && uiState.runtimeFailure == null) {
        AlertDialog(
            onDismissRequest = dismissAutoSaveLoadClick,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = neonShape(24.dp),
            title = {
                Text(
                    stringResource(R.string.emulation_quick_load_confirm_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    stringResource(R.string.emulation_quick_load_confirm_desc, 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = confirmAutoSaveLoadClick) {
                    Text(stringResource(R.string.emulation_quick_load))
                }
            },
            dismissButton = {
                TextButton(onClick = dismissAutoSaveLoadClick) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    softwareKeyboardRequest?.let { request ->
        SoftwareKeyboardDialog(request)
    }

    if (showGamepadMappingDialog && uiState.runtimeFailure == null) {
        Dialog(onDismissRequest = dismissGamepadMappingDialog) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = neonShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val connectedControllerName = GamepadManager.connectedControllerName(selectedGamepadPadIndex)
                    val selectedBindings = effectiveGamepadBindingsByPad[selectedGamepadPadIndex].orEmpty()
                    Column(
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_gamepad_mapping_title),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1).forEach { padIndex ->
                                FilterChip(
                                    shape = neonChipShape(),
                                    selected = selectedGamepadPadIndex == padIndex,
                                    onClick = { selectedGamepadPadIndex = padIndex },
                                    colors = premiumFilterChipColors(),
                                    label = { Text(gamepadPlayerLabel(padIndex)) }
                                )
                            }
                        }
                        Text(
                            text = connectedControllerName?.let {
                                stringResource(
                                    R.string.settings_gamepad_mapping_player_connected,
                                    gamepadPlayerLabel(selectedGamepadPadIndex),
                                    it
                                )
                            } ?: stringResource(
                                R.string.settings_gamepad_mapping_player_disconnected,
                                gamepadPlayerLabel(selectedGamepadPadIndex)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        gamepadActions.forEach { action ->
                            val assignedKeyCode = GamepadManager.resolveBindingForAction(
                                actionId = action.id,
                                customBindings = selectedBindings
                            )
                            val isCustomBinding = selectedBindings.containsKey(action.id)
                            EmulationGamepadBindingRow(
                                title = gamepadActionLabel(action.id),
                                value = assignedKeyCode?.let(GamepadManager::keyCodeLabel)
                                    ?: stringResource(R.string.settings_not_set),
                                autoLabel = if (isCustomBinding || action.defaultKeyCodes.isEmpty()) {
                                    null
                                } else {
                                    stringResource(R.string.settings_gamepad_mapping_auto_format)
                                },
                                onBindClick = {
                                    pendingGamepadPadIndex = selectedGamepadPadIndex
                                    pendingGamepadActionId = action.id
                                },
                                onClearClick = if (isCustomBinding) {
                                    {
                                        scope.launch {
                                            if (uiState.gameSettingsProfileActive) {
                                                val currentBindings = uiState.gamepadBindingsByPad.toMutableMap()
                                                val padBindings = currentBindings[selectedGamepadPadIndex].orEmpty().toMutableMap()
                                                padBindings.remove(action.id)
                                                if (padBindings.isEmpty()) {
                                                    currentBindings.remove(selectedGamepadPadIndex)
                                                } else {
                                                    currentBindings[selectedGamepadPadIndex] = padBindings
                                                }
                                                viewModel.setGamepadBindingsByPad(currentBindings)
                                            } else {
                                                preferences.clearGamepadBinding(selectedGamepadPadIndex, action.id)
                                            }
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                        Box(modifier = Modifier.padding(bottom = 4.dp)) {
                            MenuButton(
                                icon = Icons.Rounded.SettingsSuggest,
                                text = stringResource(R.string.settings_gamepad_mapping_reset_title),
                                onClick = {
                                    scope.launch {
                                        if (uiState.gameSettingsProfileActive) {
                                            val currentBindings = uiState.gamepadBindingsByPad.toMutableMap()
                                            currentBindings.remove(selectedGamepadPadIndex)
                                            viewModel.setGamepadBindingsByPad(currentBindings)
                                        } else {
                                            preferences.resetGamepadBindingsForPad(selectedGamepadPadIndex)
                                        }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                    Column(
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = dismissGamepadMappingDialogClick) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKeyboardMouseSettingsDialog) {
        KeyboardMouseSettingsDialog(
            consolePolicy = overlayPolicy,
            settings = keyboardMouseOverride ?: globalKeyboardMouseSettings,
            onSettingsChange = viewModel::setKeyboardMouseSettings,
            onUseGlobal = if (keyboardMouseOverride != null) {
                { viewModel.setKeyboardMouseSettings(null) }
            } else {
                null
            },
            onDismiss = { showKeyboardMouseSettingsDialog = false }
        )
    }

    if (pendingGamepadActionId != null && uiState.runtimeFailure == null) {
        val dialogFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            dialogFocusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = { pendingGamepadActionId = null },
            modifier = Modifier
                .focusRequester(dialogFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    GamepadManager.handleBindingCapture(keyEvent.nativeKeyEvent)
                },
            title = { Text(stringResource(R.string.settings_gamepad_mapping_listening_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_gamepad_mapping_listening_player_desc,
                        gamepadPlayerLabel(pendingGamepadPadIndex),
                        gamepadActionLabel(pendingGamepadActionId.orEmpty())
                    )
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingGamepadActionId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun BoxScope.EmulationPerformanceOverlay(
    viewModel: EmulationViewModel,
    uiState: EmulationUiState,
    showControlsEditor: Boolean,
    overlayHorizontalSafeInset: Dp,
    overlayTopSafeInset: Dp,
    overlayBottomSafeInset: Dp
) {
    // Metrics change four times per second; keep their state reads below the
    // screen's restart scope so they only recompose the monitoring overlay.
    val performance by viewModel.performanceState.collectAsState()
    val showPerformanceHud = uiState.showFps &&
        !uiState.showMenu &&
        !showControlsEditor &&
        (uiState.fpsOverlayMode == FPS_OVERLAY_MODE_SIMPLE || performance.overlayText.isNotBlank())
    AnimatedVisibility(
        visible = showPerformanceHud,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
        modifier = Modifier
            .align(uiState.fpsOverlayCorner.toOverlayAlignment())
            .padding(
                start = if (uiState.fpsOverlayCorner.isRightOverlayCorner()) 0.dp else overlayHorizontalSafeInset + 12.dp,
                top = if (uiState.fpsOverlayCorner.isTopOverlayCorner()) overlayTopSafeInset + 12.dp else 0.dp,
                end = if (uiState.fpsOverlayCorner.isRightOverlayCorner()) overlayHorizontalSafeInset + 12.dp else 0.dp,
                bottom = if (uiState.fpsOverlayCorner.isBottomOverlayCorner()) overlayBottomSafeInset + 12.dp else 0.dp
            )
            .zIndex(30f)
    ) {
        if (uiState.fpsOverlayMode == FPS_OVERLAY_MODE_SIMPLE) {
            SimpleFpsCounter(
                fps = performance.fps,
                fontScale = uiState.fpsOverlayScale / 100f
            )
        } else {
            SystemPerformanceHud(
                speedPercent = performance.speedPercent,
                text = performance.overlayText,
                fixedHeaderLine = uiState.performanceOverlayHeader,
                isRightCorner = uiState.fpsOverlayCorner.isRightOverlayCorner(),
                fontScale = uiState.fpsOverlayScale / 100f,
                metricsMask = uiState.fpsOverlayMetrics
            )
        }
    }
}

private fun GameMenuTabId.toEmulationMenuTab(): EmulationMenuTab = when (this) {
    GameMenuTabId.SESSION -> EmulationMenuTab.Session
    GameMenuTabId.CONTROLS -> EmulationMenuTab.Controls
    GameMenuTabId.EMULATION -> EmulationMenuTab.Emulation
    GameMenuTabId.CHEATS -> EmulationMenuTab.Cheats
    GameMenuTabId.GRAPHICS -> EmulationMenuTab.Graphics
    GameMenuTabId.ACHIEVEMENTS -> EmulationMenuTab.Achievements
}

@Composable
private fun OnScreenControls(
    modifier: Modifier = Modifier,
    scaleFactor: Float,
    stickScaleFactor: Float = 1.0f,
    leftStickSensitivity: Float = 1.0f,
    rightStickSensitivity: Float = 1.0f,
    invertLeftStick: Boolean = false,
    invertRightStick: Boolean = false,
    invertLeftStickHorizontal: Boolean = false,
    invertRightStickHorizontal: Boolean = false,
    rightStickUpToR2: Boolean = false,
    rightStickDownToL2: Boolean = false,
    touchscreenRightStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK,
    touchscreenRightStickSensitivity: Float = 1.0f,
    touchscreenLeftStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_LEFT_STICK,
    touchscreenLeftStickSensitivity: Float = 1.0f,
    touchHaptics: Boolean = false,
    touchHapticsPreset: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET,
    touchHapticsStrength: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_STRENGTH,
    visualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    pressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    customControls: List<CustomTouchControl> = emptyList(),
    dpadOffset: Pair<Float, Float>,
    lstickOffset: Pair<Float, Float>,
    rstickOffset: Pair<Float, Float>,
    actionOffset: Pair<Float, Float>,
    lbtnOffset: Pair<Float, Float>,
    rbtnOffset: Pair<Float, Float>,
    centerOffset: Pair<Float, Float>,
    controlLayouts: Map<String, OverlayControlLayout>,
    racingMode: Boolean,
    stickyButtons: Set<String> = emptySet(),
    stickToggleTarget: Int = AppPreferences.DEFAULT_STICK_TOGGLE_TARGET,
    onToggleLeftInputMode: () -> Unit,
    onPadInput: (Int, Int, Boolean) -> Unit,
    onSwapScreens: () -> Unit = {},
    onScreenDoubleTap: () -> Unit = {},
    respectSystemInsets: Boolean = true,
    safeInsets: PaddingValues? = null,
    dsMode: Boolean = false,
    consolePolicy: ConsoleOverlayPolicy = ConsoleOverlayPolicy.forPlatform(com.sbro.emucored.core.platform.ConsolePlatform.N3DS)
) {
    val density = LocalDensity.current
    val safeDrawingPadding = safeInsets ?: WindowInsets.safeDrawing.asPaddingValues()
    val safeLeft = if (respectSystemInsets) {
        safeDrawingPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    } else 0.dp
    val safeRight = if (respectSystemInsets) {
        safeDrawingPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    } else 0.dp
    val safeTop = if (respectSystemInsets) safeDrawingPadding.calculateTopPadding() else 0.dp
    val safeBottom = if (respectSystemInsets) safeDrawingPadding.calculateBottomPadding() else 0.dp
    val context = LocalContext.current
    val hapticView = LocalView.current
    val currentOnPadInput by rememberUpdatedState(onPadInput)
    var touchL2Pressed by remember { mutableStateOf(false) }
    var touchR2Pressed by remember { mutableStateOf(false) }

    fun performTouchHaptic(phase: ButtonPhase) {
        if (touchHaptics) {
            AndroidTouchHaptics.playButton(
                context = context,
                view = hapticView,
                strengthPercent = touchHapticsStrength,
                preset = touchHapticsPreset,
                phase = phase
            )
        }
    }

    fun dispatchTouchAnalogDirection(key: Int, pressed: Boolean) {
        val range = if (pressed) 255 else 0
        currentOnPadInput(key, range, pressed)
    }

    fun dispatchTouchL2() {
        if (rightStickDownToL2) {
            currentOnPadInput(PadKey.ZL, 0, false)
            dispatchTouchAnalogDirection(PadKey.RIGHT_STICK_DOWN, touchL2Pressed)
        } else {
            currentOnPadInput(PadKey.RIGHT_STICK_DOWN, 0, false)
            dispatchTouchAnalogDirection(PadKey.ZL, touchL2Pressed)
        }
    }

    fun dispatchTouchR2() {
        if (rightStickUpToR2) {
            currentOnPadInput(PadKey.ZR, 0, false)
            dispatchTouchAnalogDirection(PadKey.RIGHT_STICK_UP, touchR2Pressed)
        } else {
            currentOnPadInput(PadKey.RIGHT_STICK_UP, 0, false)
            dispatchTouchAnalogDirection(PadKey.ZR, touchR2Pressed)
        }
    }

    LaunchedEffect(rightStickUpToR2) {
        currentOnPadInput(PadKey.ZR, 0, false)
        currentOnPadInput(PadKey.RIGHT_STICK_UP, 0, false)
        dispatchTouchR2()
    }

    LaunchedEffect(rightStickDownToL2) {
        currentOnPadInput(PadKey.ZL, 0, false)
        currentOnPadInput(PadKey.RIGHT_STICK_DOWN, 0, false)
        dispatchTouchL2()
    }

    fun buttonPressHandler(id: String): ((Boolean) -> Unit)? = when (consolePolicy.inputAlias(id)) {
        "zl" -> { pressed ->
            touchL2Pressed = pressed
            dispatchTouchL2()
        }
        "l" -> { pressed -> onPadInput(PadKey.L, 0, pressed) }
        "zr" -> { pressed ->
            touchR2Pressed = pressed
            dispatchTouchR2()
        }
        "r" -> { pressed -> onPadInput(PadKey.R, 0, pressed) }
        "dpad_up", "up" -> { pressed -> onPadInput(PadKey.UP, 0, pressed) }
        "dpad_down", "down" -> { pressed -> onPadInput(PadKey.DOWN, 0, pressed) }
        "dpad_left", "left" -> { pressed -> onPadInput(PadKey.LEFT, 0, pressed) }
        "dpad_right", "right" -> { pressed -> onPadInput(PadKey.RIGHT, 0, pressed) }
        "x" -> { pressed -> onPadInput(PadKey.X, 0, pressed) }
        "b" -> { pressed -> onPadInput(PadKey.B, 0, pressed) }
        "y" -> { pressed -> onPadInput(PadKey.Y, 0, pressed) }
        "a" -> { pressed -> onPadInput(PadKey.A, 0, pressed) }
        "select" -> { pressed -> onPadInput(PadKey.SELECT, 0, pressed) }
        "start" -> { pressed -> onPadInput(PadKey.START, 0, pressed) }
        "fast_forward" -> { pressed -> onPadInput(126, 0, pressed) }
        "rewind" -> { pressed -> onPadInput(127, 0, pressed) }
        "home" -> { pressed -> onPadInput(PadKey.HOME, 0, pressed) }
        "swap_screen" -> { pressed -> if (pressed) onSwapScreens() }
        "touch" -> { pressed -> onPadInput(PadKey.TOUCH, 0, pressed) }
        else -> null
    }

    fun isRacingTapToHoldButton(id: String): Boolean = id in setOf(
        "zl", "l", "zr", "r",
        "x", "b", "y", "a"
    )

    BoxWithConstraints(modifier = modifier) {
        val layout = buildOverlayCanvasLayout(
            canvasWidth = maxWidth,
            canvasHeight = maxHeight,
            density = density,
            scaleFactor = scaleFactor,
            stickScaleFactor = stickScaleFactor,
            dpadOffset = dpadOffset,
            lstickOffset = lstickOffset,
            rstickOffset = rstickOffset,
            actionOffset = actionOffset,
            lbtnOffset = lbtnOffset,
            rbtnOffset = rbtnOffset,
            centerOffset = centerOffset,
            controlLayouts = controlLayouts,
            safeLeftInset = safeLeft,
            safeRightInset = safeRight,
            safeTopInset = safeTop,
            safeBottomInset = safeBottom,
            stickToggleTarget = stickToggleTarget,
            dsMode = dsMode,
            consolePolicy = consolePolicy
        )
        var extraDpadDirections by remember { mutableStateOf(emptySet<OverlayDpadDirection>()) }
        val currentExtraDpadDirections by rememberUpdatedState(extraDpadDirections)
        var toggleDpadDirections by remember { mutableStateOf(emptySet<OverlayDpadDirection>()) }
        val currentToggleDpadDirections by rememberUpdatedState(toggleDpadDirections)

        fun dpadKeyFor(direction: OverlayDpadDirection): Int = when (direction) {
            OverlayDpadDirection.Up -> PadKey.UP
            OverlayDpadDirection.Down -> PadKey.DOWN
            OverlayDpadDirection.Left -> PadKey.LEFT
            OverlayDpadDirection.Right -> PadKey.RIGHT
        }

        fun updateExtraDpadDirections(next: Set<OverlayDpadDirection>) {
            val released = extraDpadDirections - next
            val pressed = next - extraDpadDirections
            released.forEach { direction -> currentOnPadInput(dpadKeyFor(direction), 0, false) }
            if (pressed.isNotEmpty()) {
                performTouchHaptic(ButtonPhase.PRESS)
            } else if (released.isNotEmpty()) {
                performTouchHaptic(ButtonPhase.RELEASE)
            }
            pressed.forEach { direction -> currentOnPadInput(dpadKeyFor(direction), 0, true) }
            extraDpadDirections = next
        }

        fun updateToggleDpadDirections(next: Set<OverlayDpadDirection>) {
            val released = toggleDpadDirections - next
            val pressed = next - toggleDpadDirections
            released.forEach { direction -> currentOnPadInput(dpadKeyFor(direction), 0, false) }
            if (pressed.isNotEmpty()) {
                performTouchHaptic(ButtonPhase.PRESS)
            } else if (released.isNotEmpty()) {
                performTouchHaptic(ButtonPhase.RELEASE)
            }
            pressed.forEach { direction -> currentOnPadInput(dpadKeyFor(direction), 0, true) }
            toggleDpadDirections = next
        }

        DisposableEffect(Unit) {
            onDispose {
                currentExtraDpadDirections.forEach { direction ->
                    currentOnPadInput(dpadKeyFor(direction), 0, false)
                }
                currentToggleDpadDirections.forEach { direction ->
                    currentOnPadInput(dpadKeyFor(direction), 0, false)
                }
            }
        }

        fun runtimeSpecs(specs: List<com.sbro.emucored.ui.common.OverlayCanvasButtonSpec>): List<TouchButtonSpec> {
            return specs.filter { it.visible }.map { spec ->
                val pressActions = listOfNotNull(
                    buttonPressHandler(spec.id)?.let {
                        TouchButtonPressAction(touchButtonActionOwner(consolePolicy.inputAlias(spec.id)), it)
                    }
                )
                // Console-specific glyphs (Z, 1, 2, +, −, C) keep the exact same
                // outline artwork as every other overlay button; only the label
                // changes. Never fall back to a different text-button skin.
                val label = consolePolicy.buttonLabel(spec.id)
                val drawable = when {
                    label == null -> spec.drawableRes
                    spec.id in setOf("a", "b", "x", "y", "home") ->
                        R.drawable.ic_controller_blank_face_button
                    else -> R.drawable.ic_controller_blank_shoulder_button
                }
                TouchButtonSpec(
                    id = spec.id,
                    drawableRes = drawable,
                    labelOverride = label,
                    width = spec.width,
                    height = spec.height,
                    x = spec.x,
                    y = spec.y,
                    shape = spec.shape,
                    opacity = spec.opacity / 100f,
                    pressActions = pressActions,
                    onClick = if (spec.id == "left_input_toggle") onToggleLeftInputMode else null,
                    tapToHold = (racingMode && isRacingTapToHoldButton(spec.id)) ||
                        spec.id in stickyButtons
                )
            }
        }

        fun stickIsSurfaceOnly(stick: com.sbro.emucored.ui.common.OverlayCanvasStickSpec): Boolean {
            return controlLayouts[stick.id]?.surfaceOnly == true
        }

        fun stickPanelWidth(stick: com.sbro.emucored.ui.common.OverlayCanvasStickSpec): Dp {
            return if (stickIsSurfaceOnly(stick)) stick.size * (stick.widthScale / 100f) else stick.size
        }

        fun stickPanelX(stick: com.sbro.emucored.ui.common.OverlayCanvasStickSpec): Dp {
            val width = stickPanelWidth(stick)
            return if (stickIsSurfaceOnly(stick)) stick.x - ((width - stick.size) / 2f) else stick.x
        }

        val rightStickTriggerVisualY = (
            (if (rightStickDownToL2 && touchL2Pressed) 1f else 0f) -
                (if (rightStickUpToR2 && touchR2Pressed) 1f else 0f)
            ).coerceIn(-1f, 1f)

        val allButtonSpecs = runtimeSpecs(
            layout.leftShoulders +
                layout.rightShoulders +
                layout.dpadButtons +
                layout.actionButtons +
                layout.centerButtons
        ) + customControls
            .asSequence()
            .filter(CustomTouchControl::enabled)
            .mapNotNull(CustomTouchControl::sanitized)
            .map { control ->
                val width = control.widthDp.dp
                val height = control.heightDp.dp
                val travelX = (maxWidth - safeLeft - safeRight - width).coerceAtLeast(0.dp)
                val travelY = (maxHeight - safeTop - safeBottom - height).coerceAtLeast(0.dp)
                val pressActions = listOfNotNull(
                    buttonPressHandler(control.actionId)?.let {
                        TouchButtonPressAction(touchButtonActionOwner(consolePolicy.inputAlias(control.actionId)), it)
                    },
                    control.secondaryActionId?.let { actionId ->
                        buttonPressHandler(actionId)?.let {
                            TouchButtonPressAction(touchButtonActionOwner(consolePolicy.inputAlias(actionId)), it)
                        }
                    }
                )
                TouchButtonSpec(
                    id = "custom:${control.id}",
                    width = width,
                    height = height,
                    x = safeLeft + travelX * control.positionX,
                    y = safeTop + travelY * control.positionY,
                    shape = control.composeShape(),
                    customControl = control,
                    labelOverride = if (control.usesVectorStyle) consolePolicy.buttonLabel(control.actionId) else null,
                    haptics = control.haptics,
                    pressActions = pressActions,
                    tapToHold = control.pressMode == CustomTouchControlPressMode.TOGGLE
                )
            }
            .toList()
        if (allButtonSpecs.isNotEmpty() || touchscreenRightStick || touchscreenLeftStick) {
            TouchButtonGroup(
                specs = allButtonSpecs,
                inputWidth = maxWidth,
                inputHeight = maxHeight,
                touchscreenRightStick = touchscreenRightStick,
                touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
                touchscreenLeftStick = touchscreenLeftStick,
                touchscreenLeftStickSensitivity = touchscreenLeftStickSensitivity,
                invertRightStick = invertRightStick,
                invertRightStickHorizontal = invertRightStickHorizontal,
                invertLeftStick = invertLeftStick,
                invertLeftStickHorizontal = invertLeftStickHorizontal,
                onPadInput = onPadInput,
                onScreenDoubleTap = onScreenDoubleTap,
                guestTouchEnabled = consolePolicy.guestTouch && !dsMode,
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                onTouchHaptic = ::performTouchHaptic
            )
        }

        layout.dpadCluster?.takeIf { it.visible }?.let { cluster ->
            val surface = cluster.surface
            VectorDpadCluster(
                size = cluster.size,
                alpha = cluster.opacity / 100f,
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                directionOffsets = cluster.directionOffsets,
                surface = surface,
                onDirectionsChange = ::updateExtraDpadDirections,
                modifier = Modifier.offset {
                    IntOffset(
                        (cluster.x + surface.offset.x).roundToPx(),
                        (cluster.y + surface.offset.y).roundToPx()
                    )
                }
            )
        }

        layout.toggleDpad?.takeIf { it.visible }?.let { cluster ->
            val surface = cluster.surface
            VectorDpadCluster(
                size = cluster.size,
                alpha = cluster.opacity / 100f,
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                surface = surface,
                onDirectionsChange = ::updateToggleDpadDirections,
                modifier = Modifier.offset {
                    IntOffset(
                        (cluster.x + surface.offset.x).roundToPx(),
                        (cluster.y + surface.offset.y).roundToPx()
                    )
                }
            )
        }

        layout.leftStick?.takeIf { it.visible }?.let { stick ->
            val panelWidth = stickPanelWidth(stick)
            VectorAnalogStick(
                analogSize = stick.size,
                analogWidth = panelWidth,
                analogHeight = stick.size,
                alpha = stick.opacity / 100f,
                surfaceOnly = stickIsSurfaceOnly(stick),
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                onValueChange = { x, y ->
                    updateAnalogStick(
                        x = if (invertLeftStickHorizontal) -x else x,
                        y = if (invertLeftStick) -y else y,
                        sensitivity = leftStickSensitivity,
                        upKey = PadKey.LEFT_STICK_UP,
                        rightKey = PadKey.LEFT_STICK_RIGHT,
                        downKey = PadKey.LEFT_STICK_DOWN,
                        leftKey = PadKey.LEFT_STICK_LEFT,
                        onPadInput = onPadInput
                    )
                },
                modifier = Modifier.offset {
                    IntOffset(stickPanelX(stick).roundToPx(), stick.y.roundToPx())
                }
            )
        }

        layout.rightStick?.takeIf { it.visible }?.let { stick ->
            val panelWidth = stickPanelWidth(stick)
            VectorAnalogStick(
                analogSize = stick.size,
                analogWidth = panelWidth,
                analogHeight = stick.size,
                alpha = stick.opacity / 100f,
                surfaceOnly = stickIsSurfaceOnly(stick),
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                visualY = rightStickTriggerVisualY,
                onValueChange = { x, y ->
                    updateRightAnalogStick(
                        x = if (invertRightStickHorizontal) -x else x,
                        y = if (invertRightStick) -y else y,
                        sensitivity = rightStickSensitivity,
                        onPadInput = onPadInput
                    )
                },
                modifier = Modifier.offset {
                    IntOffset(stickPanelX(stick).roundToPx(), stick.y.roundToPx())
                }
            )
        }

    }
}

internal fun forwardOverlayGuestTouch(x: Float, y: Float, pressed: Boolean) {
    val rect = NativeApp.getDisplayDrawRect() ?: return
    val width = rect[2] - rect[0]
    val height = rect[3] - rect[1]
    if (width <= 0f || height <= 0f) return
    val fractionX = ((x - rect[0]) / width).coerceIn(0f, 1f)
    val fractionY = ((y - rect[1]) / height).coerceIn(0f, 1f)
    NativeApp.setTouchState(
        x = (fractionX * FRAMEBUFFER_TOUCH_WIDTH).roundToInt(),
        y = (fractionY * FRAMEBUFFER_TOUCH_HEIGHT).roundToInt(),
        pressed = pressed
    )
}

@Composable
internal fun TouchButtonGroup(
    specs: List<TouchButtonSpec>,
    inputWidth: Dp,
    inputHeight: Dp,
    touchscreenRightStick: Boolean,
    touchscreenRightStickSensitivity: Float,
    touchscreenLeftStick: Boolean = false,
    touchscreenLeftStickSensitivity: Float = 1f,
    invertRightStick: Boolean,
    invertRightStickHorizontal: Boolean,
    invertLeftStick: Boolean = false,
    invertLeftStickHorizontal: Boolean = false,
    onPadInput: (Int, Int, Boolean) -> Unit,
    onScreenDoubleTap: () -> Unit = {},
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    onTouchHaptic: (ButtonPhase) -> Unit,
    modifier: Modifier = Modifier,
    guestTouchEnabled: Boolean = true,
    onGuestTouch: (Float, Float, Boolean) -> Unit = ::forwardOverlayGuestTouch
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val activeTargets = remember { mutableStateMapOf<Long, Set<String>>() }
    val downTargets = remember { mutableMapOf<Long, Set<String>>() }
    val latchedTargets = remember { mutableStateMapOf<String, Boolean>() }
    val longPressJobs = remember { mutableMapOf<Pair<Long, String>, Job>() }
    val longPressActiveTargets = remember { mutableStateMapOf<Long, Set<String>>() }
    val shortTapSequence = remember { AtomicLong() }
    val shortTapJobs = remember { mutableMapOf<String, Job>() }
    val shortTapActionOwners = remember { mutableMapOf<String, Set<String>>() }
    val specById = specs.associateBy { it.id }
    val currentSpecById by rememberUpdatedState(specById)
    val currentOnPadInput by rememberUpdatedState(onPadInput)
    val currentTouchscreenRightStickSensitivity by rememberUpdatedState(touchscreenRightStickSensitivity)
    val currentTouchscreenLeftStickSensitivity by rememberUpdatedState(touchscreenLeftStickSensitivity)
    val currentInvertRightStick by rememberUpdatedState(invertRightStick)
    val currentInvertRightStickHorizontal by rememberUpdatedState(invertRightStickHorizontal)
    val currentInvertLeftStick by rememberUpdatedState(invertLeftStick)
    val currentInvertLeftStickHorizontal by rememberUpdatedState(invertLeftStickHorizontal)
    val currentOnScreenDoubleTap by rememberUpdatedState(onScreenDoubleTap)
    val currentGuestTouchEnabled by rememberUpdatedState(guestTouchEnabled)
    val currentOnGuestTouch by rememberUpdatedState(onGuestTouch)
    var lastTouchscreenDownTime by remember { mutableStateOf(0L) }
    var lastTouchscreenDownPosition by remember { mutableStateOf(Offset.Zero) }
    var touchPointerId by remember { mutableStateOf<Long?>(null) }
    // One independent pointer per gesture stick so both can be dragged at the same time.
    var leftGesturePointerId by remember { mutableStateOf<Long?>(null) }
    var rightGesturePointerId by remember { mutableStateOf<Long?>(null) }
    var leftGestureOrigin by remember { mutableStateOf(Offset.Zero) }
    var rightGestureOrigin by remember { mutableStateOf(Offset.Zero) }
    val gestureRadiusPx = with(density) { 96.dp.toPx() }
    val layoutKey = specs.map { spec ->
        TouchButtonLayoutKey(
            id = spec.id,
            width = spec.width,
            height = spec.height,
            x = spec.x,
            y = spec.y,
            tapToHold = spec.tapToHold,
            hasLongPressAction = spec.onLongPressChange != null && spec.longPressDelayMs > 0L
        )
    }
    val actionOwnersByTarget = specs.associate { spec ->
        spec.id to spec.pressActions.mapTo(LinkedHashSet(), TouchButtonPressAction::ownerId)
    }
    val pointerActionTargets = specs.asSequence()
        .filterNot { it.tapToHold || (it.onLongPressChange != null && it.longPressDelayMs > 0L) }
        .mapTo(LinkedHashSet(), TouchButtonSpec::id)
    val pointerState = remember(layoutKey, actionOwnersByTarget, pointerActionTargets) {
        TouchButtonPointerState(actionOwnersByTarget, pointerActionTargets)
    }
    val routingKey = Triple(layoutKey, actionOwnersByTarget, pointerActionTargets)
    val pressActionsByOwner = specs.flatMap(TouchButtonSpec::pressActions)
        .associateBy(TouchButtonPressAction::ownerId)
    val currentPressActionsByOwner by rememberUpdatedState(pressActionsByOwner)
    val bounds = remember(layoutKey, density) {
        with(density) {
            val rects = specs.associate { spec ->
                spec.id to Rect(
                    left = spec.x.toPx(),
                    top = spec.y.toPx(),
                    right = spec.x.toPx() + spec.width.toPx(),
                    bottom = spec.y.toPx() + spec.height.toPx()
                )
            }
            Triple(rects, Unit, Unit)
        }
    }
    val rects = bounds.first
    val groupRect = remember(inputWidth, inputHeight, density) {
        with(density) {
            Rect(
                left = 0f,
                top = 0f,
                right = inputWidth.toPx(),
                bottom = inputHeight.toPx()
            )
        }
    }
    val groupWidth = inputWidth
    val groupHeight = inputHeight

    fun hitTargets(x: Float, y: Float): List<String> =
        selectTouchButtonHitTargets(
            specs.asSequence()
                .filter { spec -> rects.getValue(spec.id).contains(Offset(x, y)) }
                .map { spec ->
                    TouchButtonHitCandidate(
                        id = spec.id,
                        clickOnlyUtility = spec.onClick != null && spec.pressActions.isEmpty()
                    )
                }
                .toList()
        )

    fun TouchButtonSpec.hasLongPressAction(): Boolean {
        return onLongPressChange != null && longPressDelayMs > 0L
    }

    fun TouchButtonSpec.hasTapToHoldAction(): Boolean {
        return tapToHold && pressActions.isNotEmpty()
    }

    fun dispatchActionTransition(
        transition: TouchButtonPointerTransition,
        handlers: Map<String, TouchButtonPressAction> = currentPressActionsByOwner
    ) {
        transition.releasedActionOwners.forEach { ownerId -> handlers[ownerId]?.onPressChange?.invoke(false) }
        transition.pressedActionOwners.forEach { ownerId -> handlers[ownerId]?.onPressChange?.invoke(true) }
    }

    fun cancelLongPress(pointerId: Long, targetId: String) {
        longPressJobs.remove(pointerId to targetId)?.cancel()
        val activeForPointer = longPressActiveTargets[pointerId].orEmpty()
        if (targetId in activeForPointer) {
            val remaining = activeForPointer - targetId
            if (remaining.isEmpty()) longPressActiveTargets.remove(pointerId) else longPressActiveTargets[pointerId] = remaining
            if (longPressActiveTargets.values.none { targetId in it }) {
                currentSpecById[targetId]?.onLongPressChange?.invoke(false)
            }
        }
    }

    fun startLongPress(pointerId: Long, targetId: String) {
        val spec = currentSpecById[targetId] ?: return
        if (!spec.hasLongPressAction()) return
        val key = pointerId to targetId
        longPressJobs.remove(key)?.cancel()
        longPressJobs[key] = coroutineScope.launch {
            delay(spec.longPressDelayMs.milliseconds)
            if (targetId !in activeTargets[pointerId].orEmpty()) return@launch
            val wasAlreadyActive = longPressActiveTargets.values.any { targetId in it }
            longPressActiveTargets[pointerId] = longPressActiveTargets[pointerId].orEmpty() + targetId
            if (!wasAlreadyActive) {
                spec.onLongPressChange?.invoke(true)
            }
        }
    }

    fun scheduleShortTapRelease(sourceId: String) {
        shortTapJobs[sourceId] = coroutineScope.launch {
            delay(70.milliseconds)
            dispatchActionTransition(pointerState.setTransientActionOwners(sourceId, emptySet()))
            shortTapActionOwners.remove(sourceId)
            shortTapJobs.remove(sourceId)
        }
    }

    fun completePointerTargets(
        pointerId: Long,
        latchedUpdates: Map<String, Boolean> = emptyMap(),
        transientActionOwners: Map<String, Set<String>> = emptyMap()
    ) {
        val transition = pointerState.completePointer(pointerId, latchedUpdates, transientActionOwners)
        activeTargets.remove(pointerId)
        downTargets.remove(pointerId)
        transition.exitedTargets.forEach { targetId -> cancelLongPress(pointerId, targetId) }
        latchedUpdates.forEach { (targetId, latched) ->
            if (latched) latchedTargets[targetId] = true else latchedTargets.remove(targetId)
        }
        transientActionOwners.forEach { (sourceId, owners) ->
            shortTapActionOwners[sourceId] = owners
        }
        dispatchActionTransition(transition)
        if (transition.deactivatedTargets.any { currentSpecById[it]?.haptics != false }) {
            onTouchHaptic(ButtonPhase.RELEASE)
        }
        transientActionOwners.keys.forEach(::scheduleShortTapRelease)
    }

    fun updatePointerTargets(pointerId: Long, newTargets: Collection<String>, emitHaptics: Boolean = true) {
        val transition = pointerState.update(pointerId, newTargets)
        if (newTargets.isEmpty()) {
            activeTargets.remove(pointerId)
        } else {
            activeTargets[pointerId] = newTargets.toCollection(LinkedHashSet())
        }

        transition.exitedTargets.forEach { targetId -> cancelLongPress(pointerId, targetId) }
        transition.enteredTargets.forEach { targetId ->
            if (currentSpecById[targetId]?.hasLongPressAction() == true) startLongPress(pointerId, targetId)
        }
        dispatchActionTransition(transition)

        if (emitHaptics && transition.activatedTargets.any { currentSpecById[it]?.haptics != false }) {
            onTouchHaptic(ButtonPhase.PRESS)
        }
        if (emitHaptics && transition.deactivatedTargets.any { currentSpecById[it]?.haptics != false }) {
            onTouchHaptic(ButtonPhase.RELEASE)
        }
    }

    fun releaseTouchscreenStick(target: TouchscreenStickGestureTarget) {
        when (target) {
            TouchscreenStickGestureTarget.LEFT -> {
                updateLeftAnalogStick(
                    x = 0f,
                    y = 0f,
                    sensitivity = currentTouchscreenLeftStickSensitivity,
                    onPadInput = currentOnPadInput
                )
                leftGesturePointerId = null
            }
            TouchscreenStickGestureTarget.RIGHT -> {
                updateRightAnalogStick(
                    x = 0f,
                    y = 0f,
                    sensitivity = currentTouchscreenRightStickSensitivity,
                    onPadInput = currentOnPadInput
                )
                rightGesturePointerId = null
            }
            TouchscreenStickGestureTarget.NONE -> Unit
        }
    }

    fun releaseTouchscreenGestures() {
        releaseTouchscreenStick(TouchscreenStickGestureTarget.LEFT)
        releaseTouchscreenStick(TouchscreenStickGestureTarget.RIGHT)
    }

    /**
     * Converts a touch inside the emulator surface into emulated 3DS framebuffer
     * coordinates and forwards it to the core, which decides whether the point
     * lands on the emulated bottom screen.
     */
    fun forwardTouchscreenPointer(x: Float, y: Float, pressed: Boolean) {
        if (currentGuestTouchEnabled) currentOnGuestTouch(x, y, pressed)
    }

    fun releaseTouchscreenPointer() {
        if (touchPointerId == null) return
        currentOnGuestTouch(0f, 0f, false)
        touchPointerId = null
    }

    DisposableEffect(routingKey, touchscreenRightStick, touchscreenLeftStick, guestTouchEnabled) {
        val stateAtStart = pointerState
        val specsAtStart = specById
        val pressActionsAtStart = pressActionsByOwner
        onDispose {
            longPressJobs.values.forEach { it.cancel() }
            longPressActiveTargets.values.flatten().toSet().forEach { targetId ->
                specsAtStart[targetId]?.onLongPressChange?.invoke(false)
            }
            longPressActiveTargets.clear()
            shortTapJobs.values.forEach { it.cancel() }
            shortTapActionOwners.keys.toList().forEach { sourceId ->
                dispatchActionTransition(
                    stateAtStart.setTransientActionOwners(sourceId, emptySet()),
                    pressActionsAtStart
                )
            }
            shortTapActionOwners.clear()
            shortTapJobs.clear()
            latchedTargets.keys.toList().forEach { targetId ->
                dispatchActionTransition(stateAtStart.setLatched(targetId, latched = false), pressActionsAtStart)
            }
            latchedTargets.clear()
            (activeTargets.keys + downTargets.keys).toSet().forEach { pointerId ->
                dispatchActionTransition(stateAtStart.update(pointerId, emptyList()), pressActionsAtStart)
            }
            activeTargets.clear()
            downTargets.clear()
            latchedTargets.clear()
            longPressJobs.clear()
            longPressActiveTargets.clear()
            releaseTouchscreenGestures()
            releaseTouchscreenPointer()
        }
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(groupRect.left.roundToInt(), groupRect.top.roundToInt())
            }
            .size(groupWidth, groupHeight)
            // Use Compose pointerInput instead of pointerInteropFilter:
            // - pointerInput delivers proper per-pointer press/move/release events and cancellation
            // - pointerInteropFilter could miss ACTION_UP under rapid multitouch / Samsung OneUI
            //   which caused stuck keys and completely dead overlay on some devices (e.g., Samsung S26)
            .pointerInput(routingKey, touchscreenRightStick, touchscreenLeftStick, guestTouchEnabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isNativeCancel = event.motionEvent?.actionMasked == MotionEvent.ACTION_CANCEL
                        event.changes.forEach { change ->
                            val pointerId = change.id.value
                            val isDown = change.pressed && !change.previousPressed
                            val isUp = !change.pressed && change.previousPressed
                            if (isDown && !change.isConsumed) {
                                val targets = hitTargets(change.position.x, change.position.y)
                                if (targets.isNotEmpty()) {
                                    downTargets[pointerId] = targets.toCollection(LinkedHashSet())
                                    updatePointerTargets(pointerId, targets)
                                    change.consume()
                                } else {
                                    val target = resolveTouchscreenStickGestureTarget(
                                        leftEnabled = touchscreenLeftStick,
                                        rightEnabled = touchscreenRightStick,
                                        startX = change.position.x,
                                        widthPx = groupRect.width
                                    )
                                    val targetFree = when (target) {
                                        TouchscreenStickGestureTarget.LEFT -> leftGesturePointerId == null
                                        TouchscreenStickGestureTarget.RIGHT -> rightGesturePointerId == null
                                        TouchscreenStickGestureTarget.NONE -> false
                                    }
                                    if (targetFree) {
                                        when (target) {
                                            TouchscreenStickGestureTarget.LEFT -> {
                                                leftGesturePointerId = pointerId
                                                leftGestureOrigin = change.position
                                            }
                                            TouchscreenStickGestureTarget.RIGHT -> {
                                                rightGesturePointerId = pointerId
                                                rightGestureOrigin = change.position
                                            }
                                            TouchscreenStickGestureTarget.NONE -> Unit
                                        }
                                        change.consume()
                                    } else if (currentGuestTouchEnabled && touchPointerId == null) {
                                        touchPointerId = pointerId
                                        val now = change.uptimeMillis
                                        val distance = (change.position - lastTouchscreenDownPosition)
                                            .getDistance()
                                        if (now - lastTouchscreenDownTime in 1..350L && distance < 140f) {
                                            lastTouchscreenDownTime = 0L
                                            currentOnScreenDoubleTap()
                                        } else {
                                            lastTouchscreenDownTime = now
                                            lastTouchscreenDownPosition = change.position
                                        }
                                        forwardTouchscreenPointer(
                                            change.position.x,
                                            change.position.y,
                                            pressed = true
                                        )
                                        change.consume()
                                    }
                                }
                            } else if (isUp) {
                                val downTargetSet = downTargets.remove(pointerId)
                                if (downTargetSet != null) {
                                    // Compose synthesizes cancellation changes with isConsumed=true and
                                    // no native MotionEvent; use both cancellation signals before consuming this up.
                                    if (isNativeCancel || change.isConsumed) {
                                        completePointerTargets(pointerId)
                                    } else {
                                        val upTargets = hitTargets(change.position.x, change.position.y).toSet()
                                        val matchingTargets = matchingTouchButtonReleaseTargets(downTargetSet, upTargets)
                                        val consumedByLongPress = longPressActiveTargets[pointerId].orEmpty()
                                        val latchedUpdates = mutableMapOf<String, Boolean>()
                                        val transientOwners = mutableMapOf<String, Set<String>>()
                                        val clickTargets = mutableListOf<TouchButtonSpec>()
                                        matchingTargets.forEach { targetId ->
                                            val downSpec = currentSpecById[targetId] ?: return@forEach
                                            if (downSpec.hasLongPressAction()) {
                                                if (targetId !in consumedByLongPress && downSpec.pressActions.isNotEmpty()) {
                                                    val sourceId = "tap:$pointerId:${downSpec.id}:${shortTapSequence.incrementAndGet()}"
                                                    transientOwners[sourceId] = downSpec.pressActions
                                                        .mapTo(LinkedHashSet(), TouchButtonPressAction::ownerId)
                                                }
                                            } else if (downSpec.hasTapToHoldAction()) {
                                                latchedUpdates[targetId] = latchedTargets[targetId] != true
                                            } else if (downSpec.onClick != null) {
                                                clickTargets += downSpec
                                            }
                                        }
                                        completePointerTargets(pointerId, latchedUpdates, transientOwners)
                                        clickTargets.forEach { it.onClick?.invoke() }
                                    }
                                    change.consume()
                                } else if (pointerId == leftGesturePointerId) {
                                    releaseTouchscreenStick(TouchscreenStickGestureTarget.LEFT)
                                    change.consume()
                                } else if (pointerId == rightGesturePointerId) {
                                    releaseTouchscreenStick(TouchscreenStickGestureTarget.RIGHT)
                                    change.consume()
                                } else if (touchPointerId == pointerId) {
                                    releaseTouchscreenPointer()
                                    change.consume()
                                }
                            } else if (change.pressed) {
                                if (downTargets.containsKey(pointerId)) {
                                    val newTargets = hitTargets(change.position.x, change.position.y)
                                    updatePointerTargets(pointerId, newTargets)
                                    change.consume()
                                } else if (pointerId == leftGesturePointerId) {
                                    val value = calculateRightStickGestureValue(
                                        deltaX = change.position.x - leftGestureOrigin.x,
                                        deltaY = change.position.y - leftGestureOrigin.y,
                                        radiusPx = gestureRadiusPx
                                    )
                                    updateLeftAnalogStick(
                                        x = if (currentInvertLeftStickHorizontal) -value.x else value.x,
                                        y = if (currentInvertLeftStick) -value.y else value.y,
                                        sensitivity = currentTouchscreenLeftStickSensitivity,
                                        onPadInput = currentOnPadInput
                                    )
                                    change.consume()
                                } else if (pointerId == rightGesturePointerId) {
                                    val value = calculateRightStickGestureValue(
                                        deltaX = change.position.x - rightGestureOrigin.x,
                                        deltaY = change.position.y - rightGestureOrigin.y,
                                        radiusPx = gestureRadiusPx
                                    )
                                    updateRightAnalogStick(
                                        x = if (currentInvertRightStickHorizontal) -value.x else value.x,
                                        y = if (currentInvertRightStick) -value.y else value.y,
                                        sensitivity = currentTouchscreenRightStickSensitivity,
                                        onPadInput = currentOnPadInput
                                    )
                                    change.consume()
                                } else if (touchPointerId == pointerId) {
                                    forwardTouchscreenPointer(
                                        change.position.x,
                                        change.position.y,
                                        pressed = true
                                    )
                                    change.consume()
                                }
                            }
                        }
                        // Stuck-key guard: clean up pointers that disappeared without explicit up (missed cancel)
                        val presentIds = event.changes.map { it.id.value }.toSet()
                        val staleActive = (activeTargets.keys + downTargets.keys).toSet().filter { it !in presentIds }
                        if (staleActive.isNotEmpty()) {
                            staleActive.forEach { pid ->
                                updatePointerTargets(pid, emptyList(), emitHaptics = false)
                                downTargets.remove(pid)
                            }
                        }
                        if (leftGesturePointerId != null && leftGesturePointerId !in presentIds) {
                            releaseTouchscreenStick(TouchscreenStickGestureTarget.LEFT)
                        }
                        if (rightGesturePointerId != null && rightGesturePointerId !in presentIds) {
                            releaseTouchscreenStick(TouchscreenStickGestureTarget.RIGHT)
                        }
                        if (touchPointerId != null && touchPointerId !in presentIds) {
                            releaseTouchscreenPointer()
                        }
                    }
                }
            }
    ) {
        specs.forEach { spec ->
            val controlModifier = Modifier.offset {
                    IntOffset(
                        (spec.x.roundToPx() - groupRect.left.roundToInt()),
                        (spec.y.roundToPx() - groupRect.top.roundToInt())
                    )
                }
            val pressed = activeTargets.values.any { spec.id in it } || latchedTargets[spec.id] == true
            val vectorDrawable = spec.customControl
                ?.takeIf { it.usesVectorStyle }
                ?.let { actionDrawableRes(it.actionId) }
            if (spec.customControl != null && vectorDrawable == null) {
                CustomControlVisual(
                    control = spec.customControl,
                    pressed = pressed,
                    modifier = controlModifier.size(spec.width, spec.height)
                )
            } else {
                VectorOverlayButton(
                    drawableRes = vectorDrawable ?: requireNotNull(spec.drawableRes),
                    labelOverride = spec.labelOverride,
                    width = spec.width,
                    height = spec.height,
                    shape = spec.shape,
                    alpha = spec.opacity,
                    interactive = false,
                    pressed = pressed,
                    visualStyle = visualStyle,
                    pressEffect = pressEffect,
                    modifier = controlModifier
                )
            }
        }
    }
}

private fun updateAnalogStick(
    x: Float, y: Float,
    sensitivity: Float = 1f,
    upKey: Int, rightKey: Int, downKey: Int, leftKey: Int,
    onPadInput: (Int, Int, Boolean) -> Unit
) {
    val scaledX = (x * sensitivity.coerceIn(0.5f, 2f)).coerceIn(-1f, 1f)
    val scaledY = (y * sensitivity.coerceIn(0.5f, 2f)).coerceIn(-1f, 1f)
    val right = (scaledX.coerceAtLeast(0f) * 255f).roundToInt()
    val left = ((-scaledX).coerceAtLeast(0f) * 255f).roundToInt()
    val down = (scaledY.coerceAtLeast(0f) * 255f).roundToInt()
    val up = ((-scaledY).coerceAtLeast(0f) * 255f).roundToInt()
    onPadInput(upKey, up, up > 0)
    onPadInput(rightKey, right, right > 0)
    onPadInput(downKey, down, down > 0)
    onPadInput(leftKey, left, left > 0)
}

private fun updateRightAnalogStick(
    x: Float,
    y: Float,
    sensitivity: Float = 1f,
    onPadInput: (Int, Int, Boolean) -> Unit
) {
    updateAnalogStick(
        x = x,
        y = y,
        sensitivity = sensitivity,
        upKey = PadKey.RIGHT_STICK_UP,
        rightKey = PadKey.RIGHT_STICK_RIGHT,
        downKey = PadKey.RIGHT_STICK_DOWN,
        leftKey = PadKey.RIGHT_STICK_LEFT,
        onPadInput = onPadInput
    )
}

private fun updateLeftAnalogStick(
    x: Float,
    y: Float,
    sensitivity: Float = 1f,
    onPadInput: (Int, Int, Boolean) -> Unit
) {
    updateAnalogStick(
        x = x,
        y = y,
        sensitivity = sensitivity,
        upKey = PadKey.LEFT_STICK_UP,
        rightKey = PadKey.LEFT_STICK_RIGHT,
        downKey = PadKey.LEFT_STICK_DOWN,
        leftKey = PadKey.LEFT_STICK_LEFT,
        onPadInput = onPadInput
    )
}

private fun formatSaveTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}

internal suspend fun captureSaveStatePreview(
    surfaceView: SurfaceView,
    savePath: String,
    dualScreenPortrait: Boolean = false
): Boolean {
    val width = surfaceView.width
    val height = surfaceView.height
    if (width <= 0 || height <= 0 || !surfaceView.holder.surface.isValid) return false
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val result = suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (continuation.isActive) continuation.resume(copyResult)
                },
                Handler(Looper.getMainLooper())
            )
        } catch (_: Throwable) {
            if (continuation.isActive) continuation.resume(PixelCopy.ERROR_UNKNOWN)
        }
    }
    if (result != PixelCopy.SUCCESS) {
        bitmap.recycle()
        return false
    }
    val previewBitmap = if (dualScreenPortrait) {
        cropNdsTopScreen(bitmap)
    } else {
        scaleSavePreview(bitmap)
    }
    return withContext(Dispatchers.IO) {
        val target = File("$savePath.png")
        target.parentFile?.mkdirs()
        val temporary = runCatching {
            File.createTempFile(".preview-", ".tmp", target.parentFile)
        }.getOrNull()
        if (temporary == null) {
            if (previewBitmap !== bitmap) previewBitmap.recycle()
            bitmap.recycle()
            return@withContext false
        }
        try {
            FileOutputStream(temporary).use { output ->
                previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            temporary.delete()
            if (previewBitmap !== bitmap) previewBitmap.recycle()
            bitmap.recycle()
        }
    }
}

private fun scaleSavePreview(source: Bitmap, targetWidth: Int = 512): Bitmap {
    if (source.width <= targetWidth) return source
    val targetHeight = (source.height * targetWidth / source.width.toFloat()).toInt()
        .coerceAtLeast(1)
    return runCatching {
        Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }.getOrElse { source }
}

private fun cropNdsTopScreen(source: Bitmap): Bitmap {
    val sourceWidth = source.width
    val sourceHeight = source.height
    val contentAspect = 256f / 386f
    var contentWidth = sourceWidth
    var contentHeight = (sourceWidth / contentAspect).toInt()
    if (contentHeight > sourceHeight) {
        contentHeight = sourceHeight
        contentWidth = (sourceHeight * contentAspect).toInt()
    }
    if (contentWidth <= 0 || contentHeight <= 0) return source
    val left = (sourceWidth - contentWidth) / 2
    val top = (sourceHeight - contentHeight) / 2
    val screenHeight = (contentHeight * 192f / 386f).toInt().coerceAtLeast(1)
    val cropped = runCatching {
        Bitmap.createBitmap(source, left, top, contentWidth, screenHeight)
    }.getOrElse { return source }
    val targetWidth = 512
    val targetHeight = (cropped.height * targetWidth / cropped.width.toFloat()).toInt()
        .coerceAtLeast(1)
    val scaled = runCatching {
        Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }.getOrElse { cropped }
    if (scaled !== cropped) cropped.recycle()
    return scaled
}

private fun formatPlayTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun SaveSlotPreview(
    slot: Int,
    previewPath: String?,
    lastModified: Long,
    isSaving: Boolean,
    gameTitle: String,
    modifier: Modifier = Modifier
) {
    val shape = neonShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    )
                )
            )
    ) {
        if (previewPath != null) {
            GameCoverArt(
                coverPath = previewPath,
                fallbackTitle = gameTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f),
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.58f)
        ) {
            Text(
                text = stringResource(R.string.detail_slot, slot),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (lastModified > 0L) {
                    stringResource(
                        R.string.emulation_save_slot_saved_at,
                        formatSaveTimestamp(lastModified)
                    )
                } else {
                    stringResource(R.string.emulation_save_slot_empty)
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SaveSlotThumbnail(
    previewPath: String?,
    lastModified: Long,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(neonShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        if (previewPath != null) {
            GameCoverArt(
                coverPath = previewPath,
                fallbackTitle = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Save,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (lastModified > 0L) 0.8f else 0.35f
                ),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EmulationSidebarMenu(
    uiState: EmulationUiState,
    isNds: Boolean,
    gamepadConnected: Boolean,
    currentGamePath: String?,
    globalDefaults: SettingsSnapshot,
    overlayDefaults: OverlayLayoutSnapshot,
    onClose: () -> Unit,
    onSwapScreens: () -> Unit,
    onPauseToggle: () -> Unit,
    onQuickSave: () -> Unit,
    onQuickLoad: () -> Unit,
    onLoadAutoSave: () -> Unit,
    onSetAutoSaveEnabled: (Boolean) -> Unit,
    onSetAutoSaveIntervalMinutes: (Int) -> Unit,
    onSetAutoSaveOnExit: (Boolean) -> Unit,
    onSetAutoLoadOnStart: (Boolean) -> Unit,
    onSaveGameSettingsProfile: () -> Unit,
    onResetGameSettingsProfile: () -> Unit,
    onSetCoreOption: (String, String) -> Unit,
    onNextSlot: () -> Unit,
    onPrevSlot: () -> Unit,
    onToggleFps: () -> Unit,
    onSetFrameLimit: () -> Unit,
    onSetFrameSkip: (Int) -> Unit,
    onSetFpsOverlayMode: (Int) -> Unit,
    onSetFpsOverlayCorner: (Int) -> Unit,
    onSetFpsOverlayScale: (Int) -> Unit,
    onSetFpsOverlayMetrics: (Int) -> Unit,
    onSetOverlayScale: (Int) -> Unit,
    onSetOverlayOpacity: (Int) -> Unit,
    onSetHideOverlayOnGamepad: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetStickScale: (Int) -> Unit,
    onSetLeftStickSensitivity: (Int) -> Unit,
    onSetRightStickSensitivity: (Int) -> Unit,
    onSetInvertLeftStick: (Boolean) -> Unit,
    onSetInvertRightStick: (Boolean) -> Unit,
    onSetInvertLeftStickHorizontal: (Boolean) -> Unit,
    onSetInvertRightStickHorizontal: (Boolean) -> Unit,
    onSetGamepadStickDeadzone: (Int) -> Unit,
    onSetGamepadLeftStickSensitivity: (Int) -> Unit,
    onSetGamepadRightStickSensitivity: (Int) -> Unit,
    onSetGamepadRightStickUpToR2: (Boolean) -> Unit,
    onSetGamepadRightStickDownToL2: (Boolean) -> Unit,
    onSetTouchscreenRightStick: (Boolean) -> Unit,
    onSetTouchscreenRightStickSensitivity: (Int) -> Unit,
    onSetTouchscreenLeftStick: (Boolean) -> Unit,
    onSetTouchscreenLeftStickSensitivity: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onOpenControlsEditor: () -> Unit,
    onOpenGamepadMapping: () -> Unit,
    onOpenKeyboardMouseSettings: () -> Unit,
    onSetRenderer: (Int) -> Unit,
    onSetUpscale: (Float) -> Unit,
    onSetAspectRatio: (Int) -> Unit,
    onSetDisplayCrop: (DisplayCrop) -> Unit,
    onSetEnableCheats: (Boolean) -> Unit,
    onToggleCheat: (String, Boolean) -> Unit,
    onSetCheatGroupEnabled: (List<String>, Boolean) -> Unit,
    onRefreshCheats: () -> Unit,
    onSetAudioVolume: (Int) -> Unit,
    onSetAudioMuted: (Boolean) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sectionTitleColor = MaterialTheme.colorScheme.primary
    16.dp
    val sectionSpacing = 16.dp
    val sectionLabelInset = 4.dp
    val sectionLabelTopPadding = 8.dp
    val navPadding = WindowInsets.navigationBars.asPaddingValues()
    val animatedRightInset by animateDpAsState(
        targetValue = navPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        animationSpec = tween(durationMillis = 220),
        label = "emulation_menu_right_inset"
    )
    val animatedBottomInset by animateDpAsState(
        targetValue = navPadding.calculateBottomPadding(),
        animationSpec = tween(durationMillis = 220),
        label = "emulation_menu_bottom_inset"
    )
    val drawerBottomPadding = when {
        !uiState.controlsVisible -> 0.dp
        uiState.compactControls -> 28.dp
        else -> 36.dp
    }
    val capabilities = PlatformUiCapabilities.forGame(currentGamePath, uiState.gamePlatform)
    val usesAdditionalCore = uiState.gamePlatform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)
    var selectedMenuTabName by rememberSaveable { mutableStateOf(EmulationMenuTab.Session.name) }
    val selectedMenuTab = remember(selectedMenuTabName) { EmulationMenuTab.valueOf(selectedMenuTabName) }
    val firstMenuFocusRequester = remember { FocusRequester() }
    val railFocusRequesters = remember {
        EmulationMenuTab.entries.associateWith { FocusRequester() }
    }
    var autoSaveIntervalText by remember(uiState.autoSaveIntervalMinutes) {
        mutableStateOf(uiState.autoSaveIntervalMinutes.toString())
    }
    val sessionScrollState = rememberScrollState()
    val controlsScrollState = rememberScrollState()
    val emulationScrollState = rememberScrollState()
    val cheatsScrollState = rememberScrollState()
    val graphicsScrollState = rememberScrollState()
    val achievementsScrollState = rememberScrollState()
    val selectedTabScrollState = when (selectedMenuTab) {
        EmulationMenuTab.Session -> sessionScrollState
        EmulationMenuTab.Controls -> controlsScrollState
        EmulationMenuTab.Emulation -> emulationScrollState
        EmulationMenuTab.Cheats -> cheatsScrollState
        EmulationMenuTab.Graphics -> graphicsScrollState
        EmulationMenuTab.Achievements -> achievementsScrollState
    }
    LaunchedEffect(selectedMenuTab) {
        railFocusRequesters[selectedMenuTab]?.requestFocus()
    }
    // Cheats can be toggled or installed from the cheat manager; re-read the
    // installed list every time the tab is opened so both stay in sync.
    LaunchedEffect(selectedMenuTab) {
        if (selectedMenuTab == EmulationMenuTab.Cheats) onRefreshCheats()
    }
    val menuTabs = remember(uiState.gameMenuTabOrder, uiState.hiddenGameMenuTabs, capabilities.platform) {
        uiState.gameMenuTabOrder
            .filter(capabilities::supportsGameMenuTab)
            .filterNot(uiState.hiddenGameMenuTabs::contains)
            .map(GameMenuTabId::toEmulationMenuTab)
            .ifEmpty { listOf(EmulationMenuTab.Session) }
    }
    LaunchedEffect(menuTabs, selectedMenuTab) {
        if (selectedMenuTab !in menuTabs) {
            selectedMenuTabName = menuTabs.first().name
        }
    }
    fun selectRelativeMenuTab(offset: Int) {
        val currentIndex = menuTabs.indexOf(selectedMenuTab).coerceAtLeast(0)
        selectedMenuTabName = menuTabs[(currentIndex + offset + menuTabs.size) % menuTabs.size].name
    }
    ProvideGamepadShoulderActions(
        onPrevious = { selectRelativeMenuTab(-1) },
        onNext = { selectRelativeMenuTab(1) }
    )

    val menuContent: @Composable ColumnScope.() -> Unit = {
                val menuTitle = uiState.currentGameTitle.ifBlank { stringResource(R.string.emulation_sidebar_title) }
                Surface(
                    shape = neonShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        GradientStart.copy(alpha = 0.14f),
                                        GradientEnd.copy(alpha = 0.08f)
                                    )
                                )
                            )
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GameCoverArt(
                            coverPath = uiState.currentGameCoverPath,
                            fallbackTitle = menuTitle,
                            contentScale = ContentScale.Fit,
                            preserveContentScale = true,
                            showPlaceholder = false,
                            shape = RectangleShape,
                            modifier = Modifier
                                .width(64.dp)
                                .aspectRatio(
                                    gameCoverAspectRatio(
                                        uiState.currentGameCoverPath,
                                        com.sbro.emucored.ui.common.GameCoverAspectRatio
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = menuTitle,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = uiState.currentGameSubtitle.ifBlank { stringResource(R.string.emulation_menu_subtitle) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.emulation_play_time_badge,
                                            formatPlayTime(uiState.activePlayTimeMs)
                                        ),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                var coreOptionsVersion by remember { mutableIntStateOf(0) }
                val onCoreOptionChange: (String, String) -> Unit = { key, value ->
                    onSetCoreOption(key, value)
                    coreOptionsVersion++
                }

                when (selectedMenuTab) {
                    EmulationMenuTab.Session -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                MenuButton(
                                    modifier = Modifier.focusRequester(firstMenuFocusRequester),
                                    icon = if (uiState.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    text = stringResource(if (uiState.isPaused) R.string.emulation_resume else R.string.emulation_pause),
                                    onClick = onPauseToggle,
                                    enabled = !uiState.isActionInProgress,
                                    containerColor = if (!uiState.isPaused) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                MenuButton(
                                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                                    text = stringResource(R.string.emulation_exit),
                                    onClick = onExit,
                                    enabled = !uiState.isActionInProgress,
                                    isDestructive = true,
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                                )
                            }
                        }

                        if (capabilities.supportsScreenSwap) MenuButton(
                            icon = Icons.Rounded.SwapVert,
                            text = stringResource(R.string.settings_gamepad_action_swap_screen),
                            onClick = onSwapScreens,
                            enabled = !uiState.isActionInProgress
                        )

                        // Nintendo 3DS titles are single-card; there is no
                        // disc-swap action to offer while a game is running.

                        val visibleSessionSections = gameMenuSectionsForTab(
                            GameMenuTabId.SESSION,
                            uiState.gameMenuSectionOrder
                        )
                            .filterNot(uiState.hiddenGameMenuSections::contains)
                        visibleSessionSections.forEachIndexed { index, section ->
                        val includeFollowingAutoSave = section == GameMenuSectionId.SAVE_STATES &&
                            visibleSessionSections.getOrNull(index + 1) == GameMenuSectionId.AUTO_SAVE
                        val mergedIntoPreviousSaveStates = section == GameMenuSectionId.AUTO_SAVE &&
                            visibleSessionSections.getOrNull(index - 1) == GameMenuSectionId.SAVE_STATES
                        if (!mergedIntoPreviousSaveStates &&
                            (section == GameMenuSectionId.SAVE_STATES || section == GameMenuSectionId.AUTO_SAVE)
                        ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = neonShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (section == GameMenuSectionId.SAVE_STATES) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.detail_save_states),
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (uiState.currentSlotLastModified > 0L) {
                                                stringResource(
                                                    R.string.emulation_save_slot_saved_at,
                                                    formatSaveTimestamp(uiState.currentSlotLastModified)
                                                )
                                            } else {
                                                stringResource(R.string.emulation_save_slot_empty)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    androidx.compose.material3.IconButton(
                                        onClick = onPrevSlot,
                                        enabled = uiState.currentSlot > 1,
                                        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null)
                                    }
                                    Text(
                                        text = "${uiState.currentSlot}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    androidx.compose.material3.IconButton(
                                        onClick = onNextSlot,
                                        enabled = uiState.currentSlot < 10,
                                        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                                    }
                                }

                                SaveSlotPreview(
                                    slot = uiState.currentSlot,
                                    previewPath = uiState.currentSlotPreviewPath,
                                    lastModified = uiState.currentSlotLastModified,
                                    isSaving = uiState.actionLabel == "saving",
                                    gameTitle = uiState.currentGameTitle
                                )

                                }

                                if (includeFollowingAutoSave) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                }

                                if (section == GameMenuSectionId.AUTO_SAVE || includeFollowingAutoSave) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    SaveSlotThumbnail(
                                        previewPath = uiState.autoSavePreviewPath,
                                        lastModified = uiState.autoSaveLastModified,
                                        contentDescription = stringResource(R.string.emulation_auto_save_title),
                                        modifier = Modifier
                                            .width(74.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.emulation_auto_save_title),
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (uiState.autoSaveLastModified > 0L) {
                                                formatSaveTimestamp(uiState.autoSaveLastModified)
                                            } else {
                                                stringResource(R.string.emulation_auto_save_empty)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    SettingHelpButton(
                                        title = stringResource(R.string.emulation_auto_save_title),
                                        description = stringResource(R.string.emulation_help_auto_save)
                                    )
                                    Switch(
                                        checked = uiState.autoSaveEnabled,
                                        onCheckedChange = onSetAutoSaveEnabled,
                                        enabled = !uiState.isActionInProgress
                                    )
                                }

                                AnimatedVisibility(visible = uiState.autoSaveEnabled) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.emulation_auto_save_interval_label),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        SettingHelpButton(
                                            title = stringResource(R.string.emulation_auto_save_interval_label),
                                            description = stringResource(R.string.emulation_help_auto_save_interval)
                                        )
                                        OutlinedTextField(
                                            value = autoSaveIntervalText,
                                            onValueChange = { raw ->
                                                val filtered = raw.filter(Char::isDigit).take(3)
                                                autoSaveIntervalText = filtered
                                                filtered.toIntOrNull()?.takeIf { it >= 1 }?.let { minutes ->
                                                    onSetAutoSaveIntervalMinutes(minutes)
                                                }
                                            },
                                            enabled = !uiState.isActionInProgress,
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = neonShape(16.dp),
                                            textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            modifier = Modifier
                                                .width(108.dp)
                                                .height(48.dp),
                                            suffix = {
                                                Text(stringResource(R.string.emulation_auto_save_interval_suffix))
                                            }
                                        )
                                    }
                                }
                                }
                            }
                        }

                        }

                        if ((section == GameMenuSectionId.AUTO_SAVE && !mergedIntoPreviousSaveStates) ||
                            includeFollowingAutoSave
                        ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = neonShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SaveSlotThumbnail(
                                    previewPath = uiState.autoSavePreviewPath,
                                    lastModified = uiState.autoSaveLastModified,
                                    contentDescription = stringResource(R.string.emulation_auto_save_restore_title),
                                    modifier = Modifier
                                            .width(64.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.emulation_auto_save_restore_title),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (uiState.autoSaveLastModified > 0L) {
                                            formatSaveTimestamp(uiState.autoSaveLastModified)
                                        } else {
                                            stringResource(R.string.emulation_auto_save_empty)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                CompactIconActionButton(
                                    icon = Icons.Rounded.Restore,
                                    contentDescription = stringResource(R.string.emulation_quick_load_desc),
                                    onClick = onLoadAutoSave,
                                    enabled = !uiState.isActionInProgress && uiState.autoSaveLastModified > 0L,
                                    showProgress = uiState.actionLabel == "loading"
                                )
                            }
                        }

                        }

                        if (section == GameMenuSectionId.QUICK_ACTIONS) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                QuickIconActionButton(
                                    icon = Icons.Rounded.Save,
                                    contentDescription = stringResource(R.string.emulation_quick_save_desc),
                                    onClick = onQuickSave,
                                    enabled = !uiState.isActionInProgress,
                                    showProgress = uiState.actionLabel == "saving",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                QuickIconActionButton(
                                    icon = Icons.Rounded.Restore,
                                    contentDescription = stringResource(R.string.emulation_quick_load_desc),
                                    onClick = onQuickLoad,
                                    enabled = !uiState.isActionInProgress,
                                    showProgress = uiState.actionLabel == "loading",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                )
                            }
                        }

                        }

                        if (section == GameMenuSectionId.AUTOMATION) {
                        SettingsToggle(
                            title = stringResource(R.string.emulation_auto_save_on_exit),
                            checked = uiState.autoSaveOnExit,
                            enabled = !uiState.isActionInProgress,
                            onCheckedChange = onSetAutoSaveOnExit,
                            helpText = stringResource(R.string.emulation_auto_save_on_exit_desc)
                        )

                        SettingsToggle(
                            title = stringResource(R.string.emulation_auto_load_on_start),
                            checked = uiState.autoLoadOnStart,
                            enabled = !uiState.isActionInProgress,
                            onCheckedChange = onSetAutoLoadOnStart,
                            helpText = stringResource(R.string.emulation_auto_load_on_start_desc)
                        )

                        }

                        if (section == GameMenuSectionId.GAME_PROFILE) {
                        SidebarSectionTitle(
                            text = stringResource(R.string.game_settings_overlay_section).uppercase(),
                            color = sectionTitleColor,
                            topPadding = sectionLabelTopPadding,
                            horizontalInset = sectionLabelInset
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = neonShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        if (uiState.gameSettingsProfileActive) {
                                            R.string.game_settings_overlay_profile_active
                                        } else {
                                            R.string.game_settings_overlay_profile_inactive
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.game_settings_overlay_profile_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                MenuButton(
                                    icon = Icons.Rounded.Save,
                                    text = stringResource(R.string.game_settings_overlay_save),
                                    onClick = onSaveGameSettingsProfile,
                                    enabled = !uiState.isActionInProgress,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                MenuButton(
                                    icon = Icons.Rounded.Restore,
                                    text = stringResource(R.string.game_settings_overlay_reset),
                                    onClick = onResetGameSettingsProfile,
                                    enabled = !uiState.isActionInProgress && uiState.gameSettingsProfileActive,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                                )
                            }
                        }

                        }
                            }

                    }

                    EmulationMenuTab.Controls -> {
                        if (usesAdditionalCore) ConsoleCoreOptionsPanel(uiState.gamePlatform.console,
                            SettingsCategory.Controls, uiState.perGameCoreOptions, onSetCoreOption,
                            card = false)
                        gameMenuSectionsForTab(GameMenuTabId.CONTROLS, uiState.gameMenuSectionOrder)
                            .filterNot(uiState.hiddenGameMenuSections::contains)
                            .forEach { section ->
                                when (section) {
                                    GameMenuSectionId.CONTROLS_GENERAL -> {
                        SidebarSectionTitle(
                            text = stringResource(R.string.settings_controls_tab).uppercase(),
                            color = sectionTitleColor,
                            topPadding = sectionLabelTopPadding,
                            horizontalInset = sectionLabelInset
                        )

                        MenuButton(
                            icon = Icons.Rounded.TouchApp,
                            text = stringResource(R.string.settings_edit_controls),
                            onClick = onOpenControlsEditor,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                        )

                        MenuButton(
                            icon = if (uiState.controlsVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            text = stringResource(if (uiState.controlsVisible) R.string.emulation_hide_controls else R.string.emulation_show_controls),
                            onClick = onToggleControls,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                        )

                        SettingsToggle(
                            title = stringResource(R.string.settings_gamepad_hide_overlay),
                            checked = uiState.hideOverlayOnGamepad,
                            onCheckedChange = onSetHideOverlayOnGamepad,
                            helpText = stringResource(R.string.settings_help_hide_overlay_on_gamepad),
                            onResetToDefault = { onSetHideOverlayOnGamepad(overlayDefaults.hideOverlayOnGamepad) }
                        )

                        SettingsToggle(
                            title = stringResource(R.string.settings_keep_screen_on),
                            checked = uiState.keepScreenOn,
                            onCheckedChange = onSetKeepScreenOn,
                            helpText = stringResource(R.string.settings_help_keep_screen_on),
                            onResetToDefault = { onSetKeepScreenOn(globalDefaults.keepScreenOn) }
                        )

                        if (capabilities.supportsAzaharOptions) {
                            CoreOptionRows(
                                options = AzaharCoreOptions.gameMenuControlsOptions(),
                                version = coreOptionsVersion,
                                perGameOverrides = uiState.perGameCoreOptions,
                                onValueChange = onCoreOptionChange
                            )
                        }

                                    }

                                    GameMenuSectionId.CONTROLS_TOUCH -> {

                        OverlaySubsectionLabel(text = stringResource(R.string.settings_touch_controls_section))

                        LiveSliderRow(
                            title = stringResource(R.string.settings_overlay_scale),
                            valueLabelForValue = { "$it%" },
                            value = uiState.overlayScale.toFloat(),
                            range = 50f..150f,
                            steps = 99,
                            onValueChange = { onSetOverlayScale(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_overlay_scale),
                            onResetToDefault = { onSetOverlayScale(overlayDefaults.overlayScale) }
                        )

                        LiveSliderRow(
                            title = stringResource(R.string.settings_overlay_opacity),
                            valueLabelForValue = { "$it%" },
                            value = uiState.overlayOpacity.toFloat(),
                            range = AppPreferences.OVERLAY_OPACITY_MIN.toFloat()..
                                AppPreferences.OVERLAY_OPACITY_MAX.toFloat(),
                            steps = AppPreferences.OVERLAY_OPACITY_MAX -
                                AppPreferences.OVERLAY_OPACITY_MIN - 1,
                            onValueChange = { onSetOverlayOpacity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_overlay_opacity),
                            onResetToDefault = { onSetOverlayOpacity(overlayDefaults.overlayOpacity) }
                        )

                        if (capabilities.supportsAzaharOptions) {
                            LiveSliderRow(
                                title = stringResource(R.string.emulation_stick_scale),
                                valueLabelForValue = { "$it%" },
                                value = uiState.stickScale.toFloat(),
                                range = AppPreferences.OVERLAY_CONTROL_SCALE_MIN.toFloat()..
                                    AppPreferences.OVERLAY_CONTROL_SCALE_MAX.toFloat(),
                                steps = AppPreferences.OVERLAY_CONTROL_SCALE_MAX -
                                    AppPreferences.OVERLAY_CONTROL_SCALE_MIN - 1,
                                onValueChange = { onSetStickScale(it.toInt()) },
                                helpText = stringResource(R.string.settings_help_stick_scale),
                                onResetToDefault = { onSetStickScale(overlayDefaults.stickScale) }
                            )

                            LiveSliderRow(
                                title = stringResource(R.string.settings_left_stick_sensitivity),
                                valueLabelForValue = { "$it%" },
                                value = uiState.leftStickSensitivity.toFloat(),
                                range = 50f..200f,
                                steps = 149,
                                onValueChange = { onSetLeftStickSensitivity(it.toInt()) },
                                helpText = stringResource(R.string.settings_help_left_stick_sensitivity),
                                onResetToDefault = { onSetLeftStickSensitivity(AppPreferences.DEFAULT_STICK_SENSITIVITY) }
                            )

                            LiveSliderRow(
                                title = stringResource(R.string.settings_right_stick_sensitivity),
                                valueLabelForValue = { "$it%" },
                                value = uiState.rightStickSensitivity.toFloat(),
                                range = 50f..200f,
                                steps = 149,
                                onValueChange = { onSetRightStickSensitivity(it.toInt()) },
                                helpText = stringResource(R.string.settings_help_right_stick_sensitivity),
                                onResetToDefault = { onSetRightStickSensitivity(AppPreferences.DEFAULT_STICK_SENSITIVITY) }
                            )

                            SettingsToggle(
                                title = stringResource(R.string.settings_invert_left_stick),
                                checked = uiState.invertLeftStick,
                                onCheckedChange = onSetInvertLeftStick,
                                helpText = stringResource(R.string.settings_help_invert_left_stick),
                                onResetToDefault = { onSetInvertLeftStick(false) }
                            )

                            SettingsToggle(
                                title = stringResource(R.string.settings_invert_left_stick_horizontal),
                                checked = uiState.invertLeftStickHorizontal,
                                onCheckedChange = onSetInvertLeftStickHorizontal,
                                helpText = stringResource(R.string.settings_help_invert_left_stick_horizontal),
                                onResetToDefault = { onSetInvertLeftStickHorizontal(false) }
                            )

                            SettingsToggle(
                                title = stringResource(R.string.settings_invert_right_stick),
                                checked = uiState.invertRightStick,
                                onCheckedChange = onSetInvertRightStick,
                                helpText = stringResource(R.string.settings_help_invert_right_stick),
                                onResetToDefault = { onSetInvertRightStick(false) }
                            )

                            SettingsToggle(
                                title = stringResource(R.string.settings_invert_right_stick_horizontal),
                                checked = uiState.invertRightStickHorizontal,
                                onCheckedChange = onSetInvertRightStickHorizontal,
                                helpText = stringResource(R.string.settings_help_invert_right_stick_horizontal),
                                onResetToDefault = { onSetInvertRightStickHorizontal(false) }
                            )
                        }

                                    }

                                    GameMenuSectionId.CONTROLS_GAMEPAD -> {

                        OverlaySubsectionLabel(text = stringResource(R.string.settings_gamepad_controls_section))

                        if (!gamepadConnected) {
                            Text(
                                text = stringResource(R.string.settings_gamepad_controls_disconnected_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (capabilities.supportsAnalogControls) LiveSliderRow(
                            title = stringResource(R.string.settings_gamepad_stick_deadzone),
                            valueLabelForValue = { "$it%" },
                            value = uiState.gamepadStickDeadzone.toFloat(),
                            range = 0f..35f,
                            steps = 34,
                            enabled = gamepadConnected,
                            onValueChange = { onSetGamepadStickDeadzone(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_stick_deadzone),
                            onResetToDefault = { onSetGamepadStickDeadzone(AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE) }
                        )

                        if (capabilities.supportsAnalogControls) LiveSliderRow(
                            title = stringResource(R.string.settings_gamepad_left_stick_sensitivity),
                            valueLabelForValue = { "$it%" },
                            value = uiState.gamepadLeftStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 149,
                            enabled = gamepadConnected,
                            onValueChange = { onSetGamepadLeftStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_left_stick_sensitivity),
                            onResetToDefault = { onSetGamepadLeftStickSensitivity(AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY) }
                        )

                        if (capabilities.supportsAnalogControls) LiveSliderRow(
                            title = stringResource(R.string.settings_gamepad_right_stick_sensitivity),
                            valueLabelForValue = { "$it%" },
                            value = uiState.gamepadRightStickSensitivity.toFloat(),
                            range = 50f..200f,
                            steps = 149,
                            enabled = gamepadConnected,
                            onValueChange = { onSetGamepadRightStickSensitivity(it.toInt()) },
                            helpText = stringResource(R.string.settings_help_gamepad_right_stick_sensitivity),
                            onResetToDefault = { onSetGamepadRightStickSensitivity(AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY) }
                        )

                        if (capabilities.supportsAzaharOptions) {
                            SettingsToggle(
                                title = stringResource(R.string.settings_touchscreen_left_stick),
                                checked = uiState.touchscreenLeftStick,
                                onCheckedChange = onSetTouchscreenLeftStick,
                                helpText = stringResource(R.string.settings_help_touchscreen_left_stick),
                                onResetToDefault = { onSetTouchscreenLeftStick(globalDefaults.touchscreenLeftStick) }
                            )

                            if (uiState.touchscreenLeftStick) {
                                LiveSliderRow(
                                    title = stringResource(R.string.settings_touchscreen_left_stick_sensitivity),
                                    valueLabelForValue = { "$it%" },
                                    value = uiState.touchscreenLeftStickSensitivity.toFloat(),
                                    range = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN.toFloat()..
                                        AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX.toFloat(),
                                    steps = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX -
                                        AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN - 1,
                                    onValueChange = { onSetTouchscreenLeftStickSensitivity(it.toInt()) },
                                    helpText = stringResource(R.string.settings_help_touchscreen_left_stick_sensitivity),
                                    onResetToDefault = {
                                        onSetTouchscreenLeftStickSensitivity(
                                            globalDefaults.touchscreenLeftStickSensitivity
                                        )
                                    }
                                )
                            }

                            SettingsToggle(
                                title = stringResource(R.string.settings_touchscreen_right_stick),
                                checked = uiState.touchscreenRightStick,
                                onCheckedChange = onSetTouchscreenRightStick,
                                helpText = stringResource(R.string.settings_help_touchscreen_right_stick),
                                onResetToDefault = { onSetTouchscreenRightStick(globalDefaults.touchscreenRightStick) }
                            )

                            if (uiState.touchscreenRightStick) {
                                LiveSliderRow(
                                    title = stringResource(R.string.settings_touchscreen_right_stick_sensitivity),
                                    valueLabelForValue = { "$it%" },
                                    value = uiState.touchscreenRightStickSensitivity.toFloat(),
                                    range = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN.toFloat()..
                                        AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX.toFloat(),
                                    steps = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX -
                                        AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN - 1,
                                    onValueChange = { onSetTouchscreenRightStickSensitivity(it.toInt()) },
                                    helpText = stringResource(R.string.settings_help_touchscreen_right_stick_sensitivity),
                                    onResetToDefault = {
                                        onSetTouchscreenRightStickSensitivity(
                                            globalDefaults.touchscreenRightStickSensitivity
                                        )
                                    }
                                )
                            }
                        }

                        MenuButton(
                            icon = Icons.Rounded.Gamepad,
                            text = stringResource(R.string.settings_gamepad_mapping_title),
                            onClick = onOpenGamepadMapping,
                            enabled = gamepadConnected,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                        )

                        MenuButton(
                            icon = Icons.Rounded.Keyboard,
                            text = stringResource(R.string.settings_keyboard_mouse_section),
                            onClick = onOpenKeyboardMouseSettings,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                        )
                                    }

                                    else -> Unit
                                }
                            }
                    }

                    EmulationMenuTab.Emulation -> {
                        gameMenuSectionsForTab(GameMenuTabId.EMULATION, uiState.gameMenuSectionOrder)
                            .filterNot(uiState.hiddenGameMenuSections::contains)
                            .filter(capabilities::supportsGameMenuSection)
                            .forEach { section ->
                                when (section) {
                                    GameMenuSectionId.EMULATION_PERFORMANCE -> EmulationPerformanceSection(
                                        uiState = uiState,
                                        globalDefaults = globalDefaults,
                                        supportsFrameLimit = capabilities.supportsAzaharOptions,
                                        sectionTitleColor = sectionTitleColor,
                                        sectionLabelTopPadding = sectionLabelTopPadding,
                                        sectionLabelInset = sectionLabelInset,
                                        onToggleFps = onToggleFps,
                                        onSetFrameLimit = onSetFrameLimit,
                                        onSetFpsOverlayMode = onSetFpsOverlayMode,
                                        onSetFpsOverlayCorner = onSetFpsOverlayCorner,
                                        onSetFpsOverlayScale = onSetFpsOverlayScale,
                                        onSetFpsOverlayMetrics = onSetFpsOverlayMetrics
                                    )

                                    GameMenuSectionId.EMULATION_CPU -> {
                                        if (usesAdditionalCore) {
                                            ConsoleCoreOptionsPanel(
                                                uiState.gamePlatform.console,
                                                SettingsCategory.Emulation,
                                                uiState.perGameCoreOptions,
                                                onSetCoreOption,
                                                card = false
                                            )
                                        } else {
                                            SidebarSectionTitle(
                                                text = stringResource(R.string.settings_emulation_tab).uppercase(),
                                                color = sectionTitleColor,
                                                topPadding = sectionLabelTopPadding,
                                                horizontalInset = sectionLabelInset
                                            )
                                            if (capabilities.supportsAzaharOptions) {
                                                CoreOptionRows(
                                                    options = AzaharCoreOptions.gameMenuEmulationOptions(),
                                                    version = coreOptionsVersion,
                                                    perGameOverrides = uiState.perGameCoreOptions,
                                                    onValueChange = onCoreOptionChange
                                                )
                                            }
                                        }
                                    }

                                    GameMenuSectionId.EMULATION_AUDIO -> {
                                        if (usesAdditionalCore) {
                                            ConsoleCoreOptionsPanel(
                                                uiState.gamePlatform.console,
                                                SettingsCategory.Audio,
                                                uiState.perGameCoreOptions,
                                                onSetCoreOption,
                                                card = false
                                            )
                                        } else {
                                            SidebarSectionTitle(
                                                text = stringResource(R.string.settings_audio_tab).uppercase(),
                                                color = sectionTitleColor,
                                                topPadding = sectionLabelTopPadding,
                                                horizontalInset = sectionLabelInset
                                            )

                                            SettingsToggle(
                                                title = stringResource(R.string.settings_audio_mute),
                                                checked = uiState.audioMuted,
                                                onCheckedChange = onSetAudioMuted,
                                                helpText = stringResource(R.string.settings_help_audio_mute),
                                                onResetToDefault = { onSetAudioMuted(globalDefaults.audioMuted) }
                                            )

                                            LiveSliderRow(
                                                title = stringResource(R.string.settings_audio_volume),
                                                valueLabelForValue = { "$it%" },
                                                value = uiState.audioVolume.toFloat(),
                                                range = AudioDefaults.VOLUME_MIN.toFloat()..AudioDefaults.VOLUME_MAX.toFloat(),
                                                steps = 0,
                                                onValueChange = { onSetAudioVolume(it.toInt()) },
                                                helpText = stringResource(R.string.settings_help_audio_volume),
                                                onResetToDefault = { onSetAudioVolume(globalDefaults.audioVolume) }
                                            )
                                        }
                                    }

                                    else -> Unit
                                }
                            }
                    }

                    EmulationMenuTab.Cheats -> {
                        gameMenuSectionsForTab(GameMenuTabId.CHEATS, uiState.gameMenuSectionOrder)
                            .filterNot(uiState.hiddenGameMenuSections::contains)
                            .forEach { section ->
                                when (section) {
                                    GameMenuSectionId.EMULATION_CHEATS -> {
                        SidebarSectionTitle(
                            text = stringResource(R.string.emulation_cheats_title).uppercase(),
                            color = sectionTitleColor,
                            topPadding = sectionLabelTopPadding,
                            horizontalInset = sectionLabelInset
                        )

                        SettingsToggle(
                            title = stringResource(R.string.settings_enable_cheats),
                            checked = uiState.enableCheats,
                            onCheckedChange = onSetEnableCheats,
                            helpText = stringResource(R.string.settings_help_cheats),
                            onResetToDefault = { onSetEnableCheats(globalDefaults.enableCheats) }
                        )

                        EmulationCheatsSection(
                            blocks = uiState.availableCheats,
                            onCheatToggle = onToggleCheat,
                            onGroupToggle = onSetCheatGroupEnabled
                        )
                                    }

                                    else -> Unit
                                }
                            }
                    }

                    EmulationMenuTab.Graphics -> {
                        if (usesAdditionalCore) {
                            ConsoleCoreOptionsPanel(uiState.gamePlatform.console, SettingsCategory.Graphics,
                                uiState.perGameCoreOptions, onSetCoreOption, card = false)
                        } else {

                        gameMenuSectionsForTab(GameMenuTabId.GRAPHICS, uiState.gameMenuSectionOrder)
                            .filterNot(uiState.hiddenGameMenuSections::contains)
                            .forEach { section ->
                                when (section) {
                                    GameMenuSectionId.GRAPHICS_DISPLAY -> {
                        SidebarSectionTitle(
                            text = stringResource(R.string.settings_graphics_tab).uppercase(),
                            color = sectionTitleColor,
                            topPadding = sectionLabelTopPadding,
                            horizontalInset = sectionLabelInset
                        )

                        if (uiState.gamePlatform == GamePlatform.NDS) {
                            ScreenSwapMenuRow(
                                platform = uiState.gamePlatform,
                                swapped = uiState.swapScreens,
                                enabled = !uiState.isActionInProgress,
                                onSwapScreens = onSwapScreens
                            )
                        }

                        LiveSelectionRow(
                            title = stringResource(R.string.settings_renderer),
                            options = capabilities.rendererIds.map { renderer ->
                                LiveSelectionOption(renderer, stringResource(when (renderer) {
                                    12 -> R.string.settings_renderer_opengl
                                    13 -> R.string.settings_renderer_software
                                    else -> R.string.settings_renderer_vulkan
                                }))
                            },
                            currentValue = capabilities.normalizeRenderer(uiState.renderer),
                            onValueChange = onSetRenderer,
                            helpText = stringResource(if (uiState.gamePlatform == GamePlatform.NDS) R.string.legacy_help_ds_renderer else R.string.settings_help_renderer),
                            onResetToDefault = {
                                onSetRenderer(capabilities.normalizeRenderer(globalDefaults.renderer))
                            }
                        )

                        // Internal resolution belongs to the app-level per-game
                        // upscale setting (uiState.upscale). Binding this row to
                        // the raw core option
                        // persisted changes to the global core-option store, so
                        // they leaked into every game and never appeared in the
                        // game manager.
                        if (capabilities.supportsInternalUpscaling(uiState.renderer)) LiveSelectionRow(
                            title = stringResource(R.string.settings_upscale),
                            options = buildUpscaleOptions(
                                stringResource(R.string.settings_upscale_native),
                                capabilities.maxUpscaleMultiplier(EmulatorBridge.getMaxUpscaleMultiplier(uiState.renderer))
                            ).map { (value, label) -> LiveSelectionOption(value, label) },
                            currentValue = upscaleMultiplierValue(uiState.upscale.coerceIn(1f, capabilities.maxUpscaleMultiplier(10).toFloat())),
                            onValueChange = { onSetUpscale(upscaleKeyToMultiplier(it)) },
                            helpText = stringResource(if (uiState.gamePlatform == GamePlatform.NDS) R.string.legacy_help_ds_upscale else R.string.settings_help_upscale),
                            onResetToDefault = { onSetUpscale(globalDefaults.upscaleMultiplier.coerceIn(1f, capabilities.maxUpscaleMultiplier(10).toFloat())) }
                        ) else Text(stringResource(R.string.settings_ds_software_native_resolution))

                        if (capabilities.supportsAzaharOptions) {
                            LiveSelectionRow(
                                title = stringResource(R.string.settings_aspect_ratio).replace(":", ""),
                                options = listOf(
                                    LiveSelectionOption(1, stringResource(R.string.emulation_aspect_auto)),
                                    LiveSelectionOption(2, "4:3"),
                                    LiveSelectionOption(3, "16:9"),
                                    LiveSelectionOption(
                                        value = 0,
                                        icon = Icons.Rounded.Fullscreen,
                                        contentDescription = stringResource(R.string.emulation_aspect_stretch)
                                    )
                                ),
                                currentValue = uiState.aspectRatio,
                                onValueChange = onSetAspectRatio,
                                helpText = stringResource(R.string.settings_help_aspect_ratio),
                                onResetToDefault = { onSetAspectRatio(globalDefaults.aspectRatio) }
                            )

                            SecondScreenSettingsRows()
                        }

                        if (capabilities.supportsAzaharOptions) {
                            CoreOptionRows(
                                options = AzaharCoreOptions.gameMenuGraphicsOptions().filterNot { it.key == "swap_screen" },
                                version = coreOptionsVersion,
                                perGameOverrides = uiState.perGameCoreOptions,
                                onValueChange = onCoreOptionChange
                            )
                        }

                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }

                    EmulationMenuTab.Achievements -> {
                        InGameAchievementsTab()
                    }

                }
    }

    GameMenuLayoutHost(
        style = if (isNds) GameMenuLayoutStyle.COMPACT else uiState.gameMenuLayoutStyle,
        compactForNds = isNds,
        modifier = modifier,
        menuTabs = menuTabs,
        selectedMenuTab = selectedMenuTab,
        selectedTabScrollState = selectedTabScrollState,
        railFocusRequesters = railFocusRequesters,
        contentBottomPadding = drawerBottomPadding + animatedBottomInset,
        rightInset = animatedRightInset,
        sectionSpacing = sectionSpacing,
        onSelectTab = { selectedMenuTabName = it.name },
        onClose = onClose,
        content = menuContent
    )
}

@Composable
private fun GameMenuLayoutHost(
    style: GameMenuLayoutStyle,
    compactForNds: Boolean,
    modifier: Modifier,
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    selectedTabScrollState: ScrollState,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    contentBottomPadding: Dp,
    rightInset: Dp,
    sectionSpacing: Dp,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val safeModifier = modifier
        .padding(WindowInsets.displayCutout.asPaddingValues())
        .padding(WindowInsets.statusBars.asPaddingValues())

    when (style) {
        GameMenuLayoutStyle.SIDEBAR -> {
            Row(
                modifier = safeModifier.padding(end = 16.dp + rightInset, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                GameMenuContentSurface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 300.dp, max = 420.dp),
                    shape = neonShape(28.dp),
                    scrollState = selectedTabScrollState,
                    bottomPadding = contentBottomPadding,
                    sectionSpacing = sectionSpacing,
                    content = content
                )
                Spacer(modifier = Modifier.width(12.dp))
                GameMenuVerticalNavigation(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(74.dp),
                    menuTabs = menuTabs,
                    selectedMenuTab = selectedMenuTab,
                    railFocusRequesters = railFocusRequesters,
                    showLabels = false,
                    onSelectTab = onSelectTab,
                    onClose = onClose
                )
            }
        }

        GameMenuLayoutStyle.DASHBOARD -> {
            Box(
                modifier = safeModifier
                    .fillMaxSize()
                    .padding(end = rightInset, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(),
                    shape = neonShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    shadowElevation = 12.dp
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        GameMenuVerticalNavigation(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(196.dp),
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            showLabels = true,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = 18.dp,
                            sectionSpacing = sectionSpacing,
                            content = content
                        )
                    }
                }
            }
        }

        GameMenuLayoutStyle.COMMAND_CENTER -> {
            Box(
                modifier = safeModifier
                    .fillMaxSize()
                    .padding(end = rightInset, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .fillMaxHeight(),
                    shape = neonShapeCorners(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
                    shadowElevation = 14.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        GameMenuHorizontalNavigation(
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            iconOnly = false,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = 18.dp,
                            sectionSpacing = sectionSpacing,
                            content = content
                        )
                    }
                }
            }
        }

        GameMenuLayoutStyle.COMPACT -> {
                Box(
                    modifier = safeModifier
                        .fillMaxSize()
                        .padding(
                            start = if (compactForNds) 12.dp else 0.dp,
                            end = 12.dp + rightInset,
                            top = 16.dp,
                            bottom = 16.dp
                        ),
                    contentAlignment = if (compactForNds) Alignment.Center else Alignment.CenterEnd
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(
                                min = 300.dp,
                                max = if (compactForNds) 420.dp else 352.dp
                            ),
                    shape = neonShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    shadowElevation = 10.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        GameMenuHorizontalNavigation(
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            iconOnly = true,
                            pinClose = true,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = contentBottomPadding,
                            sectionSpacing = 10.dp,
                            horizontalPadding = 12.dp,
                            verticalPadding = 12.dp,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameMenuContentSurface(
    modifier: Modifier,
    shape: Shape,
    scrollState: ScrollState,
    bottomPadding: Dp,
    sectionSpacing: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    ) {
        GameMenuContentColumn(
            scrollState = scrollState,
            bottomPadding = bottomPadding,
            sectionSpacing = sectionSpacing,
            content = content
        )
    }
}

@Composable
private fun GameMenuContentColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    bottomPadding: Dp,
    sectionSpacing: Dp,
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(
        LocalSettingsHorizontalPadding provides 0.dp,
        LocalSettingsHorizontalScrollBleed provides horizontalPadding
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
            content = content
        )
    }
}

@Composable
private fun GameMenuVerticalNavigation(
    modifier: Modifier,
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    showLabels: Boolean,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = if (showLabels) neonShapeCorners(topStart = 24.dp, bottomStart = 24.dp) else neonShape(24.dp),
        color = if (showLabels) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
    ) {
        val railScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(railScrollState)
                .padding(vertical = 16.dp, horizontal = if (showLabels) 12.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            menuTabs.forEach { tab ->
                if (showLabels) {
                    GameMenuNavigationTile(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                        tab = tab,
                        selected = selectedMenuTab == tab,
                        iconOnly = false,
                        onClick = { onSelectTab(tab) }
                    )
                } else {
                    EmulationMenuRailButton(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                        icon = gameMenuTabIcon(tab),
                        contentDescription = gameMenuTabLabel(tab),
                        selected = selectedMenuTab == tab,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (showLabels) {
                GameMenuNavigationTile(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    label = stringResource(R.string.emulation_close_menu),
                    selected = false,
                    destructive = true,
                    iconOnly = false,
                    onClick = onClose
                )
            } else {
                EmulationMenuRailButton(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    contentDescription = stringResource(R.string.emulation_close_menu),
                    selected = false,
                    onClick = onClose,
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
private fun GameMenuHorizontalNavigation(
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    iconOnly: Boolean,
    pinClose: Boolean = false,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit
) {
    if (pinClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .tvFocusGroup(),
                contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(menuTabs, key = { it.name }) { tab ->
                    GameMenuNavigationTile(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                        tab = tab,
                        selected = selectedMenuTab == tab,
                        iconOnly = iconOnly,
                        fillWidth = false,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
            GameMenuNavigationTile(
                modifier = Modifier.padding(end = 12.dp),
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                label = stringResource(R.string.emulation_close_menu),
                selected = false,
                destructive = true,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = onClose
            )
        }
        return
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGroup(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(menuTabs, key = { it.name }) { tab ->
            GameMenuNavigationTile(
                modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                tab = tab,
                selected = selectedMenuTab == tab,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = { onSelectTab(tab) }
            )
        }
        item(key = "close") {
            GameMenuNavigationTile(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                label = stringResource(R.string.emulation_close_menu),
                selected = false,
                destructive = true,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun GameMenuNavigationTile(
    modifier: Modifier = Modifier,
    tab: EmulationMenuTab? = null,
    icon: ImageVector? = null,
    label: String? = null,
    selected: Boolean,
    destructive: Boolean = false,
    iconOnly: Boolean,
    fillWidth: Boolean = !iconOnly,
    onClick: () -> Unit
) {
    val resolvedIcon = icon ?: tab?.let(::gameMenuTabIcon) ?: Icons.Rounded.Menu
    val resolvedLabel = label ?: tab?.let { gameMenuTabLabel(it) }.orEmpty()
    val shape = neonShape(if (iconOnly) 14.dp else 16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        shape = shape,
        color = when {
            destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
        },
        border = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            when {
                isFocused -> MaterialTheme.colorScheme.primary
                destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (iconOnly) 13.dp else 14.dp,
                vertical = if (iconOnly) 12.dp else 13.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = resolvedLabel,
                tint = when {
                    destructive -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(21.dp)
            )
            if (!iconOnly) {
                Text(
                    text = resolvedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        destructive -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun gameMenuTabIcon(tab: EmulationMenuTab): ImageVector = when (tab) {
    EmulationMenuTab.Session -> Icons.Rounded.Menu
    EmulationMenuTab.Controls -> Icons.Rounded.Gamepad
    EmulationMenuTab.Emulation -> Icons.Rounded.SettingsSuggest
    EmulationMenuTab.Cheats -> Icons.Rounded.Star
    EmulationMenuTab.Graphics -> Icons.Rounded.Fullscreen
    EmulationMenuTab.Achievements -> Icons.Rounded.EmojiEvents
}

@Composable
private fun gameMenuTabLabel(tab: EmulationMenuTab): String = when (tab) {
    EmulationMenuTab.Session -> stringResource(R.string.emulation_session_tab)
    EmulationMenuTab.Controls -> stringResource(R.string.settings_controls_tab)
    EmulationMenuTab.Emulation -> stringResource(R.string.settings_emulation_tab)
    EmulationMenuTab.Cheats -> stringResource(R.string.emulation_cheats_title)
    EmulationMenuTab.Graphics -> stringResource(R.string.settings_graphics_tab)
    EmulationMenuTab.Achievements -> stringResource(R.string.achievements_title)
}

@Composable
private fun EmulationMenuRailButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val shape = neonShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val iconScale by animateFloatAsState(
        targetValue = if (selected && !isDestructive) 1.08f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "emulation_menu_rail_icon_scale"
    )
    val border = BorderStroke(
        if (isFocused) 2.dp else 1.dp,
        when {
            isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
        }
    )
    Surface(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        shape = shape,
        color = when {
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        border = border
    ) {
        Box(
            modifier = Modifier
                .size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    isDestructive -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer(
                        scaleX = iconScale,
                        scaleY = iconScale
                    )
            )
        }
    }
}

@Composable
private fun EmulationGamepadBindingRow(
    title: String,
    value: String,
    autoLabel: String?,
    onBindClick: () -> Unit,
    onClearClick: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        onClick = onBindClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Gamepad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    autoLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            onClearClick?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.settings_gamepad_mapping_clear))
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun gamepadActionLabelRes(actionId: String): Int = when (actionId) {
    "b" -> R.string.settings_gamepad_action_b
    "a" -> R.string.settings_gamepad_action_a
    "y" -> R.string.settings_gamepad_action_y
    "x" -> R.string.settings_gamepad_action_x
    "l" -> R.string.settings_gamepad_action_l
    "r" -> R.string.settings_gamepad_action_r
    "zl" -> R.string.settings_gamepad_action_zl
    "zr" -> R.string.settings_gamepad_action_zr
    "home" -> R.string.settings_gamepad_action_home
    "touch" -> R.string.settings_gamepad_action_touch
    "select" -> R.string.settings_gamepad_action_select
    "start" -> R.string.settings_gamepad_action_start
    "left_input_toggle" -> R.string.settings_gamepad_action_left_input_toggle
    GamepadManager.ACTION_QUICK_SAVE -> R.string.emulation_quick_save
    GamepadManager.ACTION_QUICK_LOAD -> R.string.emulation_quick_load
    GamepadManager.ACTION_TOGGLE_SECOND_SCREEN -> R.string.emulation_second_screen
    GamepadManager.ACTION_SWAP_SCREENS -> R.string.settings_second_screen_swap_now
    GamepadManager.ACTION_FAST_FORWARD -> R.string.emulation_fast_forward
    GamepadManager.ACTION_REWIND -> R.string.emulation_rewind
    "dpad_up" -> R.string.settings_gamepad_action_dpad_up
    "dpad_down" -> R.string.settings_gamepad_action_dpad_down
    "dpad_left" -> R.string.settings_gamepad_action_dpad_left
    "dpad_right" -> R.string.settings_gamepad_action_dpad_right
    "left_stick_up" -> R.string.settings_gamepad_action_left_stick_up
    "left_stick_down" -> R.string.settings_gamepad_action_left_stick_down
    "left_stick_left" -> R.string.settings_gamepad_action_left_stick_left
    "left_stick_right" -> R.string.settings_gamepad_action_left_stick_right
    "right_stick_up" -> R.string.settings_gamepad_action_right_stick_up
    "right_stick_down" -> R.string.settings_gamepad_action_right_stick_down
    "right_stick_left" -> R.string.settings_gamepad_action_right_stick_left
    "right_stick_right" -> R.string.settings_gamepad_action_right_stick_right
    else -> R.string.settings_gamepad_section
}

@Composable
private fun gamepadActionLabel(actionId: String): String =
    stringResource(gamepadActionLabelRes(actionId))

@Composable
private fun gamepadPlayerLabel(padIndex: Int): String {
    return stringResource(
        if (padIndex == 0) R.string.settings_gamepad_player_1 else R.string.settings_gamepad_player_2
    )
}

@Composable
private fun ConnectedInputBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = true
) {
    val accent = Color(0xFF50D9A0)
    Box(
        modifier = Modifier
            .clip(neonShape(12.dp))
            .background(accent.copy(alpha = if (active) 0.20f else 0.10f))
            .padding(horizontal = 9.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) accent else accent.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
internal fun ScreenSwapMenuRow(
    platform: GamePlatform,
    swapped: Boolean,
    enabled: Boolean = true,
    onSwapScreens: () -> Unit
) {
    if (!com.sbro.emucored.core.platform.CoreCapabilities.forPlatform(platform.console).supportsScreenSwap) return
    SettingsToggle(
        title = stringResource(R.string.settings_gamepad_action_swap_screen),
        helpText = stringResource(R.string.legacy_help_screen_swap),
        checked = swapped,
        enabled = enabled,
        onCheckedChange = { requested -> if (requested != swapped) onSwapScreens() },
        modifier = Modifier
            .testTag("game-menu-screen-swap")
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = if (swapped) ToggleableState.On else ToggleableState.Off
            }
    )
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val shape = neonShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
                onLongClick = if (enabled) onResetToDefault?.let {
                    {
                        it()
                        Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                    }
                } else null
            )
            .gamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                helpText?.let {
                    SettingHelpButton(title = title, description = it)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.scale(0.85f),
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
            )
        }
    }
}

@Composable
private fun SidebarSectionTitle(
    text: String,
    color: Color,
    topPadding: Dp,
    horizontalInset: Dp
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        ),
        color = color,
        modifier = Modifier.padding(top = topPadding, start = horizontalInset, end = horizontalInset)
    )
}

@Composable
private fun SecondScreenSettingsRows() {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val mode by preferences.secondScreenMode.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_MODE_PANEL
    )
    val width by preferences.secondScreenWidth.collectAsState(
        initial = AppPreferences.SECOND_SCREEN_WIDTH_DEFAULT
    )

    LiveSelectionRow(
        title = stringResource(R.string.settings_second_screen),
        options = listOf(
            LiveSelectionOption(
                AppPreferences.SECOND_SCREEN_MODE_INTEGRATED,
                stringResource(R.string.settings_second_screen_integrated)
            ),
            LiveSelectionOption(
                AppPreferences.SECOND_SCREEN_MODE_PANEL,
                stringResource(R.string.settings_second_screen_panel)
            ),
            LiveSelectionOption(
                AppPreferences.SECOND_SCREEN_MODE_FLOATING,
                stringResource(R.string.settings_second_screen_floating)
            )
        ),
        currentValue = mode,
        onValueChange = { scope.launch { preferences.setSecondScreenMode(it) } },
        helpText = stringResource(R.string.settings_second_screen_desc),
        onResetToDefault = {
            scope.launch { preferences.setSecondScreenMode(AppPreferences.SECOND_SCREEN_MODE_INTEGRATED) }
        }
    )

    if (mode == AppPreferences.SECOND_SCREEN_MODE_PANEL) {
        LiveSelectionRow(
            title = stringResource(R.string.settings_second_screen_width),
            helpText = stringResource(R.string.legacy_help_second_screen_width),
            options = (AppPreferences.SECOND_SCREEN_WIDTH_MIN..AppPreferences.SECOND_SCREEN_WIDTH_MAX step 2)
                .map { LiveSelectionOption(it, "$it%") },
            currentValue = width,
            onValueChange = { scope.launch { preferences.setSecondScreenWidth(it) } },
        )
    }
}

@Composable
private fun LiveSelectionRow(
    title: String,
    options: List<LiveSelectionOption>,
    currentValue: Int,
    onValueChange: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val scrollBleed = LocalSettingsHorizontalScrollBleed.current
    val scrollContentPadding = LocalSettingsHorizontalPadding.current + scrollBleed
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        // Option variants always scroll horizontally instead of wrapping onto
        // extra rows. The row bleeds past the drawer padding while the padding
        // lives on the scrolling content, so chips can be scrolled all the way
        // to the card edges.
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalViewportBleed(scrollBleed),
            contentPadding = PaddingValues(horizontal = scrollContentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options, key = { it.value }) { option ->
                LiveSelectionChip(
                    option = option,
                    selected = option.value == currentValue,
                    onClick = { onValueChange(option.value) }
                )
            }
        }
    }
}

@Composable
private fun InGameAchievementsTab() {
    val context = LocalContext.current
    val repository = remember(context) { RetroAchievementsRepository.get(context) }
    var state by remember { mutableStateOf(repository.state()) }
    var items by remember { mutableStateOf(repository.achievements()) }

    LaunchedEffect(Unit) {
        repository.ensureInitialized()
        while (true) {
            val newState = withContext(Dispatchers.IO) { repository.state() }
            val newItems = withContext(Dispatchers.IO) { repository.achievements() }
            state = newState
            items = newItems
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            !state.available -> RaNoticeCard(stringResource(R.string.achievements_unavailable))

            !state.enabled -> RaNoticeCard(stringResource(R.string.achievements_disabled))

            !state.loggedIn -> RaNoticeCard(stringResource(R.string.achievements_not_signed_in))

            state.loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.achievements_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            state.unsupportedImage -> RaNoticeCard(stringResource(R.string.achievements_unsupported_image))

            state.imageReadError -> RaNoticeCard(stringResource(R.string.achievements_game_unavailable), isError = true)

            state.lastError != null -> RaNoticeCard(
                stringResource(R.string.achievements_error, state.lastError.orEmpty()), isError = true
            )

            !state.gameLoaded && state.game == null -> RaNoticeCard(stringResource(R.string.achievements_no_game))

            else -> {
                val game = state.game
                val summary = state.summary
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = neonShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = game?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.richPresence.isNotBlank()) {
                            Text(
                                text = state.richPresence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RaMetricCard(
                                modifier = Modifier.weight(1f),
                                value = "${summary.unlocked}/${summary.total}",
                                label = stringResource(R.string.achievements_title)
                            )
                            RaMetricCard(
                                modifier = Modifier.weight(1f),
                                value = "${summary.pointsUnlocked}/${summary.points}",
                                label = stringResource(R.string.achievements_points_label)
                            )
                        }
                        if (summary.total > 0) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { summary.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (summary.completed) {
                            Text(
                                text = stringResource(R.string.achievements_mastered),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                state.lastError?.let { error ->
                    RaNoticeCard(stringResource(R.string.achievements_error, error), isError = true)
                }
                val visibleItems = items.filterNot { it.isWarning }
                if (visibleItems.isEmpty()) {
                    RaNoticeCard(stringResource(R.string.achievements_none))
                } else {
                    visibleItems
                        .sortedWith(compareByDescending<AchievementItem> { it.unlocked }.thenBy { it.id })
                        .forEach { item -> RaAchievementCard(item) }
                }
            }
        }
    }
}

@Composable
private fun RaBadge(text: String, containerColor: Color, contentColor: Color) {
    Surface(shape = neonShape(8.dp), color = containerColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor
        )
    }
}

@Composable
private fun RaNoticeCard(text: String, isError: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RaMetricCard(modifier: Modifier = Modifier, value: String, label: String) {
    Surface(
        modifier = modifier,
        shape = neonShape(15.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RaAchievementCard(item: AchievementItem) {
    val borderColor = if (item.unlocked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(17.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badge = if (item.unlocked) {
                item.badgeUrl.takeIf { it.isNotBlank() } ?: item.badgeLockedUrl
            } else {
                item.badgeLockedUrl.takeIf { it.isNotBlank() } ?: item.badgeUrl
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                if (badge.isNotBlank()) {
                    AsyncImage(
                        model = badge,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(neonShape(12.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (item.unlocked) 1f else 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (item.unlocked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!item.unlocked && item.progressText.isNotBlank()) {
                    Text(
                        text = item.progressText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RaBadge(
                        text = stringResource(R.string.achievements_points_badge, item.points),
                        containerColor = if (item.unlocked) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        contentColor = if (item.unlocked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        }
                    )
                    RaBadge(
                        text = stringResource(
                            if (item.unlocked) R.string.achievements_unlocked_header
                            else R.string.achievements_locked_header
                        ),
                        containerColor = if (item.unlocked) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        contentColor = if (item.unlocked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        }
                    )
                }
            }
        }
    }
}

@Suppress("UNUSED_EXPRESSION")
@Composable
private fun CoreOptionRows(
    options: List<AzaharCoreOptions.Option>,
    version: Int,
    perGameOverrides: Map<String, String>,
    onValueChange: (String, String) -> Unit
) {
    version
    val context = LocalContext.current
    options.forEach { option ->
        val values = option.choices.map { it.value }
        val current = perGameOverrides[option.key]
            ?: NativeApp.getCoreOption(option.key)
            ?: option.defaultValue
        val currentIndex = values.indexOf(current).let { if (it >= 0) it else 0 }
        val localized = AzaharCoreOptionLocalization.resolve(context, option)
        val title = localized.label
        val help = azaharCoreOptionHelpRes(option.key)?.let { stringResource(it) }
        val choiceLabels = localized.choices.map { it.label }
        if (option.isBooleanToggle) {
            SettingsToggle(
                title = title,
                checked = option.isEnabled(current),
                onCheckedChange = { enabled -> onValueChange(option.key, option.valueForEnabled(enabled)) },
                helpText = help,
                onResetToDefault = { onValueChange(option.key, option.defaultValue) }
            )
        } else {
            LiveSelectionRow(
                title = title,
                options = option.choices.mapIndexed { index, _ ->
                    LiveSelectionOption(index, choiceLabels[index])
                },
                currentValue = currentIndex,
                onValueChange = { index ->
                    values.getOrNull(index)?.let { onValueChange(option.key, it) }
                },
                helpText = help,
                onResetToDefault = { onValueChange(option.key, option.defaultValue) }
            )
        }
    }
}

@Composable
private fun LiveSelectionChip(
    option: LiveSelectionOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        shape = neonChipShape(),
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        colors = premiumFilterChipColors(),
        leadingIcon = option.icon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = option.contentDescription,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        label = {
            Text(
                text = option.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    )
}

@Composable
private fun TransportStatusOverlay(mode: EmulationTransportMode) {
    if (mode == EmulationTransportMode.None) return
    val fastForward = mode == EmulationTransportMode.FastForward
    Surface(
        shape = neonShape(8.dp),
        color = Color(0xDD10131A),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (fastForward) ">>" else "<<",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                )
            )
            Text(
                text = stringResource(
                    if (fastForward) {
                        R.string.emulation_transport_fast_forward
                    } else {
                        R.string.emulation_transport_rewind
                    }
                ),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp
                )
            )
        }
    }
}

@Composable
private fun SimpleFpsCounter(
    fps: String,
    fontScale: Float
) {
    val safeScale = fontScale.coerceIn(0.75f, 2f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp * safeScale, vertical = 5.dp * safeScale)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.emulation_hud_fps_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp * safeScale,
                    lineHeight = 14.sp * safeScale,
                    letterSpacing = 0.2.sp
                ),
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = fps,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp * safeScale,
                    lineHeight = 18.sp * safeScale
                ),
                color = if (fps.toFloatOrNull()?.let { it >= 55f } == true) {
                    Color(0xFF50D9A0)
                } else {
                    Color(0xFFFFB85C)
                }
            )
        }
    }
}

@Composable
private fun SystemPerformanceHud(
    speedPercent: Float,
    text: String,
    fixedHeaderLine: String,
    isRightCorner: Boolean = false,
    fontScale: Float,
    metricsMask: Int
) {
    val safeScale = fontScale.coerceIn(0.75f, 2f)
    val lines = remember(text, metricsMask, fixedHeaderLine) {
        buildPerformanceOverlayLayout(text, metricsMask, fixedHeaderLine)
    }
    val mainLines = lines.mainLines
    val bottomLines = lines.bottomLines
    val textAlign = if (isRightCorner) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier
            .widthIn(min = 190.dp, max = 330.dp * safeScale.coerceAtMost(1.75f))
            .padding(horizontal = 6.dp * safeScale, vertical = 4.dp * safeScale),
        verticalArrangement = Arrangement.spacedBy(1.dp * safeScale)
    ) {
        mainLines.forEach { line ->
            Text(
                text = buildPerformanceAnnotatedText(line, speedPercent),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp * safeScale,
                    lineHeight = 13.sp * safeScale,
                    letterSpacing = 0.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 1f),
                        offset = Offset(0f, 0f),
                        blurRadius = 6f * safeScale
                    )
                ),
                color = Color.White.copy(alpha = 0.97f),
                textAlign = textAlign,
                softWrap = true
            )
        }
        if (bottomLines.isNotEmpty()) {
            if (mainLines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp * safeScale))
            }
            bottomLines.forEach { line ->
                Text(
                    text = buildPerformanceAnnotatedText(line, speedPercent),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 8.5.sp * safeScale,
                        lineHeight = 11.sp * safeScale,
                        letterSpacing = 0.sp,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 1f),
                            offset = Offset(0f, 0f),
                            blurRadius = 5f * safeScale
                        )
                    ),
                    color = Color.White.copy(alpha = 0.97f),
                    textAlign = textAlign,
                    softWrap = true
                )
            }
        }
    }
}

private fun buildPerformanceAnnotatedText(text: String, speedPercent: Float): AnnotatedString {
    val speedColor = when {
        speedPercent < 90f -> Color(0xFFFF5A5A)
        speedPercent < 99f -> Color(0xFFFFC04D)
        speedPercent <= 101f -> Color(0xFF7CFF7C)
        speedPercent <= 110f -> Color(0xFF9BE870)
        else -> Color(0xFF59D2FF)
    }
    return buildAnnotatedString {
        append(text)
        addRepeatedStyle(
            text,
            listOf(
                "EmuCoreD", "FPS:", "VPS:", "Speed:", "Target:",
                "Frame:", "GS Queue:", "Res:", "CPU:", "GPU:",
                "Audio:", "GPU Core:", "JIT:", "CD-ROM:", "VRAM:"
            ),
            SpanStyle(color = Color(0xFF9DD7FF))
        )
        addRepeatedStyle(
            text,
            listOf("Vulkan", "OpenGL", "Software", "Null"),
            SpanStyle(color = Color(0xFF9DD7FF))
        )
        addLineValueStyle(text, "Speed:", speedColor)
        addLineValueStyle(text, "FPS:", Color(0xFFB9F7CF), stopAt = " | ")
        addLineValueStyle(text, "VPS:", Color(0xFFB9F7CF), stopAt = " | ")
        addLineValueStyle(text, "Target:", Color(0xFFB9F7CF), stopAt = " | ")
    }
}

private fun AnnotatedString.Builder.addRepeatedStyle(
    text: String,
    tokens: List<String>,
    style: SpanStyle
) {
    tokens.forEach { token ->
        var start = text.indexOf(token)
        while (start >= 0) {
            addStyle(style, start, start + token.length)
            start = text.indexOf(token, startIndex = start + token.length)
        }
    }
}

private fun AnnotatedString.Builder.addLineValueStyle(
    text: String,
    label: String,
    color: Color,
    stopAt: String? = null
) {
    val labelStart = text.indexOf(label)
    if (labelStart < 0) return
    val valueStart = labelStart + label.length
    val lineEnd = text.indexOf('\n', startIndex = valueStart).let { if (it == -1) text.length else it }
    val valueEnd = stopAt
        ?.let { separator -> text.indexOf(separator, startIndex = valueStart).takeIf { it in 0..<lineEnd } }
        ?: lineEnd
    if (valueStart < valueEnd)
        addStyle(SpanStyle(color = color), valueStart, valueEnd)
}

@Composable
private fun LiveChipsSelectionRow(
    title: String,
    options: List<Pair<Int, String>>,
    currentValue: Int,
    onValueChange: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val scrollBleed = LocalSettingsHorizontalScrollBleed.current
    val scrollContentPadding = LocalSettingsHorizontalPadding.current + scrollBleed
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalViewportBleed(scrollBleed),
            contentPadding = PaddingValues(horizontal = scrollContentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options) { (value, label) ->
                FilterChip(
                    shape = neonChipShape(),
                    selected = currentValue == value,
                    onClick = { onValueChange(value) },
                    colors = premiumFilterChipColors(),
                    label = { Text(text = label) }
                )
            }
        }
    }
}

@Composable
private fun LiveBitmaskChoiceRow(
    title: String,
    options: List<Pair<Int, String>>,
    selectedMask: Int,
    onToggle: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val scrollBleed = LocalSettingsHorizontalScrollBleed.current
    val scrollContentPadding = LocalSettingsHorizontalPadding.current + scrollBleed
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalViewportBleed(scrollBleed),
            contentPadding = PaddingValues(horizontal = scrollContentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(options) { (metric, label) ->
                FilterChip(
                    shape = neonChipShape(),
                    selected = PerformanceOverlayMetrics.isEnabled(selectedMask, metric),
                    onClick = { onToggle(metric) },
                    colors = premiumFilterChipColors(),
                    label = { Text(text = label) }
                )
            }
        }
    }
}

@Composable
private fun EmulationPerformanceSection(
    uiState: EmulationUiState,
    globalDefaults: SettingsSnapshot,
    supportsFrameLimit: Boolean,
    sectionTitleColor: Color,
    sectionLabelTopPadding: Dp,
    sectionLabelInset: Dp,
    onToggleFps: () -> Unit,
    onSetFrameLimit: () -> Unit,
    onSetFpsOverlayMode: (Int) -> Unit,
    onSetFpsOverlayCorner: (Int) -> Unit,
    onSetFpsOverlayScale: (Int) -> Unit,
    onSetFpsOverlayMetrics: (Int) -> Unit
) {
    SidebarSectionTitle(
        text = stringResource(R.string.settings_emulation_tab).uppercase(),
        color = sectionTitleColor,
        topPadding = sectionLabelTopPadding,
        horizontalInset = sectionLabelInset
    )

    SettingsToggle(
        title = stringResource(R.string.emulation_performance_stats),
        checked = uiState.showFps,
        onCheckedChange = { onToggleFps() },
        helpText = stringResource(R.string.settings_help_show_fps),
        onResetToDefault = {
            if (uiState.showFps != globalDefaults.showFps) {
                onToggleFps()
            }
        }
    )

    if (supportsFrameLimit) {
        SettingsToggle(
            title = stringResource(R.string.settings_frame_limit),
            checked = uiState.frameLimitEnabled,
            onCheckedChange = { onSetFrameLimit() },
            helpText = stringResource(R.string.settings_frame_limit_desc),
            onResetToDefault = {
                if (uiState.frameLimitEnabled != globalDefaults.frameLimitEnabled) {
                    onSetFrameLimit()
                }
            }
        )
    }

    LiveSelectionRow(
        title = stringResource(R.string.settings_fps_overlay_mode),
        options = listOf(
            LiveSelectionOption(FPS_OVERLAY_MODE_SIMPLE, stringResource(R.string.settings_fps_overlay_mode_simple)),
            LiveSelectionOption(FPS_OVERLAY_MODE_DETAILED, stringResource(R.string.settings_fps_overlay_mode_detailed))
        ),
        currentValue = uiState.fpsOverlayMode,
        onValueChange = onSetFpsOverlayMode,
        helpText = stringResource(R.string.settings_help_fps_overlay_mode),
        onResetToDefault = { onSetFpsOverlayMode(globalDefaults.fpsOverlayMode) }
    )

    LiveSelectionRow(
        title = stringResource(R.string.settings_fps_overlay_position),
        options = fpsOverlayCornerLiveOptions(),
        currentValue = uiState.fpsOverlayCorner,
        onValueChange = onSetFpsOverlayCorner,
        helpText = stringResource(R.string.settings_help_fps_overlay_position),
        onResetToDefault = { onSetFpsOverlayCorner(globalDefaults.fpsOverlayCorner) }
    )

    LiveSliderRow(
        title = stringResource(R.string.settings_fps_overlay_scale),
        valueLabelResId = R.string.settings_fps_overlay_scale_value,
        valueLabelForValue = { scale -> "$scale%" },
        value = uiState.fpsOverlayScale.toFloat(),
        range = AppPreferences.MIN_FPS_OVERLAY_SCALE.toFloat()..AppPreferences.MAX_FPS_OVERLAY_SCALE.toFloat(),
        steps = 24,
        onValueChange = { onSetFpsOverlayScale(it.toInt()) },
        helpText = stringResource(R.string.settings_help_fps_overlay_scale),
        onResetToDefault = { onSetFpsOverlayScale(globalDefaults.fpsOverlayScale) }
    )

    if (uiState.fpsOverlayMode == FPS_OVERLAY_MODE_DETAILED) {
        LiveBitmaskChoiceRow(
            title = stringResource(R.string.settings_fps_overlay_metrics),
            options = fpsOverlayMetricLiveOptions(),
            selectedMask = uiState.fpsOverlayMetrics,
            onToggle = { metric -> onSetFpsOverlayMetrics(uiState.fpsOverlayMetrics xor metric) },
            helpText = stringResource(R.string.settings_help_fps_overlay_metrics),
            onResetToDefault = { onSetFpsOverlayMetrics(globalDefaults.fpsOverlayMetrics) }
        )
    }
}

@Composable
private fun OverlaySubsectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 4.dp)
    )
}

@Composable
private fun LiveSliderRow(
    title: String,
    @androidx.annotation.StringRes valueLabelResId: Int? = null,
    valueLabelForValue: (Int) -> String,
    valueLabelForFloat: ((Float) -> String)? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)

    LaunchedEffect(value) {
        sliderValue = value
    }

    val displayValue = sliderValue.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {},
                            onLongClick = onResetToDefault?.let {
                                {
                                    it()
                                    Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                    },
                    modifier = Modifier.weight(1f, fill = false)
                )
                helpText?.let {
                    SettingHelpButton(title = title, description = it)
                }
            }
            Text(
                text = valueLabelResId?.let { stringResource(it, displayValue) }
                    ?: valueLabelForFloat?.invoke(sliderValue)
                    ?: valueLabelForValue(displayValue),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                }
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                if (enabled) {
                    onValueChange(sliderValue)
                }
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun MenuButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    gradientColors: List<Color>? = null,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    containerColor: Color? = null
) {
    val shape = neonShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = when {
            gradientColors != null -> Color.Transparent
            containerColor != null -> containerColor
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = if (gradientColors == null) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) else null
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (gradientColors != null) {
                        Modifier.background(
                            Brush.horizontalGradient(gradientColors.map { it.copy(alpha = 0.22f) }),
                            neonShape(16.dp)
                        ).border(1.dp, gradientColors.first().copy(alpha = 0.3f), neonShape(16.dp))
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    gradientColors != null -> gradientColors.first()
                    isDestructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            if (showProgress) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun QuickIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    showProgress: Boolean,
    containerColor: Color
) {
    val shape = neonShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (showProgress) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private const val FLOATING_QUICK_ACTION_SIZE_DP = 54

/** Emulated 3DS framebuffer the core expects touch coordinates in. */
private const val FRAMEBUFFER_TOUCH_WIDTH = 400f
private const val FRAMEBUFFER_TOUCH_HEIGHT = 240f

@Composable
private fun FloatingQuickActionsLayer(
    savePosition: Offset,
    loadPosition: Offset,
    saveEnabled: Boolean,
    loadEnabled: Boolean,
    saveInProgress: Boolean,
    loadInProgress: Boolean,
    onSavePositionChange: (Offset) -> Unit,
    onLoadPositionChange: (Offset) -> Unit,
    onSavePositionCommit: () -> Unit,
    onLoadPositionCommit: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val sizePx = with(density) { FLOATING_QUICK_ACTION_SIZE_DP.dp.toPx() }

        FloatingQuickActionButton(
            icon = Icons.Rounded.Save,
            contentDescription = stringResource(R.string.emulation_quick_save_desc),
            position = savePosition,
            widthPx = widthPx,
            heightPx = heightPx,
            sizePx = sizePx,
            enabled = saveEnabled,
            showProgress = saveInProgress,
            onDrag = { dragAmount ->
                onSavePositionChange(
                    clampFloatingQuickActionPosition(
                        savePosition + Offset(dragAmount.x / widthPx, dragAmount.y / heightPx)
                    )
                )
            },
            onDragEnd = onSavePositionCommit,
            onClick = onSave
        )
        FloatingQuickActionButton(
            icon = Icons.Rounded.Restore,
            contentDescription = stringResource(R.string.emulation_quick_load_desc),
            position = loadPosition,
            widthPx = widthPx,
            heightPx = heightPx,
            sizePx = sizePx,
            enabled = loadEnabled,
            showProgress = loadInProgress,
            onDrag = { dragAmount ->
                onLoadPositionChange(
                    clampFloatingQuickActionPosition(
                        loadPosition + Offset(dragAmount.x / widthPx, dragAmount.y / heightPx)
                    )
                )
            },
            onDragEnd = onLoadPositionCommit,
            onClick = onLoad
        )
    }
}

@Composable
private fun FloatingQuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    position: Offset,
    widthPx: Float,
    heightPx: Float,
    sizePx: Float,
    enabled: Boolean,
    showProgress: Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
) {
    val currentPosition by rememberUpdatedState(position)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = neonShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (currentPosition.x * widthPx - sizePx / 2f).roundToInt(),
                    y = (currentPosition.y * heightPx - sizePx / 2f).roundToInt()
                )
            }
            .size(FLOATING_QUICK_ACTION_SIZE_DP.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) {
                currentOnClick()
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount)
                    }
                )
            },
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 6.dp
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (showProgress) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun clampFloatingQuickActionPosition(position: Offset): Offset {
    return Offset(
        position.x.coerceIn(
            AppPreferences.FLOATING_QUICK_ACTION_MIN,
            AppPreferences.FLOATING_QUICK_ACTION_MAX
        ),
        position.y.coerceIn(
            AppPreferences.FLOATING_QUICK_ACTION_MIN,
            AppPreferences.FLOATING_QUICK_ACTION_MAX
        )
    )
}

@Composable
private fun CompactIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    showProgress: Boolean
) {
    val shape = neonShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(52.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .gamepadFocusableCard(
                enabled = enabled,
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            ),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (showProgress) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
