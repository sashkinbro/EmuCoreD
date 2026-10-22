package com.sbro.emucored.data.n3ds

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Bundled snapshot of the EmuCoreD-Covers index. */
class N3dsCoverIndexRepository(private val context: Context) {
    data class Cover(val url: String, val sourceUrl: String, val sha256: String)

    fun find(
        serial: String?,
        title: String?,
        threeDimensional: Boolean = false,
        darkTheme: Boolean = false
    ): Cover? {
        val index = load() ?: return null
        val id = resolveGameId(index, serial, title)
            ?: N3dsCatalogRepository.getInstance(context).findBestMatchId(serial, title)?.toString()
            ?: return null
        val game = index.optJSONObject("games")?.optJSONObject(id) ?: return null
        val path = when {
            threeDimensional && darkTheme -> firstPath(
                game,
                "cover_3d_dark",
                "path_3d_dark",
                "cover_3d",
                "path_3d"
            )
            threeDimensional -> firstPath(game, "cover_3d", "path_3d", "cover_3d_dark", "path_3d_dark")
            else -> firstPath(game, "cover", "path")
        } ?: return null
        if (!COVER_PATH.matches(path)) return null
        val sha256 = when {
            !threeDimensional -> game.optString("sha256")
            darkTheme -> game.optString("sha256_3d_dark").ifBlank { "" }
            else -> game.optString("sha256_3d")
        }
        return Cover(
            "$BASE_URL/$path",
            game.optString("source_url"),
            sha256
        )
    }

    private fun resolveGameId(index: JSONObject, serial: String?, title: String?): String? {
        val serials = index.optJSONObject("serials")
        val cleanUpper = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
        val cleanLower = cleanUpper.lowercase(Locale.ROOT)
        val serialCandidates = buildList {
            if (cleanUpper.isNotBlank()) {
                add(cleanUpper)
                add(cleanLower)
                if (cleanUpper.startsWith("CTRP") && cleanUpper.length == 8) {
                    add(cleanUpper.substring(4))
                }
            }
        }
        for (candidate in serialCandidates) {
            idFromTableValue(serials?.optString(candidate))?.let { return it }
        }
        val normalizedTitle = normalizeTitle(title.orEmpty())
        if (normalizedTitle.isNotBlank()) {
            idFromTableValue(index.optJSONObject("titles")?.optString(normalizedTitle))
                ?.let { return it }
        }
        return null
    }

    private fun idFromTableValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.all(Char::isDigit)) return value
        return COVER_PATH.find(value)?.groupValues?.getOrNull(1)
    }

    private fun firstPath(game: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> game.optString(key).takeIf { it.isNotBlank() } }

    private fun load(): JSONObject? = cached ?: synchronized(lock) {
        cached ?: runCatching {
            context.assets.open("catalog/n3ds_covers.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        }.getOrNull()?.also { cached = it }
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Covers/main"
        private val COVER_PATH = Regex(
            "covers/(?:3d/(?:dark/)?)?([0-9]+)\\.(?:jpg|jpeg|png|webp)",
            RegexOption.IGNORE_CASE
        )
        private val lock = Any()
        @Volatile private var cached: JSONObject? = null

        internal fun normalizeTitle(value: String): String =
            Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[™®©]"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
