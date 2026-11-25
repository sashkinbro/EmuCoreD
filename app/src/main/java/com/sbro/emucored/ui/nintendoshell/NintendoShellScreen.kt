// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.nintendoshell

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.annotation.ColorInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucored.BuildConfig
import com.sbro.emucored.R
import com.sbro.emucored.core.UiSoundManager
import com.sbro.emucored.data.ConsoleMemorySaveItem
import com.sbro.emucored.data.ConsoleMemorySaveRepository
import com.sbro.emucored.data.CustomGameCoverRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SaveStateEntryInfo
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.nintendoui.NintendoImageDecoder
import com.sbro.emucored.nintendoui.NintendoShellAudio
import com.sbro.emucored.nintendoui.NintendoShellHost
import com.sbro.emucored.nintendoui.NintendoShellNative
import com.sbro.emucored.nintendoui.NintendoShellSurfaceRenderer
import com.sbro.emucored.ui.common.gamePlatformLabelResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val PREFS_NAME = "nintendo_shell_prefs"
private const val KEY_THEME = "theme"
private const val KEY_ASPECT = "aspect_mode"
private const val KEY_MOTION = "motion_mode"
private const val KEY_SCANLINES = "scanlines_mode"
private const val KEY_BOOT_ANIM = "boot_anim_mode"
private const val KEY_KEY_TONE = "key_tone"
private const val KEY_AMBIENT = "ambient_sound"
private const val KEY_SWAP_CONFIRM = "swap_confirm"
private const val KEY_TOUCH_CONTROLS = "touch_controls"
private const val KEY_TIME_24H = "time_24h"
private const val KEY_DATE_FMT = "date_format"
private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

private data class ShellPrefsState(
    val theme: Int,
    val aspectMode: Int,
    val motionMode: Int,
    val scanlinesMode: Int,
    val bootAnimMode: Int,
    val keyTone: Boolean,
    val ambientSound: Boolean,
    val swapConfirm: Boolean,
    val touchControls: Boolean,
    val time24h: Boolean,
    val dateFormat: Int,
    val keepScreenOn: Boolean,
)

private object NintendoShellSessionMemory {
    var hasBootedOnce: Boolean = false
    var savedState: IntArray? = null
}

@Composable
fun NintendoShellScreen(
    games: List<GameItem>,
    recentGames: List<GameItem>,
    isCoverArtDisabled: Boolean,
    onExitShelfMode: () -> Unit,
    onLaunchGame: (GameItem) -> Unit,
    onLaunchSaveSlot: (String, Int) -> Unit,
    onOpenGameSettings: (GameItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val saveStateRepository = remember(context) { SaveStateRepository(context) }
    val customCoverRepository = remember(context) { CustomGameCoverRepository(context) }
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val currentGames by rememberUpdatedState(games)
    val currentOnExit by rememberUpdatedState(onExitShelfMode)
    val currentOnLaunchGame by rememberUpdatedState(onLaunchGame)
    val currentOnLaunchSaveSlot by rememberUpdatedState(onLaunchSaveSlot)
    val currentOnOpenGameSettings by rememberUpdatedState(onOpenGameSettings)

    var refreshNonce by remember { mutableIntStateOf(0) }
    var glReadyNonce by remember { mutableIntStateOf(0) }
    val loadedQuickSaves = remember { ArrayList<SaveStateEntryInfo>() }
    val loadedMemorySaves = remember { ArrayList<ConsoleMemorySaveItem>() }

    fun loadPrefsState(): ShellPrefsState = ShellPrefsState(
        theme = prefs.getInt(KEY_THEME, 0).coerceIn(0, 4),
        aspectMode = prefs.getInt(KEY_ASPECT, 0).coerceIn(0, 3),
        motionMode = prefs.getInt(KEY_MOTION, 0).coerceIn(0, 3),
        scanlinesMode = prefs.getInt(KEY_SCANLINES, 0).coerceIn(0, 2),
        bootAnimMode = prefs.getInt(KEY_BOOT_ANIM, 0).coerceIn(0, 2),
        keyTone = prefs.getBoolean(KEY_KEY_TONE, true),
        ambientSound = prefs.getBoolean(KEY_AMBIENT, false),
        swapConfirm = prefs.getBoolean(KEY_SWAP_CONFIRM, false),
        touchControls = prefs.getBoolean(KEY_TOUCH_CONTROLS, true),
        time24h = prefs.getBoolean(KEY_TIME_24H, true),
        dateFormat = prefs.getInt(KEY_DATE_FMT, 0).coerceIn(0, 2),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
    )

    fun savePrefsPacked(v: IntArray): ShellPrefsState {
        val next = ShellPrefsState(
            theme = v.getOrElse(0) { 0 }.coerceIn(0, 4),
            aspectMode = v.getOrElse(1) { 0 }.coerceIn(0, 3),
            motionMode = v.getOrElse(2) { 0 }.coerceIn(0, 3),
            scanlinesMode = v.getOrElse(3) { 0 }.coerceIn(0, 2),
            bootAnimMode = v.getOrElse(4) { 0 }.coerceIn(0, 2),
            keyTone = v.getOrElse(5) { 1 } != 0,
            ambientSound = v.getOrElse(6) { 0 } != 0,
            swapConfirm = v.getOrElse(7) { 0 } != 0,
            touchControls = v.getOrElse(8) { 1 } != 0,
            time24h = v.getOrElse(9) { 1 } != 0,
            dateFormat = v.getOrElse(10) { 0 }.coerceIn(0, 2),
            keepScreenOn = v.getOrElse(11) { 1 } != 0,
        )
        prefs.edit()
            .putInt(KEY_THEME, next.theme)
            .putInt(KEY_ASPECT, next.aspectMode)
            .putInt(KEY_MOTION, next.motionMode)
            .putInt(KEY_SCANLINES, next.scanlinesMode)
            .putInt(KEY_BOOT_ANIM, next.bootAnimMode)
            .putBoolean(KEY_KEY_TONE, next.keyTone)
            .putBoolean(KEY_AMBIENT, next.ambientSound)
            .putBoolean(KEY_SWAP_CONFIRM, next.swapConfirm)
            .putBoolean(KEY_TOUCH_CONTROLS, next.touchControls)
            .putBoolean(KEY_TIME_24H, next.time24h)
            .putInt(KEY_DATE_FMT, next.dateFormat)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .apply()
        return next
    }

    remember(context) {
        NintendoImageDecoder.setContext(context)
        NintendoShellNative.ensureLoaded()
        val initial = loadPrefsState()
        NintendoShellAudio.keyToneEnabled = initial.keyTone
        NintendoShellAudio.ambientEnabled = initial.ambientSound
        NintendoShellHost.swapConfirmButton = initial.swapConfirm
        true
    }

    val glViewHolder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val surfaceRenderer = remember(context) {
        NintendoShellSurfaceRenderer(
            context = context,
            onRenderModeNeeded = { continuous ->
                glViewHolder[0]?.let { view ->
                    view.post {
                        view.renderMode = if (continuous) {
                            GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        } else {
                            GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    }
                }
            },
            onReady = {
                glViewHolder[0]?.post { glReadyNonce++ }
            }
        ).apply {
            motionMode = loadPrefsState().motionMode
        }
    }

    fun applyRuntimeSettings(s: ShellPrefsState) {
        NintendoShellAudio.keyToneEnabled = s.keyTone
        NintendoShellAudio.ambientEnabled = s.ambientSound
        NintendoShellHost.swapConfirmButton = s.swapConfirm
        surfaceRenderer.motionMode = s.motionMode
        glViewHolder[0]?.let { view ->
            view.keepScreenOn = s.keepScreenOn
            if (s.motionMode != 3) {
                view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            view.requestRender()
        }
    }

    fun queueNative(block: () -> Unit) {
        val view = glViewHolder[0]
        if (view != null) {
            view.queueEvent {
                block()
            }
            view.requestRender()
        } else {
            block()
        }
    }

    val localeKey = remember(context) { context.resources.configuration.locales.toLanguageTags() }
    val localizedStrings = remember(localeKey) { buildLocalizedMap(context) }

    val glView = remember {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val mainHandler = Handler(Looper.getMainLooper())
        var activeTouchDown = false
        var downX = 0f
        var downY = 0f
        var twoFingerCandidate = false
        var multiTouchSeen = false
        var multiTouchDownTimeMs = 0L
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            val view = glViewHolder[0] ?: return@Runnable
            if (activeTouchDown && !multiTouchSeen) {
                longPressTriggered = true
                val nx = (downX / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val ny = (downY / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                view.queueEvent { NintendoShellNative.nativeTouchLongPress(nx, ny) }
                view.requestRender()
            }
        }

        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = loadPrefsState().keepScreenOn
            setRenderer(surfaceRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                (view as? GLSurfaceView)?.queueEvent {
                    NintendoShellNative.nativeSetSafeInsets(
                        bars.left.toFloat(),
                        bars.top.toFloat(),
                        bars.right.toFloat(),
                        bars.bottom.toFloat()
                    )
                }
                (view as? GLSurfaceView)?.requestRender()
                insets
            }

            setOnTouchListener { view, event ->
                val w = view.width.coerceAtLeast(1).toFloat()
                val h = view.height.coerceAtLeast(1).toFloat()
                val glv = view as GLSurfaceView

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.requestFocus()
                        activeTouchDown = true
                        multiTouchSeen = false
                        twoFingerCandidate = false
                        longPressTriggered = false
                        downX = event.x
                        downY = event.y
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.postDelayed(longPressRunnable, 480L)
                        val nx = (event.x / w).coerceIn(0f, 1f)
                        val ny = (event.y / h).coerceIn(0f, 1f)
                        glv.queueEvent { NintendoShellNative.nativeTouchDown(nx, ny) }
                        glv.requestRender()
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        multiTouchSeen = true
                        if (event.pointerCount == 2) {
                            twoFingerCandidate = true
                            multiTouchDownTimeMs = event.eventTime
                        } else {
                            twoFingerCandidate = false
                        }
                        glv.queueEvent { NintendoShellNative.nativeTouchCancel() }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!multiTouchSeen && activeTouchDown && !longPressTriggered) {
                            if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                                mainHandler.removeCallbacks(longPressRunnable)
                            }
                            val nx = (event.x / w).coerceIn(0f, 1f)
                            val ny = (event.y / h).coerceIn(0f, 1f)
                            glv.queueEvent { NintendoShellNative.nativeTouchMove(nx, ny) }
                            glv.requestRender()
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (twoFingerCandidate && event.pointerCount == 2 &&
                            (event.eventTime - multiTouchDownTimeMs) < 380L
                        ) {
                            glv.queueEvent { NintendoShellNative.nativeTouchTwoFingerBack() }
                            glv.requestRender()
                            twoFingerCandidate = false
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (activeTouchDown && !multiTouchSeen && !longPressTriggered) {
                            val nx = (event.x / w).coerceIn(0f, 1f)
                            val ny = (event.y / h).coerceIn(0f, 1f)
                            glv.queueEvent { NintendoShellNative.nativeTouchUp(nx, ny) }
                            glv.requestRender()
                        }
                        activeTouchDown = false
                        multiTouchSeen = false
                        twoFingerCandidate = false
                        longPressTriggered = false
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        activeTouchDown = false
                        multiTouchSeen = false
                        twoFingerCandidate = false
                        longPressTriggered = false
                        glv.queueEvent { NintendoShellNative.nativeTouchCancel() }
                        glv.requestRender()
                    }
                }
                true
            }
        }.also { glViewHolder[0] = it }
    }

    fun pushGamesAndSavesSnapshot() {
        val quickList = loadedQuickSaves.toList()
        val memList = loadedMemorySaves.toList()
        val currentGameList = currentGames

        val recentRankByPath = HashMap<String, Int>(recentGames.size)
        recentGames.forEachIndexed { idx, g -> recentRankByPath[g.path] = idx }

        val saveStatsByGamePath = HashMap<String, Pair<Int, Int>>()
        for (sv in quickList) {
            val gp = sv.gamePath ?: continue
            val prev = saveStatsByGamePath[gp]
            if (prev == null) {
                saveStatsByGamePath[gp] = 1 to sv.slot
            } else {
                saveStatsByGamePath[gp] = (prev.first + 1) to prev.second
            }
        }

        val tabs = orderedConsoleTabs(context)
        val tabIds = Array(tabs.size) { tabs[it].first }
        val tabLabels = Array(tabs.size) { tabs[it].second }
        val tabColors = IntArray(tabs.size) { tabs[it].third }

        val ng = currentGameList.size
        val gTitles = Array(ng) { currentGameList[it].title }
        val gPaths = Array(ng) { currentGameList[it].path }
        val gSerials = Array(ng) { currentGameList[it].serial.orEmpty() }
        val gCovers = Array(ng) { idx ->
            val raw = currentGameList[idx].coverArtPath
            val hidden = isCoverArtDisabled && !customCoverRepository.isCustomCoverPath(raw)
            if (hidden) "" else raw.orEmpty()
        }
        val gSizes = Array(ng) { formatByteSize(currentGameList[it].fileSize) }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val gDates = Array(ng) {
            val ts = currentGameList[it].lastModified
            if (ts > 0L) dateFmt.format(Date(ts)) else ""
        }
        val gPlatIds = Array(ng) { currentGameList[it].platform.console.id }
        val gPlatLabels = Array(ng) {
            context.getString(gamePlatformLabelResource(currentGameList[it].platform))
        }
        val gSaveCounts = IntArray(ng) { saveStatsByGamePath[currentGameList[it].path]?.first ?: 0 }
        val gLatestSlots = IntArray(ng) { saveStatsByGamePath[currentGameList[it].path]?.second ?: -1 }
        val gRecentRanks = IntArray(ng) { recentRankByPath[currentGameList[it].path] ?: -1 }

        val autoSaveText = context.getString(R.string.emulation_auto_save_title)
        val saveDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val nq = quickList.size
        val sIds = Array(nq) { quickList[it].absolutePath }
        val sTitles = Array(nq) { quickList[it].gameTitle }
        val sSlotLabels = Array(nq) {
            val e = quickList[it]
            if (e.isAutoSave) autoSaveText else "#${e.slot}"
        }
        val sSlotNums = IntArray(nq) { quickList[it].slot }
        val sDates = Array(nq) {
            val ts = quickList[it].lastModified
            if (ts > 0L) saveDateFmt.format(Date(ts)) else ""
        }
        val sSizes = Array(nq) { formatByteSize(quickList[it].sizeBytes) }
        val sSerials = Array(nq) { quickList[it].serial }
        val sPlatIds = Array(nq) { quickList[it].platform?.console?.id ?: "nintendo" }
        val sPlatLabels = Array(nq) {
            val p = quickList[it].platform
            if (p != null) context.getString(gamePlatformLabelResource(p)) else "Nintendo"
        }
        val sPreviews = Array(nq) {
            val e = quickList[it]
            saveStateRepository.getPreviewImagePath(e) ?: e.coverArtPath.orEmpty()
        }
        val sSavePaths = Array(nq) { quickList[it].absolutePath }
        val sGamePaths = Array(nq) { quickList[it].gamePath.orEmpty() }

        val nm = memList.size
        val mIds = Array(nm) { memList[it].id }
        val mPaths = Array(nm) { memList[it].filePath }
        val mTitles = Array(nm) { memList[it].title }
        val mCids = Array(nm) { memList[it].consoleId }
        val mCshorts = Array(nm) { memList[it].consoleShort }
        val mSizes = Array(nm) { memList[it].formattedSize() }
        val mDates = Array(nm) { memList[it].formattedTime() }
        val mSizeBytes = LongArray(nm) { memList[it].sizeBytes }

        queueNative {
            NintendoShellNative.nativeSetConsoleTabs(tabIds, tabLabels, tabColors)
            NintendoShellNative.nativeSetGames(
                titles = gTitles,
                paths = gPaths,
                serials = gSerials,
                covers = gCovers,
                sizes = gSizes,
                dates = gDates,
                platformIds = gPlatIds,
                platformLabels = gPlatLabels,
                saveCounts = gSaveCounts,
                latestSaveSlots = gLatestSlots,
                recentRanks = gRecentRanks
            )
            NintendoShellNative.nativeSetSaves(
                ids = sIds,
                titles = sTitles,
                slotLabels = sSlotLabels,
                slotNums = sSlotNums,
                dates = sDates,
                sizes = sSizes,
                serials = sSerials,
                platformIds = sPlatIds,
                platformLabels = sPlatLabels,
                previews = sPreviews,
                savePaths = sSavePaths,
                gamePaths = sGamePaths
            )
            NintendoShellNative.nativeSetMemorySaves(
                ids = mIds,
                filePaths = mPaths,
                titles = mTitles,
                consoleIds = mCids,
                consoleShorts = mCshorts,
                sizes = mSizes,
                dates = mDates,
                sizeBytes = mSizeBytes
            )
            NintendoShellNative.nativeSetLoading(gamesLoading = false, savesLoading = false)
        }
    }

    suspend fun reloadSaves() {
        queueNative {
            NintendoShellNative.nativeSetLoading(gamesLoading = false, savesLoading = true)
        }
        val (quickList, memList) = withContext(Dispatchers.IO) {
            val rawEntries = saveStateRepository.listEntries()
            val enriched = saveStateRepository.enrichGlobalEntries(rawEntries)
            val mems = ConsoleMemorySaveRepository.listAll(context)
            enriched to mems
        }
        loadedQuickSaves.clear()
        loadedQuickSaves.addAll(quickList)
        loadedMemorySaves.clear()
        loadedMemorySaves.addAll(memList)
        pushGamesAndSavesSnapshot()
    }

    LaunchedEffect(glReadyNonce, localizedStrings) {
        val s = loadPrefsState()
        applyRuntimeSettings(s)
        queueNative {
            NintendoShellNative.nativeSetStrings(
                localizedStrings.keys.toTypedArray(),
                localizedStrings.values.toTypedArray()
            )
            NintendoShellNative.nativeSetSettings(
                theme = s.theme,
                aspectMode = s.aspectMode,
                motionMode = s.motionMode,
                scanlinesMode = s.scanlinesMode,
                bootAnimMode = s.bootAnimMode,
                keyTone = s.keyTone,
                ambientSound = s.ambientSound,
                swapConfirm = s.swapConfirm,
                touchControls = s.touchControls,
                time24h = s.time24h,
                dateFormat = s.dateFormat,
                keepScreenOn = s.keepScreenOn
            )
            val saved = NintendoShellSessionMemory.savedState
            if (saved != null && saved.size >= 7) {
                NintendoShellNative.nativeSetState(
                    saved[0], saved[1], saved[2], saved[3], saved[4], saved[5], saved[6]
                )
            }
            if (!NintendoShellSessionMemory.hasBootedOnce) {
                NintendoShellSessionMemory.hasBootedOnce = true
                NintendoShellNative.nativeTriggerTransition(if (s.bootAnimMode == 2) 1 else 0)
            } else {
                NintendoShellNative.nativeTriggerTransition(1)
            }
        }
    }

    LaunchedEffect(glReadyNonce, games, recentGames, isCoverArtDisabled) {
        pushGamesAndSavesSnapshot()
    }

    LaunchedEffect(glReadyNonce, refreshNonce) {
        reloadSaves()
    }

    // Periodic live system status feed (Battery, WiFi, Bluetooth, Mute, Free Storage, Consoles)
    LaunchedEffect(glReadyNonce) {
        while (isActive) {
            val st = withContext(Dispatchers.IO) { readSystemStatus(context) }
            queueNative {
                NintendoShellNative.nativeSetSystemStatus(
                    batteryLevel = st.batteryLevel,
                    batteryCharging = st.batteryCharging,
                    wifiConnected = st.wifiConnected,
                    bluetoothConnected = st.bluetoothConnected,
                    audioMuted = st.audioMuted,
                    freeStorage = st.freeStorage,
                    deviceName = st.deviceName,
                    appVersion = st.appVersion,
                    systemsSummary = st.systemsSummary,
                    coreCount = st.coreCount
                )
            }
            delay(15_000L)
        }
    }

    DisposableEffect(glView) {
        UiSoundManager.setNativeShellActive(true)
        NintendoShellAudio.resumeAudio()

        NintendoShellHost.setGameLaunchListener { path ->
            NintendoShellSessionMemory.savedState = NintendoShellNative.nativeGetState()
            val target = currentGames.firstOrNull { it.path == path } ?: return@setGameLaunchListener
            currentOnLaunchGame(target)
        }
        NintendoShellHost.setSaveLaunchListener { gamePath, slot ->
            NintendoShellSessionMemory.savedState = NintendoShellNative.nativeGetState()
            if (gamePath.isNotBlank()) currentOnLaunchSaveSlot(gamePath, slot)
        }
        NintendoShellHost.setDeleteSaveListener { index ->
            val entry = loadedQuickSaves.getOrNull(index) ?: return@setDeleteSaveListener
            scope.launch {
                val ok = withContext(Dispatchers.IO) { saveStateRepository.deleteEntry(entry) }
                if (ok) {
                    queueNative {
                        NintendoShellNative.nativeShowToast(context.getString(R.string.nintendoui_deleted))
                    }
                    refreshNonce++
                }
            }
        }
        NintendoShellHost.setMemorySaveActionListener { action, filePath, _ ->
            when (action) {
                NintendoShellHost.MEM_ACTION_BACKUP -> scope.launch {
                    val dest = withContext(Dispatchers.IO) {
                        ConsoleMemorySaveRepository.backup(context, filePath)
                    }
                    val msg = context.getString(
                        if (dest != null) R.string.nintendoui_backup_saved else R.string.nintendoui_backup_failed
                    )
                    queueNative { NintendoShellNative.nativeShowToast(msg) }
                }
                NintendoShellHost.MEM_ACTION_DELETE -> scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        ConsoleMemorySaveRepository.delete(context, filePath)
                    }
                    if (ok) {
                        queueNative {
                            NintendoShellNative.nativeShowToast(context.getString(R.string.nintendoui_deleted))
                        }
                        refreshNonce++
                    }
                }
                NintendoShellHost.MEM_ACTION_REFRESH -> {
                    refreshNonce++
                }
            }
        }
        NintendoShellHost.setOpenGameSettingsListener { gamePath ->
            NintendoShellSessionMemory.savedState = NintendoShellNative.nativeGetState()
            val target = currentGames.firstOrNull { it.path == gamePath } ?: return@setOpenGameSettingsListener
            currentOnOpenGameSettings(target)
        }
        NintendoShellHost.setSettingsListener { packed ->
            val updated = savePrefsPacked(packed)
            applyRuntimeSettings(updated)
        }
        NintendoShellHost.setExitListener {
            NintendoShellSessionMemory.savedState = null
            NintendoShellSessionMemory.hasBootedOnce = false
            currentOnExit()
        }
        NintendoShellHost.setKeyListener { keyCode ->
            queueNative { NintendoShellNative.nativeKey(keyCode) }
        }
        NintendoShellHost.setRequestRenderListener {
            glViewHolder[0]?.requestRender()
        }

        onDispose {
            NintendoShellSessionMemory.savedState = runCatching { NintendoShellNative.nativeGetState() }.getOrNull()
            NintendoShellHost.setGameLaunchListener(null)
            NintendoShellHost.setSaveLaunchListener(null)
            NintendoShellHost.setDeleteSaveListener(null)
            NintendoShellHost.setMemorySaveActionListener(null)
            NintendoShellHost.setOpenGameSettingsListener(null)
            NintendoShellHost.setSettingsListener(null)
            NintendoShellHost.setExitListener(null)
            NintendoShellHost.setKeyListener(null)
            NintendoShellHost.setRequestRenderListener(null)
            NintendoShellAudio.pauseAudio()
            UiSoundManager.setNativeShellActive(false)
            NintendoImageDecoder.clearPending()
            glView.onPause()
            glView.queueEvent {
                NintendoShellNative.nativeShutdown()
            }
        }
    }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView.onResume()
                    glView.requestFocus()
                    NintendoShellAudio.resumeAudio()
                    refreshNonce++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    NintendoShellAudio.pauseAudio()
                    glView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = true) {
        queueNative { NintendoShellNative.nativeKey(NintendoShellHost.KEY_BACK) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { glView },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private data class AndroidSystemSnapshot(
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val wifiConnected: Boolean,
    val bluetoothConnected: Boolean,
    val audioMuted: Boolean,
    val freeStorage: String,
    val deviceName: String,
    val appVersion: String,
    val systemsSummary: String,
    val coreCount: Int,
)

private fun readSystemStatus(context: Context): AndroidSystemSnapshot {
    val batIntent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
    val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val caps = runCatching { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val btAudio = runCatching {
        audioMgr?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true
    }.getOrDefault(false)
    val btConnected = btAudio || (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true)
    val muted = runCatching {
        (audioMgr?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1) <= 0
    }.getOrDefault(false)

    val freeBytes = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)
    val freeStr = if (freeBytes >= 1024L * 1024L * 1024L) {
        String.format(Locale.US, "%.1f GB", freeBytes / (1024.0 * 1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.0f MB", freeBytes / (1024.0 * 1024.0))
    }

    val devName = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifBlank { "Android Device" }

    return AndroidSystemSnapshot(
        batteryLevel = pct,
        batteryCharging = charging,
        wifiConnected = wifi,
        bluetoothConnected = btConnected,
        audioMuted = muted,
        freeStorage = freeStr,
        deviceName = devName,
        appVersion = BuildConfig.VERSION_NAME,
        systemsSummary = "3DS · DS · Wii · GC · N64 · SNES · NES · GBA · GBC · GB",
        coreCount = 8
    )
}

@ColorInt
private fun accentArgb(platform: GamePlatform): Int = when (platform) {
    GamePlatform.N3DS -> 0xFFD6242E.toInt()
    GamePlatform.NDS -> 0xFF427AC7.toInt()
    GamePlatform.WII -> 0xFF009ED6.toInt()
    GamePlatform.GAMECUBE -> 0xFF6B57DB.toInt()
    GamePlatform.N64 -> 0xFF249952.toInt()
    GamePlatform.SNES -> 0xFF7A59C7.toInt()
    GamePlatform.NES -> 0xFFC72E38.toInt()
    GamePlatform.GBA -> 0xFF5247B8.toInt()
    GamePlatform.GBC -> 0xFFE07A1A.toInt()
    GamePlatform.GB -> 0xFF427A38.toInt()
}

private fun orderedConsoleTabs(context: Context): List<Triple<String, String, Int>> = listOf(
    GamePlatform.N3DS,
    GamePlatform.NDS,
    GamePlatform.WII,
    GamePlatform.GAMECUBE,
    GamePlatform.N64,
    GamePlatform.SNES,
    GamePlatform.NES,
    GamePlatform.GBA,
    GamePlatform.GBC,
    GamePlatform.GB,
).map { p ->
    Triple(p.console.id, context.getString(gamePlatformLabelResource(p)), accentArgb(p))
}

private fun buildLocalizedMap(context: Context): Map<String, String> = mapOf(
    "nintendoui_action_back" to context.getString(R.string.nintendoui_action_back),
    "nintendoui_action_backup" to context.getString(R.string.nintendoui_action_backup),
    "nintendoui_action_delete" to context.getString(R.string.nintendoui_action_delete),
    "nintendoui_action_load" to context.getString(R.string.nintendoui_action_load),
    "nintendoui_action_open" to context.getString(R.string.nintendoui_action_open),
    "nintendoui_action_refresh" to context.getString(R.string.nintendoui_action_refresh),
    "nintendoui_action_reset" to context.getString(R.string.nintendoui_action_reset),
    "nintendoui_action_view" to context.getString(R.string.nintendoui_action_view),
    "nintendoui_aspect_auto" to context.getString(R.string.nintendoui_aspect_auto),
    "nintendoui_boot_fast" to context.getString(R.string.nintendoui_boot_fast),
    "nintendoui_boot_full" to context.getString(R.string.nintendoui_boot_full),
    "nintendoui_btn_continue" to context.getString(R.string.nintendoui_btn_continue),
    "nintendoui_btn_options" to context.getString(R.string.nintendoui_btn_options),
    "nintendoui_btn_start" to context.getString(R.string.nintendoui_btn_start),
    "nintendoui_close" to context.getString(R.string.nintendoui_close),
    "nintendoui_del_mem_msg" to context.getString(R.string.nintendoui_del_mem_msg),
    "nintendoui_del_mem_title" to context.getString(R.string.nintendoui_del_mem_title),
    "nintendoui_del_save_msg" to context.getString(R.string.nintendoui_del_save_msg),
    "nintendoui_del_save_title" to context.getString(R.string.nintendoui_del_save_title),
    "nintendoui_empty_games" to context.getString(R.string.nintendoui_empty_games),
    "nintendoui_empty_memsaves" to context.getString(R.string.nintendoui_empty_memsaves),
    "nintendoui_empty_quicksaves" to context.getString(R.string.nintendoui_empty_quicksaves),
    "nintendoui_exit_msg" to context.getString(R.string.nintendoui_exit_msg),
    "nintendoui_exit_title" to context.getString(R.string.nintendoui_exit_title),
    "nintendoui_filter_all" to context.getString(R.string.nintendoui_filter_all),
    "nintendoui_got_it" to context.getString(R.string.nintendoui_got_it),
    "nintendoui_hint_change" to context.getString(R.string.nintendoui_hint_change),
    "nintendoui_hint_exit" to context.getString(R.string.nintendoui_hint_exit),
    "nintendoui_hint_select" to context.getString(R.string.nintendoui_hint_select),
    "nintendoui_hint_tab" to context.getString(R.string.nintendoui_hint_tab),
    "nintendoui_hub_systems_title" to context.getString(R.string.nintendoui_hub_systems_title),
    "nintendoui_info_console" to context.getString(R.string.nintendoui_info_console),
    "nintendoui_info_serial" to context.getString(R.string.nintendoui_info_serial),
    "nintendoui_info_size" to context.getString(R.string.nintendoui_info_size),
    "nintendoui_info_title" to context.getString(R.string.nintendoui_info_title),
    "nintendoui_launching_game" to context.getString(R.string.nintendoui_launching_game),
    "nintendoui_launching_save" to context.getString(R.string.nintendoui_launching_save),
    "nintendoui_loading_games" to context.getString(R.string.nintendoui_loading_games),
    "nintendoui_loading_saves" to context.getString(R.string.nintendoui_loading_saves),
    "nintendoui_menu_games" to context.getString(R.string.nintendoui_menu_games),
    "nintendoui_menu_saves" to context.getString(R.string.nintendoui_menu_saves),
    "nintendoui_menu_settings" to context.getString(R.string.nintendoui_menu_settings),
    "nintendoui_motion_calm" to context.getString(R.string.nintendoui_motion_calm),
    "nintendoui_motion_eco" to context.getString(R.string.nintendoui_motion_eco),
    "nintendoui_motion_smooth" to context.getString(R.string.nintendoui_motion_smooth),
    "nintendoui_motion_static" to context.getString(R.string.nintendoui_motion_static),
    "nintendoui_no" to context.getString(R.string.nintendoui_no),
    "nintendoui_opt_continue" to context.getString(R.string.nintendoui_opt_continue),
    "nintendoui_opt_info" to context.getString(R.string.nintendoui_opt_info),
    "nintendoui_opt_saves" to context.getString(R.string.nintendoui_opt_saves),
    "nintendoui_opt_settings" to context.getString(R.string.nintendoui_opt_settings),
    "nintendoui_opt_start" to context.getString(R.string.nintendoui_opt_start),
    "nintendoui_restore_msg" to context.getString(R.string.nintendoui_restore_msg),
    "nintendoui_restore_title" to context.getString(R.string.nintendoui_restore_title),
    "nintendoui_saves_tab_game" to context.getString(R.string.nintendoui_saves_tab_game),
    "nintendoui_saves_tab_memory" to context.getString(R.string.nintendoui_saves_tab_memory),
    "nintendoui_saves_tab_quick" to context.getString(R.string.nintendoui_saves_tab_quick),
    "nintendoui_scan_crt" to context.getString(R.string.nintendoui_scan_crt),
    "nintendoui_scan_lcd" to context.getString(R.string.nintendoui_scan_lcd),
    "nintendoui_setting_ambient" to context.getString(R.string.nintendoui_setting_ambient),
    "nintendoui_setting_aspect" to context.getString(R.string.nintendoui_setting_aspect),
    "nintendoui_setting_boot" to context.getString(R.string.nintendoui_setting_boot),
    "nintendoui_setting_confirm_btn" to context.getString(R.string.nintendoui_setting_confirm_btn),
    "nintendoui_setting_date_fmt" to context.getString(R.string.nintendoui_setting_date_fmt),
    "nintendoui_setting_filter" to context.getString(R.string.nintendoui_setting_filter),
    "nintendoui_setting_keep_screen" to context.getString(R.string.nintendoui_setting_keep_screen),
    "nintendoui_setting_motion" to context.getString(R.string.nintendoui_setting_motion),
    "nintendoui_setting_restore" to context.getString(R.string.nintendoui_setting_restore),
    "nintendoui_setting_sfx" to context.getString(R.string.nintendoui_setting_sfx),
    "nintendoui_setting_sysinfo" to context.getString(R.string.nintendoui_setting_sysinfo),
    "nintendoui_setting_theme" to context.getString(R.string.nintendoui_setting_theme),
    "nintendoui_setting_time_fmt" to context.getString(R.string.nintendoui_setting_time_fmt),
    "nintendoui_setting_touch" to context.getString(R.string.nintendoui_setting_touch),
    "nintendoui_setting_touch_guide" to context.getString(R.string.nintendoui_setting_touch_guide),
    "nintendoui_settings_subtitle" to context.getString(R.string.nintendoui_settings_subtitle),
    "nintendoui_slot_empty" to context.getString(R.string.nintendoui_slot_empty),
    "nintendoui_state_off" to context.getString(R.string.nintendoui_state_off),
    "nintendoui_state_on" to context.getString(R.string.nintendoui_state_on),
    "nintendoui_sys_app" to context.getString(R.string.nintendoui_sys_app),
    "nintendoui_sys_device" to context.getString(R.string.nintendoui_sys_device),
    "nintendoui_sys_storage" to context.getString(R.string.nintendoui_sys_storage),
    "nintendoui_tab_recent" to context.getString(R.string.nintendoui_tab_recent),
    "nintendoui_theme_famicom" to context.getString(R.string.nintendoui_theme_famicom),
    "nintendoui_theme_gb" to context.getString(R.string.nintendoui_theme_gb),
    "nintendoui_theme_gc" to context.getString(R.string.nintendoui_theme_gc),
    "nintendoui_theme_switch" to context.getString(R.string.nintendoui_theme_switch),
    "nintendoui_theme_wii" to context.getString(R.string.nintendoui_theme_wii),
    "nintendoui_time_12h" to context.getString(R.string.nintendoui_time_12h),
    "nintendoui_time_24h" to context.getString(R.string.nintendoui_time_24h),
    "nintendoui_touch_guide_title" to context.getString(R.string.nintendoui_touch_guide_title),
    "nintendoui_touch_tip_1" to context.getString(R.string.nintendoui_touch_tip_1),
    "nintendoui_touch_tip_2" to context.getString(R.string.nintendoui_touch_tip_2),
    "nintendoui_touch_tip_3" to context.getString(R.string.nintendoui_touch_tip_3),
    "nintendoui_touch_tip_4" to context.getString(R.string.nintendoui_touch_tip_4),
    "nintendoui_unit_consoles" to context.getString(R.string.nintendoui_unit_consoles),
    "nintendoui_yes" to context.getString(R.string.nintendoui_yes),
)

private fun formatByteSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}
