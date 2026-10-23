package com.sbro.emucored.data.covers

import android.content.Context
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.n3ds.N3dsCatalogRepository
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
        val loaded = load() ?: return null
        val index = loaded.index
        val id = loaded.identities.resolve(serial, title, platform) ?: return null
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

    private fun firstPath(game: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> game.optString(key).takeIf { it.isNotBlank() } }

    private fun load(): LoadedIndex? = cached ?: synchronized(lock) {
        cached ?: runCatching {
            context.assets.open("catalog/n3ds_covers.json").bufferedReader().use {
                val index = JSONObject(it.readText())
                // Serial prefixes still identify retail covers if the optional catalog DB is unavailable.
                val platforms = N3dsCatalogRepository.getInstance(context).coverPlatforms().orEmpty()
                LoadedIndex(index, CoverIdentityResolver(index, platforms))
            }
        }.getOrNull()?.also { cached = it }
    }

    private data class LoadedIndex(val index: JSONObject, val identities: CoverIdentityResolver)

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Covers/main"
        private val COVER_PATH = Regex(
            "covers/(?:3d/(?:dark/)?)?([0-9]+)\\.(?:jpg|jpeg|png|webp)",
            RegexOption.IGNORE_CASE
        )
        private val lock = Any()
        @Volatile private var cached: LoadedIndex? = null
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val TRADEMARK_SYMBOLS = Regex("[™®©]")
        private val TITLE_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        internal fun normalizeTitle(value: String): String =
            Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                .replace(COMBINING_MARKS, "")
                .replace(TRADEMARK_SYMBOLS, "")
                .replace(TITLE_SEPARATORS, " ")
                .trim()
    }
}
