package com.sbro.emucored.core

import android.content.Context

/** Release adapter: no file backend, crash handler, formatting or diagnostic storage. */
object CrashLogger {
    fun init(context: Context) = Unit
    fun logError(tag: String, message: String, throwable: Throwable? = null) = Unit
    fun logInfo(tag: String, message: String) = Unit
    fun logContext(key: String, value: Any?) = Unit
}
