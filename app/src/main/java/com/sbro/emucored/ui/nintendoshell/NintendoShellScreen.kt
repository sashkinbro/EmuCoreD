// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.nintendoshell

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucored.R
import com.sbro.emucored.core.UiSoundManager
import com.sbro.emucored.data.ConsoleMemorySaveRepository
import com.sbro.emucored.data.CustomGameCoverRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SaveStateEntryInfo
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.nintendoui.NintendoImageDecoder
import com.sbro.emucored.nintendoui.NintendoShellAudio
import com.sbro.emucored.nintendoui.NintendoShellHost
import com.sbro.emucored.nintendoui.NintendoShellListener
import com.sbro.emucored.nintendoui.NintendoShellNative
import com.sbro.emucored.nintendoui.NintendoShellSurfaceRenderer
import com.sbro.emucored.ui.common.gamePlatformLabelResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "nintendo_shell_prefs"
private const val KEY_THEME_ID = "theme_id"
private const val KEY_ASPECT_MODE = "aspect_mode"
private const val KEY_ECO_MODE = "eco_mode"
private const val KEY_SFX_ENABLED = "sfx_enabled"

@Composable
fun NintendoShellScreen(
    games: List<GameItem>,
    recentGames: List<GameItem>,
    isCoverArtDisabled: Boolean,
    onExitShelfMode: () -> Unit,
    onLaunchGame: (GameItem) -> Unit,
    onLaunchSaveSlot: (String, Int) -> Unit,
    modifier: Modifier = Modifier
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

    var refreshNonce by remember { mutableIntStateOf(0) }
    val quickSaveMap = remember { HashMap<String, SaveStateEntryInfo>() }

    // Ensure native bridge + image decoder context + audio state are ready immediately
    remember(context) {
        NintendoImageDecoder.setContext(context)
        NintendoShellNative.ensureLoaded()
        val sfx = prefs.getBoolean(KEY_SFX_ENABLED, true)
        NintendoShellAudio.enabled = sfx
        NintendoShellNative.nativeSetSettings(
            themeId = prefs.getInt(KEY_THEME_ID, 0),
            aspectMode = prefs.getInt(KEY_ASPECT_MODE, 0),
            ecoMode = prefs.getBoolean(KEY_ECO_MODE, false),
            sfxEnabled = sfx
        )
        true
    }

    val localizedStrings = buildLocalizedMap()
    val deletedText = stringResource(R.string.nintendoui_deleted)
    val backupSavedText = stringResource(R.string.nintendoui_backup_saved)
    val backupFailedText = stringResource(R.string.nintendoui_backup_failed)

    LaunchedEffect(localizedStrings) {
        NintendoShellNative.nativeSetStrings(
            localizedStrings.keys.toTypedArray(),
            localizedStrings.values.toTypedArray()
        )
    }

    LaunchedEffect(games, recentGames, isCoverArtDisabled) {
        val paths = Array(games.size) { games[it].path }
        val titles = Array(games.size) { games[it].title }
        val consoleIds = Array(games.size) { games[it].platform.console.id }
        val consoleShorts = Array(games.size) { shortConsoleCode(games[it].platform) }
        val consoleLabels = Array(games.size) {
            context.getString(gamePlatformLabelResource(games[it].platform))
        }
        val coverKeys = Array(games.size) { "cover:${games[it].path}" }
        val coverPaths = Array(games.size) { idx ->
            val raw = games[idx].coverArtPath
            val hidden = isCoverArtDisabled && !customCoverRepository.isCustomCoverPath(raw)
            if (hidden) "" else raw.orEmpty()
        }
        val recentPaths = recentGames.map { it.path }.toTypedArray()
        NintendoShellNative.nativeSetCatalog(
            paths = paths,
            titles = titles,
            consoleIds = consoleIds,
            consoleShorts = consoleShorts,
            consoleLabels = consoleLabels,
            coverKeys = coverKeys,
            coverPaths = coverPaths,
            recentPaths = recentPaths
        )
    }

    suspend fun reloadSaves() {
        val (quickList, memoryList) = withContext(Dispatchers.IO) {
            val rawEntries = saveStateRepository.listEntries()
            val enriched = saveStateRepository.enrichGlobalEntries(rawEntries)
            val mems = ConsoleMemorySaveRepository.listAll(context)
            enriched to mems
        }

        quickSaveMap.clear()
        quickList.forEach { quickSaveMap[it.absolutePath] = it }

        val autoSaveText = context.getString(R.string.emulation_auto_save_title)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val nq = quickList.size
        val qIds = Array(nq) { quickList[it].absolutePath }
        val qGamePaths = Array(nq) { quickList[it].gamePath.orEmpty() }
        val qTitles = Array(nq) { quickList[it].gameTitle }
        val qConsoleIds = Array(nq) { quickList[it].platform?.console?.id ?: "nintendo" }
        val qConsoleShorts = Array(nq) { shortConsoleCode(quickList[it].platform) }
        val qSlots = IntArray(nq) { quickList[it].slot }
        val qSlotLabels = Array(nq) {
            val e = quickList[it]
            if (e.isAutoSave) autoSaveText else "#${e.slot}"
        }
        val qTimeTexts = Array(nq) {
            val ts = quickList[it].lastModified
            if (ts > 0L) dateFmt.format(Date(ts)) else ""
        }
        val qSizeTexts = Array(nq) { formatByteSize(quickList[it].sizeBytes) }
        val qPreviews = Array(nq) {
            val e = quickList[it]
            saveStateRepository.getPreviewImagePath(e) ?: e.coverArtPath.orEmpty()
        }

        NintendoShellNative.nativeSetQuickSaves(
            ids = qIds,
            gamePaths = qGamePaths,
            gameTitles = qTitles,
            consoleIds = qConsoleIds,
            consoleShorts = qConsoleShorts,
            slotLabels = qSlotLabels,
            timeTexts = qTimeTexts,
            sizeTexts = qSizeTexts,
            previewPaths = qPreviews,
            slots = qSlots
        )

        val nm = memoryList.size
        NintendoShellNative.nativeSetMemorySaves(
            ids = Array(nm) { memoryList[it].id },
            filePaths = Array(nm) { memoryList[it].filePath },
            titles = Array(nm) { memoryList[it].title },
            consoleIds = Array(nm) { memoryList[it].consoleId },
            consoleShorts = Array(nm) { memoryList[it].consoleShort },
            sizeTexts = Array(nm) { memoryList[it].formattedSize() },
            timeTexts = Array(nm) { memoryList[it].formattedTime() }
        )
    }

    LaunchedEffect(refreshNonce) {
        reloadSaves()
    }

    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            isFocusable = true
            isFocusableInTouchMode = true
            setRenderer(NintendoShellSurfaceRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { view, event ->
                val action = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.requestFocus()
                        0
                    }
                    MotionEvent.ACTION_UP -> 1
                    MotionEvent.ACTION_MOVE -> 2
                    MotionEvent.ACTION_CANCEL -> 3
                    else -> return@setOnTouchListener false
                }
                NintendoShellNative.nativeOnTouch(action, event.x, event.y)
                true
            }
        }
    }

    DisposableEffect(glView) {
        NintendoShellHost.isActive = true
        UiSoundManager.setNativeShellActive(true)
        NintendoShellHost.listener = object : NintendoShellListener {
            override fun onLaunchGame(path: String) {
                val target = currentGames.firstOrNull { it.path == path } ?: return
                currentOnLaunchGame(target)
            }

            override fun onLoadSaveState(gamePath: String, stateId: String, slot: Int) {
                if (gamePath.isBlank()) return
                currentOnLaunchSaveSlot(gamePath, slot)
            }

            override fun onDeleteSaveState(stateId: String) {
                val entry = quickSaveMap[stateId] ?: return
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { saveStateRepository.deleteEntry(entry) }
                    if (ok) {
                        NintendoShellNative.nativeShowToast(deletedText)
                        refreshNonce++
                    }
                }
            }

            override fun onBackupMemorySave(filePath: String, title: String) {
                scope.launch {
                    val dest = withContext(Dispatchers.IO) {
                        ConsoleMemorySaveRepository.backup(context, filePath)
                    }
                    NintendoShellNative.nativeShowToast(
                        if (dest != null) backupSavedText else backupFailedText
                    )
                }
            }

            override fun onDeleteMemorySave(filePath: String) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        ConsoleMemorySaveRepository.delete(context, filePath)
                    }
                    if (ok) {
                        NintendoShellNative.nativeShowToast(deletedText)
                        refreshNonce++
                    }
                }
            }

            override fun onSwitchToStandardUi() {
                currentOnExit()
            }

            override fun onPersistSettings(
                themeId: Int,
                aspectMode: Int,
                ecoMode: Boolean,
                sfxEnabled: Boolean
            ) {
                prefs.edit()
                    .putInt(KEY_THEME_ID, themeId)
                    .putInt(KEY_ASPECT_MODE, aspectMode)
                    .putBoolean(KEY_ECO_MODE, ecoMode)
                    .putBoolean(KEY_SFX_ENABLED, sfxEnabled)
                    .apply()
            }
        }

        onDispose {
            NintendoShellHost.listener = null
            NintendoShellHost.isActive = false
            UiSoundManager.setNativeShellActive(false)
            NintendoImageDecoder.clearPending()
            glView.onPause()
            glView.queueEvent {
                NintendoShellNative.nativeReleaseGl()
            }
        }
    }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView.onResume()
                    glView.requestFocus()
                    refreshNonce++
                }
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = true) {
        if (!NintendoShellHost.handleBackPressed()) {
            currentOnExit()
        }
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

@Composable
private fun buildLocalizedMap(): Map<String, String> = mapOf(
    "nintendoui_tab_recent" to stringResource(R.string.nintendoui_tab_recent),
    "nintendoui_action_back" to stringResource(R.string.nintendoui_action_back),
    "nintendoui_menu_games" to stringResource(R.string.nintendoui_menu_games),
    "nintendoui_menu_saves" to stringResource(R.string.nintendoui_menu_saves),
    "nintendoui_menu_settings" to stringResource(R.string.nintendoui_menu_settings),
    "nintendoui_unit_consoles" to stringResource(R.string.nintendoui_unit_consoles),
    "nintendoui_saves_tab_quick" to stringResource(R.string.nintendoui_saves_tab_quick),
    "nintendoui_saves_tab_memory" to stringResource(R.string.nintendoui_saves_tab_memory),
    "nintendoui_settings_subtitle" to stringResource(R.string.nintendoui_settings_subtitle),
    "nintendoui_empty_games" to stringResource(R.string.nintendoui_empty_games),
    "nintendoui_empty_quicksaves" to stringResource(R.string.nintendoui_empty_quicksaves),
    "nintendoui_empty_memsaves" to stringResource(R.string.nintendoui_empty_memsaves),
    "nintendoui_action_load" to stringResource(R.string.nintendoui_action_load),
    "nintendoui_action_delete" to stringResource(R.string.nintendoui_action_delete),
    "nintendoui_action_backup" to stringResource(R.string.nintendoui_action_backup),
    "nintendoui_theme_wii" to stringResource(R.string.nintendoui_theme_wii),
    "nintendoui_theme_gc" to stringResource(R.string.nintendoui_theme_gc),
    "nintendoui_theme_famicom" to stringResource(R.string.nintendoui_theme_famicom),
    "nintendoui_theme_gb" to stringResource(R.string.nintendoui_theme_gb),
    "nintendoui_theme_switch" to stringResource(R.string.nintendoui_theme_switch),
    "nintendoui_aspect_auto" to stringResource(R.string.nintendoui_aspect_auto),
    "nintendoui_setting_theme" to stringResource(R.string.nintendoui_setting_theme),
    "nintendoui_setting_aspect" to stringResource(R.string.nintendoui_setting_aspect),
    "nintendoui_setting_eco" to stringResource(R.string.nintendoui_setting_eco),
    "nintendoui_state_on" to stringResource(R.string.nintendoui_state_on),
    "nintendoui_state_off" to stringResource(R.string.nintendoui_state_off),
    "nintendoui_setting_sfx" to stringResource(R.string.nintendoui_setting_sfx),
    "nintendoui_setting_standard_ui" to stringResource(R.string.nintendoui_setting_standard_ui),
    "nintendoui_action_open" to stringResource(R.string.nintendoui_action_open),
    "nintendoui_confirm_delete" to stringResource(R.string.nintendoui_confirm_delete)
)

private fun shortConsoleCode(platform: GamePlatform?): String = when (platform) {
    GamePlatform.N3DS -> "3DS"
    GamePlatform.NDS -> "DS"
    GamePlatform.N64 -> "N64"
    GamePlatform.GAMECUBE -> "GC"
    GamePlatform.WII -> "WII"
    GamePlatform.GBA -> "GBA"
    GamePlatform.GB -> "GB"
    GamePlatform.GBC -> "GBC"
    GamePlatform.NES -> "NES"
    GamePlatform.SNES -> "SNES"
    null -> "SAV"
}

private fun formatByteSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}
