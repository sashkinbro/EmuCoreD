package com.sbro.emucored.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent store for Azahar core config values.
 *
 * The core reads its `config.ini` from the user directory; EmuCoreD writes the
 * values it owns into this store and mirrors them into the ini on every
 * session start, then asks the core to reload its settings.
 */
object CoreOptionStore {
    private const val PREFS_NAME = "emucore_core_config"
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, String>()

    fun value(key: String): String? = cache[key]

    fun optional(key: String): String? = cache[key]

    /** All persisted core config values. */
    fun persistedEntries(): Map<String, String> = HashMap(cache)

    fun boolean(key: String, fallback: Boolean): Boolean =
        cache[key]?.toBooleanStrictOrNull() ?: fallback

    fun int(key: String, fallback: Int): Int = cache[key]?.toIntOrNull() ?: fallback

    fun set(key: String, value: String) {
        cache[key] = value
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun remove(key: String) {
        cache.remove(key)
        prefs?.edit()?.remove(key)?.apply()
    }

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cache.clear()
        prefs?.all?.forEach { (key, value) -> (value as? String)?.let { cache[key] = it } }
    }
}
