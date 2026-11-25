package com.sbro.emucored.nintendoui

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView renderer driving the native Nintendo console UI scene, with adaptive
 * 30 Hz power-saver pacing and idle dirty-render pausing on weak devices.
 */
class NintendoShellSurfaceRenderer(
    context: Context,
    private val onRenderModeNeeded: (continuous: Boolean) -> Unit,
    private val onReady: (() -> Unit)? = null,
) : GLSurfaceView.Renderer {
    private val appContext = context.applicationContext
    private var initialized = false
    private var lastContinuous = true
    private var lastFrameStartMs = 0L
    private var lastWidth = 0
    private var lastHeight = 0

    @Volatile
    var motionMode: Int = 0 // 0=Smooth 60Hz, 1=Calm, 2=Eco 30Hz, 3=Static

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            NintendoShellAudio.initialize(appContext)
            initialized = NintendoShellNative.nativeInit()
            lastContinuous = true
            lastFrameStartMs = SystemClock.elapsedRealtime()
            if (!initialized) {
                Log.e(TAG, "Nintendo native UI failed to initialize")
            } else {
                onReady?.invoke()
                if (lastWidth > 0 && lastHeight > 0) {
                    NintendoShellNative.nativeResize(lastWidth, lastHeight)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Nintendo surface initialization failed", error)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            lastWidth = width
            lastHeight = height
            if (initialized) {
                NintendoShellNative.nativeResize(width, height)
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!initialized) return

        if (motionMode == 2) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastFrameStartMs
            val targetMs = 33L
            if (elapsed in 0 until targetMs) {
                try {
                    Thread.sleep(targetMs - elapsed)
                } catch (_: InterruptedException) {
                    // Ignore wakeup
                }
            }
            lastFrameStartMs = SystemClock.elapsedRealtime()
        } else {
            lastFrameStartMs = SystemClock.elapsedRealtime()
        }

        val keepAnimating = NintendoShellNative.nativeDraw()
        if (keepAnimating != lastContinuous) {
            lastContinuous = keepAnimating
            onRenderModeNeeded(keepAnimating)
        }
    }

    private companion object {
        const val TAG = "NintendoShellSurface"
    }
}
