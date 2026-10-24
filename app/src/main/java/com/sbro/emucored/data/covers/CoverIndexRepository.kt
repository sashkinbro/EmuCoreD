package com.sbro.emucored.data.covers

import android.content.Context
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.n3ds.N3dsCatalogRepository
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Lazily loaded platform metadata; artwork remains in the public cover repository. */
class CoverIndexRepository(private val context: Context) {
    data class Cover(val url: String, val sourceUrl: String, val sha256: String)
    internal data class Asset(val path: String, val sha256: String)

    fun find(serial: String?, title: String?, platform: GamePlatform = GamePlatform.N3DS,
             threeDimensional: Boolean = false, darkTheme: Boolean = false): Cover? {
        val loaded = load(platform) ?: return null
        val id = loaded.identities.resolve(serial, title, platform) ?: return null
        val game = loaded.index.optJSONObject("games")?.optJSONObject(id) ?: return null
        val asset = selectAsset(game, platform, threeDimensional, darkTheme, id) ?: return null
        val source = game.optString("source_url").takeIf { IGDB_SOURCE.matches(it) }.orEmpty()
        return Cover("$BASE_URL/${asset.path}", source, asset.sha256)
    }

    fun hasCover(serial: String?, title: String?, platform: GamePlatform = GamePlatform.N3DS): Boolean =
        find(serial, title, platform) != null

    private fun load(platform: GamePlatform): LoadedIndex? = synchronized(lock) {
        cached[platform] ?: runCatching {
            val key = platformKey(platform)
            val scoped = runCatching {
                context.assets.open("catalog/covers/$key.json").bufferedReader().use { JSONObject(it.readText()) }
            }.getOrNull()
            if (scoped != null) {
                require(scoped.optInt("version") == 3 && scoped.optString("platform") == key)
                LoadedIndex(scoped, CoverIdentityResolver(scoped, emptyMap(), platform))
            } else if (platform == GamePlatform.N3DS || platform == GamePlatform.NDS) {
                context.assets.open("catalog/n3ds_covers.json").bufferedReader().use {
                    val legacy = JSONObject(it.readText())
                    val membership = N3dsCatalogRepository.getInstance(context).coverPlatforms().orEmpty()
                    LoadedIndex(legacy, CoverIdentityResolver(legacy, membership))
                }
            } else null
        }.getOrNull()?.also { cached[platform] = it }
    }

    private data class LoadedIndex(val index: JSONObject, val identities: CoverIdentityResolver)

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Covers/main"
        private val IGDB_SOURCE = Regex("https://images\\.igdb\\.com/igdb/image/upload/[A-Za-z0-9_/.-]+")
        private val HASH = Regex("[a-fA-F0-9]{64}")
        private val lock = Any()
        private val cached = mutableMapOf<GamePlatform, LoadedIndex>()
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val TRADEMARK_SYMBOLS = Regex("[\u2122\u00ae\u00a9]")
        private val TITLE_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        internal fun platformKey(platform: GamePlatform): String =
            if (platform == GamePlatform.N3DS) "n3ds" else if (platform == GamePlatform.NDS) "nds"
            else platform.name.lowercase(Locale.ROOT)

        private val pathPatterns: Map<Pair<String, String>, Pair<Regex, Regex>> by lazy {
            buildMap {
                for (platform in GamePlatform.entries) for (style in listOf("", "_3d", "_3d_dark")) {
                    val key = platformKey(platform)
                    val folder = when (style) { "_3d_dark" -> "3d/dark/"; "_3d" -> "3d/"; else -> "flat/" }
                    val legacyFolder = if (style.isEmpty()) "" else folder
                    put(key to style, Regex("platforms/$key/$folder([0-9]+)\\.(?:jpg|jpeg|png|webp)") to
                        Regex("covers/$legacyFolder([0-9]+)\\.(?:jpg|jpeg|png|webp)"))
                }
            }
        }

        /** The digest belongs to the selected asset, including a light/dark fallback. */
        internal fun selectAsset(game: JSONObject, platform: GamePlatform, threeDimensional: Boolean,
                                 darkTheme: Boolean, expectedId: String? = null): Asset? {
            val styles = when {
                !threeDimensional -> listOf("")
                darkTheme -> listOf("_3d_dark", "_3d", "")
                else -> listOf("_3d", "_3d_dark", "")
            }
            for (style in styles) for (field in listOf("cover$style", "path$style")) {
                val (scopedPath, legacyPath) = pathPatterns.getValue(platformKey(platform) to style)
                val path = game.optString(field)
                val match = scopedPath.matchEntire(path)
                    ?: if (platform == GamePlatform.N3DS || platform == GamePlatform.NDS)
                        legacyPath.matchEntire(path) else null
                if (match == null || (expectedId != null && match.groupValues[1] != expectedId)) continue
                val hash = game.optString("sha256$style")
                if (!HASH.matches(hash)) continue
                return Asset(path, hash.lowercase(Locale.ROOT))
            }
            return null
        }

        internal fun normalizeTitle(value: String): String =
            Normalizer.normalize(value.replace("+", " plus ").replace(TRADEMARK_SYMBOLS, "")
                .lowercase(Locale.ROOT).replace("\u00df", "ss").replace("\u03c2", "\u03c3"), Normalizer.Form.NFKD)
                .replace(COMBINING_MARKS, "").replace(TITLE_SEPARATORS, " ").trim()
    }
}
