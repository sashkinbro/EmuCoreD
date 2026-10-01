package com.sbro.emucored.data

import android.content.Context
import android.net.Uri
import com.sbro.emucored.core.EmulatorStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 3DS cheat files in the Gateway format used by Azahar:
 * `cheats/<TITLEID>.txt` with `[name]` sections and `*citra_enabled` markers.
 */
class N3dsCheatRepository(private val context: Context) {
    data class Section(val name: String, val enabled: Boolean, val code: List<String>)

    fun titleIdOf(game: GameItem): String? =
        game.serial
            ?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) }
            ?.uppercase(Locale.ROOT)

    fun cheatFile(titleId: String): File =
        File(EmulatorStorage.cheatsDir(context), "${titleId.uppercase(Locale.ROOT)}.txt")

    fun read(titleId: String): List<Section> {
        val file = cheatFile(titleId)
        if (!file.isFile) return emptyList()
        return parse(file.readText())
    }

    fun write(titleId: String, sections: List<Section>) {
        val file = cheatFile(titleId)
        file.parentFile?.mkdirs()
        file.writeText(serialize(sections))
    }

    fun delete(titleId: String) {
        cheatFile(titleId).delete()
    }

    fun import(titleId: String, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: return false
        val sections = parse(text)
        if (sections.isEmpty()) return false
        write(titleId, sections)
        true
    }.getOrDefault(false)

    /** Downloads the matching cheat pack from the EmuCoreD catalog. */
    suspend fun installFromCatalog(titleId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val catalog = JSONObject(httpGet(CATALOG_URL))
            val entries = catalog.optJSONArray("entries") ?: return@runCatching false
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val ids = entry.optJSONArray("titleIds") ?: continue
                val matches = (0 until ids.length()).any {
                    ids.optString(it).equals(titleId, ignoreCase = true)
                }
                if (!matches) continue
                val url = entry.optString("downloadUrl")
                if (url.isBlank()) continue
                val text = httpGet(url)
                if (text.isBlank()) continue
                val file = cheatFile(titleId)
                file.parentFile?.mkdirs()
                file.writeText(text)
                return@withContext true
            }
            false
        }.getOrDefault(false)
    }

    companion object {
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Cheat/main/cheats.json"

        fun parse(text: String): List<Section> {
            val sections = mutableListOf<Section>()
            var name: String? = null
            var enabled = false
            val code = mutableListOf<String>()

            fun flush() {
                val current = name
                if (current != null && code.isNotEmpty()) {
                    sections += Section(current, enabled, code.toList())
                }
                code.clear()
                enabled = false
            }

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("[") && line.endsWith("]") -> {
                        flush()
                        name = line.substring(1, line.length - 1)
                    }
                    line.equals("*citra_enabled", ignoreCase = true) -> enabled = true
                    line.isEmpty() || line.startsWith("//") || line.startsWith(";") -> Unit
                    name != null -> code += line
                }
            }
            flush()
            return sections
        }

        fun serialize(sections: List<Section>): String = buildString {
            sections.forEach { section ->
                append('[').append(section.name).append("]\n")
                if (section.enabled) append("*citra_enabled\n")
                section.code.forEach { append(it).append('\n') }
                append('\n')
            }
        }

        private fun httpGet(url: String): String {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "EmuCoreD")
            }
            return try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return ""
                connection.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
