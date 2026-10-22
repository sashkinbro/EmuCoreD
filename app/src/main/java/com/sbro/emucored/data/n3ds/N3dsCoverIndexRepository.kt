package com.sbro.emucored.data.n3ds

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import com.sbro.emucored.data.n3ds.N3dsCatalogRepository

/** A bundled, reviewed snapshot; regional serials share a canonical IGDB game. */
class N3dsCoverIndexRepository(private val context: Context) {
    data class Cover(val url: String, val sourceUrl: String, val sha256: String)

    fun find(serial: String?, title: String?, threeDimensional: Boolean = false): Cover? {
        val index = load()
        if (index != null) {
            val serialsObj = index.optJSONObject("serials")
            val cleanUpper = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
            val cleanLower = serial.orEmpty().lowercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
            val id = serialsObj?.optString(cleanUpper)?.takeIf { it.isNotBlank() }
                ?: serialsObj?.optString(cleanLower)?.takeIf { it.isNotBlank() }
                ?: (if (cleanUpper.startsWith("CTRP") && cleanUpper.length == 8) serialsObj?.optString(cleanUpper.substring(4))?.takeIf { it.isNotBlank() } else null)
                ?: index.optJSONObject("titles")?.optString(normalizeTitle(title.orEmpty()))?.takeIf { it.isNotBlank() }

            val game = id?.let { index.optJSONObject("games")?.optJSONObject(it) }
            if (game != null) {
                val path = game.optString(if (threeDimensional) "path_3d" else "path")
                if (Regex("covers/(3d/)?(dark/)?[0-9]+\\.(jpg|png|webp)").matches(path)) {
                    return Cover(
                        "$BASE_URL/$path",
                        game.optString("source_url"),
                        game.optString(if (threeDimensional) "sha256_3d" else "sha256")
                    )
                }
            }
        }
        // Fall back to the bundled 3DS games.db catalog
        val match = N3dsCatalogRepository.getInstance(context).findCoverMatch(serial, title) ?: return null
        val coverUrl = match.coverUrl ?: match.heroUrl ?: return null
        return Cover(coverUrl, match.heroUrl.orEmpty(), "")
    }

    private val catalog get() = N3dsCatalogRepository.getInstance(context)

    private fun load(): JSONObject? = cached ?: synchronized(lock) {
        cached ?: runCatching {
            context.assets.open("catalog/n3ds_covers.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()?.also { cached = it }
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Covers/main"
        private val lock = Any()
        @Volatile private var cached: JSONObject? = null

        internal fun normalizeTitle(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[™®©]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
