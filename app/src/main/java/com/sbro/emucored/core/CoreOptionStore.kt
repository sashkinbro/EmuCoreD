package com.sbro.emucored.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent store for Azahar core option overrides. Values are pushed to the
 * libretro core before every launch and kept in a dedicated SharedPreferences
 * file so the settings UI stays synchronous.
 */
object CoreOptionStore {
    private const val PREFS_NAME = "azahar_core_options"
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, String>()

    fun value(key: String): String? = cache[key]

    /** All persisted core option overrides. */
    fun persistedEntries(): Map<String, String> = HashMap(cache)

    fun set(key: String, value: String) {
        cache[key] = value
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cache.clear()
        prefs?.all?.forEach { (key, value) -> (value as? String)?.let { cache[key] = it } }
    }
}
