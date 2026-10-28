package com.sbro.emucored.core

import android.content.Context
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage

/** Driver names, never arbitrary external paths; an empty game override means system driver. */
class GpuDriverPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("gpu_driver_selection", Context.MODE_PRIVATE)
    private fun globalKey(platform: ConsolePlatform) = "global.${platform.id}"
    private fun gameKey(platform: ConsolePlatform, identity: String) =
        "game.${platform.id}.${ConsoleSessionStorage.identityKey(identity)}"

    fun global(platform: ConsolePlatform): String? = preferences.getString(globalKey(platform), null)
    fun setGlobal(platform: ConsolePlatform, driverName: String?) {
        preferences.edit().apply {
            if (driverName.isNullOrBlank()) remove(globalKey(platform)) else putString(globalKey(platform), driverName)
        }.apply()
    }

    fun effective(platform: ConsolePlatform, gameIdentity: String?): String? {
        val key = gameIdentity?.takeIf { it.isNotBlank() }?.let { gameKey(platform, it) }
        return if (key != null && preferences.contains(key)) preferences.getString(key, "").orEmpty().ifBlank { null }
        else global(platform)
    }

    fun setGame(platform: ConsolePlatform, gameIdentity: String, driverName: String?) {
        require(gameIdentity.isNotBlank())
        preferences.edit().putString(gameKey(platform, gameIdentity), driverName.orEmpty()).apply()
    }

    fun clearGame(platform: ConsolePlatform, gameIdentity: String) {
        preferences.edit().remove(gameKey(platform, gameIdentity)).apply()
    }
}
