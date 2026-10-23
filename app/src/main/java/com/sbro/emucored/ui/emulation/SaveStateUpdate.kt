package com.sbro.emucored.ui.emulation

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** JNI save acceptance is asynchronous; an already occupied slot is not completion. */
internal suspend fun awaitSaveStateFileUpdate(
    previousModified: Long,
    attempts: Int = 40,
    intervalMs: Long = 250L,
    resolveFile: () -> File?
): Boolean {
    repeat(attempts) {
        coroutineContext.ensureActive()
        val file = resolveFile()
        if (file?.isFile == true && file.length() > 0L &&
            file.lastModified() > 0L && file.lastModified() != previousModified) return true
        delay(intervalMs)
    }
    return false
}
