package com.sbro.emucored.data.covers

import android.content.Context
import com.sbro.emucored.data.GamePlatform
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Unified EmuCoreD-Covers resolver for 3DS and DS serials/titles. */
class CoverIndexRepository(private val context: Context) {
    data class Cover(val url: String, val sourceUrl: String, val sha256: String)

    fun find(
        serial: String?,
        title: String?,
        platform: GamePlatform = GamePlatform.N3DS,
        threeDimensional: Boolean = false,
        darkTheme: Boolean = false
    ): Cover? {
        val index = load() ?: return null
        val id = resolveGameId(index, serial, title, platform) ?: return null
        val game = index.optJSONObject("games")?.optJSONObject(id) ?: return null
        val path = when {
            threeDimensional && darkTheme -> firstPath(
                game,
                "cover_3d_dark",
                "path_3d_dark",
                "cover_3d",
                "path_3d",
                "cover",
                "path"
            )
            threeDimensional -> firstPath(
                game,
                "cover_3d",
                "path_3d",
                "cover_3d_dark",
                "path_3d_dark",
                "cover",
                "path"
            )
            else -> firstPath(game, "cover", "path")
        } ?: return null
        if (!COVER_PATH.matches(path)) return null
        val threeDimensionalPath = path.contains("/3d/")
        val sha256 = when {
            !threeDimensionalPath -> game.optString("sha256")
            darkTheme -> game.optString("sha256_3d_dark").ifBlank { game.optString("sha256") }
            else -> game.optString("sha256_3d").ifBlank { game.optString("sha256") }
        }
        return Cover(
            "$BASE_URL/$path",
            game.optString("source_url"),
            sha256
        )
    }

    fun hasCover(serial: String?, title: String?, platform: GamePlatform = GamePlatform.N3DS): Boolean =
        find(serial, title, platform) != null

    private fun resolveGameId(
        index: JSONObject,
        serial: String?,
        title: String?,
        platform: GamePlatform
    ): String? {
        val serials = index.optJSONObject("serials")
        val cleanSerial = serial.orEmpty().uppercase(Locale.ROOT)
            .replace(Regex("[-_.\\s]"), "")
            .trim()
        val serialCandidates = buildList {
            if (cleanSerial.isNotBlank()) {
                add(cleanSerial)
                add(cleanSerial.lowercase(Locale.ROOT))
                when (platform) {
                    GamePlatform.N3DS -> {
                        if (cleanSerial.startsWith("CTRP") && cleanSerial.length >= 8) {
                            add(cleanSerial.substring(4))
                        }
                        if (cleanSerial.startsWith("CTR") && cleanSerial.length >= 7) {
                            add(cleanSerial.substring(3))
                        }
                    }
                    GamePlatform.NDS -> {
                        if (cleanSerial.startsWith("NTR") && cleanSerial.length >= 7) {
                            add(cleanSerial.substring(3))
                        }
                    }
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
