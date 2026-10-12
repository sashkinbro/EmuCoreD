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
            val normalizedSerial = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
            val id = index.optJSONObject("serials")?.optString(normalizedSerial).orEmpty().ifBlank {
                index.optJSONObject("titles")?.optString(normalizeTitle(title.orEmpty())).orEmpty()
            }
            val game = id.takeIf { it.isNotBlank() }?.let { index.optJSONObject("games")?.optJSONObject(it) }
            if (game != null) {
                val path = game.optString(if (threeDimensional) "path_3d" else "path")
                if (Regex("covers/(3d/)?[0-9]+\\.(jpg|png|webp)").matches(path)) {
                    return Cover(
                        "$BASE_URL/$path",
                        game.optString("source_url"),
                        game.optString(if (threeDimensional) "sha256_3d" else "sha256")
                    )
                }
            }
        }
        // No reviewed cover repository is bundled yet: fall back to the IGDB
        // catalogue so library entries still get their catalogue artwork.
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
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreA-Covers/5af2519246d19a29da9ab069d512abd30a5a6113"
        private val lock = Any()
        @Volatile private var cached: JSONObject? = null

        internal fun normalizeTitle(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[™®©]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
