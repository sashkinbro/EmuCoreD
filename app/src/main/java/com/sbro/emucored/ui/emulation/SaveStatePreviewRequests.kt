package com.sbro.emucored.ui.emulation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A request owns the resolved slot path, even if a different game starts later. */
class SaveStatePreviewRequest internal constructor(
    val savePath: String,
    val dualScreenPortrait: Boolean
) {
    private val completion = CompletableDeferred<Boolean>()
    val isPending: Boolean get() = !completion.isCompleted

    fun complete(success: Boolean) { completion.complete(success) }

    internal suspend fun await(timeoutMs: Long): Boolean = try {
        withTimeoutOrNull(timeoutMs) { completion.await() } ?: false
    } finally {
        // A queued request must not capture a subsequent game's surface.
        completion.complete(false)
    }
}

internal class SaveStatePreviewRequests(private val timeoutMs: Long = 5_000) {
    private val pending = MutableSharedFlow<SaveStatePreviewRequest>(extraBufferCapacity = 1)
    val requests = pending.asSharedFlow()

    suspend fun request(savePath: String, dualScreenPortrait: Boolean, awaitCompletion: Boolean): Boolean {
        if (pending.subscriptionCount.value == 0) return false
        val request = SaveStatePreviewRequest(savePath, dualScreenPortrait)
        if (!awaitCompletion) return pending.tryEmit(request)
        return try {
            // Include a full queue in the same budget as the capture itself.
            withTimeoutOrNull(timeoutMs) {
                pending.emit(request)
                request.await(timeoutMs)
            } ?: false
        } finally { request.complete(false) }
    }
}
