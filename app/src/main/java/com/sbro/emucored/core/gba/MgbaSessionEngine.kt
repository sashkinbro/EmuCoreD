package com.sbro.emucored.core.gba

import android.view.Surface
import com.sbro.emucored.core.platform.CoreId
import com.sbro.emucored.core.platform.session.EngineButton
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import com.sbro.emucored.core.platform.session.ThreadedFrameSessionEngine
import java.io.File

/** All JNI calls, including open and teardown, run on the session's owned thread. */
class MgbaSessionEngine : ThreadedFrameSessionEngine() {
 override val coreId = CoreId.MGBA
 override fun nativeOpen(request: EngineLaunchRequest, directory: File): Long {
  System.loadLibrary("emucored_gba")
  return nativeCreate(request.launchPath,File(directory,"battery.sav").absolutePath,request.platform.name)
 }
 override fun nativeClose(handle: Long) = nativeDestroy(handle)
 override fun nativeRunFrame(handle: Long): Boolean = nativeFrame(handle)
 override fun nativeFrameRate(handle: Long): Double = nativeRate()
 override fun nativeAttachSurface(handle: Long, surface: Surface?, width: Int, height: Int) = nativeSurface(handle,surface)
 override fun nativeSetPaused(handle: Long, paused: Boolean) = nativePause(handle,paused)
 override fun nativeSetButton(handle: Long, player: Int, button: EngineButton, pressed: Boolean) = nativeButton(handle,player,button.ordinal,pressed)
 override fun nativeState(handle: Long, path: File, save: Boolean): Boolean = nativeStateFile(handle,path.absolutePath,save)
 override fun nativeApplyOption(handle: Long, key: String, value: String): Boolean = nativeOption(handle,key,value)
 override fun nativeReplaceCheats(handle: Long, formats: Array<String>, codes: Array<String>): Boolean = nativeCheats(handle,formats,codes)
 private external fun nativeCheats(handle: Long, formats: Array<String>, codes: Array<String>): Boolean
 private external fun nativeCreate(path: String,battery: String,platform: String): Long
 private external fun nativeDestroy(handle: Long)
 private external fun nativeFrame(handle: Long): Boolean
 private external fun nativeRate(): Double
 private external fun nativeSurface(handle: Long,surface: Surface?)
 private external fun nativePause(handle: Long,paused: Boolean)
 private external fun nativeButton(handle: Long,player: Int,button: Int,pressed: Boolean)
 private external fun nativeStateFile(handle: Long,path: String,save: Boolean): Boolean
 private external fun nativeOption(handle: Long,key: String,value: String): Boolean
}
