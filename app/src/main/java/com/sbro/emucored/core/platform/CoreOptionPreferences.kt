package com.sbro.emucored.core.platform

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Separate per-console namespaces, with optional identity-stable game overrides. */
class CoreOptionPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("core_options", Context.MODE_PRIVATE)

    fun globalOptions(platform: ConsolePlatform): Map<String, String> = read(platform, "global")
    fun gameOverrides(platform: ConsolePlatform, gameIdentity: String): Map<String, String> =
        read(platform, gameScope(gameIdentity))

    fun effectiveOptions(platform: ConsolePlatform, gameIdentity: String? = null): Map<String, String> =
        CoreOptionSchema.resolve(platform, globalOptions(platform),
            gameIdentity?.let { gameOverrides(platform, it) }.orEmpty())

    fun setGlobal(platform: ConsolePlatform, key: String, value: String): Boolean =
        put(platform, "global", key, value)

    fun setGameOverride(platform: ConsolePlatform, gameIdentity: String, key: String, value: String?): Boolean =
        put(platform, gameScope(gameIdentity), key, value)

    fun resetGlobal(platform: ConsolePlatform) {
        val editor = preferences.edit()
        CoreOptionSchema.options(platform).forEach { editor.remove(storageKey(platform, "global", it.key)) }
        editor.apply()
    }

    fun replaceGameOverrides(platform: ConsolePlatform, gameIdentity: String, values: Map<String, String>) {
        val scope = gameScope(gameIdentity)
        val normalized = CoreOptionSchema.normalize(platform, values)
        preferences.edit().apply {
            CoreOptionSchema.options(platform).forEach { option ->
                val key = storageKey(platform, scope, option.key)
                normalized[option.key]?.let { putString(key, it) } ?: remove(key)
            }
        }.apply()
    }

    fun resetAll() { preferences.edit().clear().apply() }

    fun exportJson(): JSONObject = JSONObject().put("version", 1).put("values", JSONObject().apply {
        preferences.all.forEach { (key, value) ->
            if (value is String && definitionForStorageKey(key)?.accepts(value) == true) put(key, value)
        }
    })

    fun importJson(json: JSONObject) {
        require(json.optInt("version") == 1) { "Unsupported core option backup version" }
        val values = requireNotNull(json.optJSONObject("values")) { "Missing core option backup values" }
        val entries = values.keys().asSequence().take(50_000).mapNotNull { key ->
            val value = values.opt(key) as? String ?: return@mapNotNull null
            if (definitionForStorageKey(key)?.accepts(value) == true) key to value else null
        }.toList()
        preferences.edit().apply {
            clear()
            entries.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    fun resetGame(platform: ConsolePlatform, gameIdentity: String) {
        val scope = gameScope(gameIdentity)
        val editor = preferences.edit()
        CoreOptionSchema.options(platform).forEach { editor.remove(storageKey(platform, scope, it.key)) }
        editor.apply()
    }

    private fun put(platform: ConsolePlatform, scope: String, key: String, value: String?): Boolean {
        val option = CoreOptionSchema.options(platform).find { it.key == key } ?: return false
        if (value != null && !option.accepts(value)) return false
        preferences.edit().apply {
            if (value == null) remove(storageKey(platform, scope, key))
            else putString(storageKey(platform, scope, key), value)
        }.apply()
        return true
    }

    private fun read(platform: ConsolePlatform, scope: String): Map<String, String> {
        val saved = preferences.all
        return CoreOptionSchema.options(platform).mapNotNull { option ->
            // Old or malformed preference types must not prevent a game from starting.
            val value = saved[storageKey(platform, scope, option.key)] as? String
            value?.takeIf(option::accepts)?.let { option.key to it }
        }.toMap()
    }

    private fun storageKey(platform: ConsolePlatform, scope: String, key: String) = "${platform.id}.$scope.$key"
    private fun definitionForStorageKey(key: String): CoreOptionDefinition? {
        val platform = ConsolePlatform.fromId(key.substringBefore('.')) ?: return null
        val suffix = key.substringAfter('.')
        val optionKey = when {
            suffix.startsWith("global.") -> suffix.removePrefix("global.")
            suffix.startsWith("game.") -> {
                val rest = suffix.removePrefix("game.")
                if (!rest.substringBefore('.').matches(Regex("[0-9a-f]{64}"))) return null
                rest.substringAfter('.', "")
            }
            else -> return null
        }
        return CoreOptionSchema.options(platform).find { it.key == optionKey }
    }
    private fun gameScope(identity: String) = "game." + MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
