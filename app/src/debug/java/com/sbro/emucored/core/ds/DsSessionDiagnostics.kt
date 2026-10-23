package com.sbro.emucored.core.ds

import android.content.Context

internal fun traceDsSession(context: Context, message: String) {
    runCatching {
        val file = java.io.File(context.filesDir, "ds/session.log")
        file.parentFile?.mkdirs()
        file.appendText("${System.currentTimeMillis()} $message\n")
    }
}
