package com.sbro.emucored.nintendoui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

interface NintendoShellListener {
    fun onLaunchGame(path: String)
    fun onLoadSaveState(gamePath: String, stateId: String, slot: Int)
    fun onDeleteSaveState(stateId: String)
    fun onBackupMemorySave(filePath: String, title: String)
    fun onDeleteMemorySave(filePath: String)
    fun onSwitchToStandardUi()
    fun onPersistSettings(themeId: Int, aspectMode: Int, ecoMode: Boolean, sfxEnabled: Boolean)
}

object NintendoShellHost {
    private const val ACTION_LAUNCH_GAME = 1
    private const val ACTION_LOAD_SAVE_STATE = 2
    private const val ACTION_DELETE_SAVE_STATE = 3
    private const val ACTION_BACKUP_MEMORY_SAVE = 4
    private const val ACTION_DELETE_MEMORY_SAVE = 5
    private const val ACTION_SWITCH_STANDARD_UI = 6
    private const val ACTION_PERSIST_SETTINGS = 7

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isActive: Boolean = false

    @Volatile
    var listener: NintendoShellListener? = null

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isActive) return false
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        return NintendoShellNative.nativeOnKey(event.keyCode, down)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val ax = event.getAxisValue(MotionEvent.AXIS_X)
        val ay = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val finalX = if (abs(hatX) > abs(ax)) hatX else ax
        val finalY = if (abs(hatY) > abs(ay)) hatY else ay
        NintendoShellNative.nativeOnAxis(finalX, finalY)
        return abs(finalX) > 0.25f || abs(finalY) > 0.25f
    }

    fun handleBackPressed(): Boolean {
        if (!isActive) return false
        return NintendoShellNative.nativeOnBackPressed()
    }

    @JvmStatic
    fun onNativeAction(actionType: Int, arg1: String, arg2: String, intArg1: Int, intArg2: Int) {
        val cb = listener ?: return
        mainHandler.post {
            when (actionType) {
                ACTION_LAUNCH_GAME -> cb.onLaunchGame(arg1)
                ACTION_LOAD_SAVE_STATE -> cb.onLoadSaveState(arg1, arg2, intArg1)
                ACTION_DELETE_SAVE_STATE -> cb.onDeleteSaveState(arg1)
                ACTION_BACKUP_MEMORY_SAVE -> cb.onBackupMemorySave(arg1, arg2)
                ACTION_DELETE_MEMORY_SAVE -> cb.onDeleteMemorySave(arg1)
                ACTION_SWITCH_STANDARD_UI -> cb.onSwitchToStandardUi()
                ACTION_PERSIST_SETTINGS -> {
                    val aspect = intArg2 and 0xF
                    val eco = (intArg2 and 0x10) != 0
                    val sfx = (intArg2 and 0x20) != 0
                    NintendoShellAudio.enabled = sfx
                    cb.onPersistSettings(intArg1, aspect, eco, sfx)
                }
            }
        }
    }
}
