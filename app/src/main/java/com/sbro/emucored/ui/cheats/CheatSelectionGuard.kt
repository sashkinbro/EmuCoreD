package com.sbro.emucored.ui.cheats

/** UI-thread selection epochs bind asynchronous results to the request that started them. */
internal class CheatSelectionGuard(restored: Request? = null) {
    data class Request internal constructor(val path: String, internal val epoch: Long)
    private var epoch = restored?.epoch ?: 0L
    var current: Request? = restored
        private set

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<CheatSelectionGuard, Any>(
            save = { guard -> guard.current?.let { listOf(it.path, it.epoch) }.orEmpty() },
            restore = { values -> CheatSelectionGuard(values.takeIf { it.size == 2 }?.let {
                Request(it[0] as String, it[1] as Long)
            }) }
        )
    }

    fun select(path: String): Request = Request(path, ++epoch).also { current = it }
    fun isCurrent(request: Request): Boolean = current == request
    fun publish(request: Request, update: () -> Unit): Boolean {
        if (!isCurrent(request)) return false
        update()
        return true
    }
}
