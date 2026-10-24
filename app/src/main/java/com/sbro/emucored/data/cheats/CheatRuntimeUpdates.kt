package com.sbro.emucored.data.cheats

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A runtime reloads only when this original library identity is its own. */
object CheatRuntimeUpdates {
    private val events=MutableSharedFlow<String>(extraBufferCapacity=8)
    val changes=events.asSharedFlow()
    fun notifyChanged(identityPath:String){events.tryEmit(identityPath)}
}
