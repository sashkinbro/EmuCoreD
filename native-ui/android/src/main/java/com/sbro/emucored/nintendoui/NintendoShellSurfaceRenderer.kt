package com.sbro.emucored.nintendoui

import android.opengl.GLSurfaceView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class NintendoShellSurfaceRenderer : GLSurfaceView.Renderer {
    private var lastFrameNanos = 0L
    private var lastClockMinute = -1L
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        NintendoShellNative.ensureLoaded()
        NintendoShellNative.nativeOnSurfaceCreated()
        lastFrameNanos = System.nanoTime()
        lastClockMinute = -1L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        NintendoShellNative.nativeOnSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f
        else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameNanos = now

        val minute = now / 60_000_000_000L
        if (minute != lastClockMinute) {
            lastClockMinute = minute
            NintendoShellNative.nativeSetClock(clockFmt.format(Date()))
        }

        NintendoShellNative.nativeDrawFrame(dt)
    }
}
