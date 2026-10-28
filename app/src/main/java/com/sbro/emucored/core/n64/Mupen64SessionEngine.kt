// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.n64

import android.view.Surface
import com.sbro.emucored.core.platform.CoreId
import com.sbro.emucored.core.platform.session.EngineButton
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import com.sbro.emucored.core.platform.session.ThreadedFrameSessionEngine
import java.io.File

class Mupen64SessionEngine : ThreadedFrameSessionEngine() {
    override val coreId = CoreId.MUPEN64
    override val nativeSupportsAnalog = true
    override fun nativeOpen(request: EngineLaunchRequest, directory: File): Long {
        val entries = request.options.entries.toList()
        return Mupen64Native.nativeCreate(request.launchPath, directory.absolutePath,
            entries.map { it.key }.toTypedArray(), entries.map { it.value }.toTypedArray())
    }
    override fun nativeClose(handle: Long) = Mupen64Native.nativeDestroy(handle)
    override fun nativeRunFrame(handle: Long) = Mupen64Native.nativeFrame(handle)
    override fun nativeFrameRate(handle: Long) = Mupen64Native.nativeRate(handle)
    override fun nativeAttachSurface(handle: Long, surface: Surface?, width: Int, height: Int) = Mupen64Native.nativeSurface(handle, surface)
    override fun nativeSetPaused(handle: Long, paused: Boolean) = Mupen64Native.nativePause(handle, paused)
    override fun nativeSetButton(handle: Long, player: Int, button: EngineButton, pressed: Boolean) =
        Mupen64Native.nativeButton(handle, player, button.ordinal, pressed)
    override fun nativeSetAnalog(handle: Long, player: Int, x: Float, y: Float, secondary: Boolean) =
        Mupen64Native.nativeAnalog(handle, player, x, y, secondary)
    override fun nativeState(handle: Long, path: File, save: Boolean) = Mupen64Native.nativeStateFile(handle, path.absolutePath, save)
    override fun nativeApplyOption(handle: Long, key: String, value: String) = Mupen64Native.nativeOption(handle, key, value)
}
