package com.sbro.emucored.core.snes

import android.view.Surface
import com.sbro.emucored.core.platform.CoreId
import com.sbro.emucored.core.platform.session.*
import java.io.File

class BsnesSessionEngine : ThreadedFrameSessionEngine() {
    override val coreId = CoreId.BSNES
    override fun nativeOpen(request: EngineLaunchRequest, directory: File): Long {
        System.loadLibrary("emucored_snes")
        return nativeCreate(request.launchPath, directory.absolutePath)
    }
    override fun nativeClose(handle: Long) = nativeDestroy(handle)
    override fun nativeRunFrame(handle: Long) = nativeFrame(handle)
    override fun nativeFrameRate(handle: Long) = nativeRate(handle)
    override fun nativeAttachSurface(handle: Long, surface: Surface?, width: Int, height: Int) = nativeSurface(handle, surface)
    override fun nativeSetPaused(handle: Long, paused: Boolean) = nativePause(handle, paused)
    override fun nativeSetButton(handle: Long, player: Int, button: EngineButton, pressed: Boolean) = nativeButton(handle, player, button.ordinal, pressed)
    override fun nativeState(handle: Long, path: File, save: Boolean) = nativeStateFile(handle, path.absolutePath, save)
    override fun nativeApplyOption(handle: Long, key: String, value: String) = nativeOption(handle, key, value)
    private external fun nativeCreate(path: String, directory: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeFrame(handle: Long): Boolean
    private external fun nativeRate(handle: Long): Double
    private external fun nativeSurface(handle: Long, surface: Surface?)
    private external fun nativePause(handle: Long, paused: Boolean)
    private external fun nativeButton(handle: Long, player: Int, button: Int, pressed: Boolean)
    private external fun nativeStateFile(handle: Long, path: String, save: Boolean): Boolean
    private external fun nativeOption(handle: Long, key: String, value: String): Boolean
}
