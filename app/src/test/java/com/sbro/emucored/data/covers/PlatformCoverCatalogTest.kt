package com.sbro.emucored.data.covers

import com.sbro.emucored.data.GamePlatform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlatformCoverCatalogTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun catalog(platform: GamePlatform): JSONObject = JSONObject(
        File(root, "app/src/main/assets/catalog/covers/${CoverIndexRepository.platformKey(platform)}.json").readText()
    )

    @Test fun allNineCatalogsSelectEveryPublishedStyleWithItsOwnDigest() {
        var complete = 0
        for (platform in GamePlatform.entries) {
            val index = catalog(platform)
            assertEquals(3, index.getInt("version"))
            assertEquals(CoverIndexRepository.platformKey(platform), index.getString("platform"))
            val games = index.getJSONObject("games")
            for (id in games.keys()) {
                val game = games.getJSONObject(id)
                for ((threeDimensional, dark, suffix) in listOf(
                    Triple(false, false, ""), Triple(true, false, "_3d"), Triple(true, true, "_3d_dark")
                )) {
                    val asset = CoverIndexRepository.selectAsset(game, platform, threeDimensional, dark, id)
                    assertNotNull("$platform/$id$suffix", asset)
                    assertEquals(game.getString("path$suffix"), asset!!.path)
                    assertEquals(game.getString("sha256$suffix"), asset.sha256)
                }
                complete++
            }
        }
        assertEquals(20532, complete)
    }

    @Test fun regionalContraAndMortalKombatAmbiguitiesNeverSelectFirstCandidate() {
        for ((platform, title) in listOf(GamePlatform.NES to "Contra", GamePlatform.SNES to "Mortal Kombat II")) {
            val index = catalog(platform)
            val normalized = CoverIndexRepository.normalizeTitle(title)
            assertTrue(index.getJSONObject("title_candidates").getJSONArray(normalized).length() > 1)
            val resolver = CoverIdentityResolver(index, emptyMap(), platform)
            assertNull(resolver.resolve(null, title, platform))
            assertEquals(if (platform == GamePlatform.NES) "186207" else "1619",
                resolver.resolve(null, "$title (USA)", platform))
            assertEquals(if (platform == GamePlatform.NES) "186207" else "1619",
                resolver.resolve(null, "$title (USA).${if (platform == GamePlatform.NES) "nes" else "sfc"}", platform))
            assertEquals(if (platform == GamePlatform.NES) "186208" else "242249",
                resolver.resolve(null, "$title (Japan)", platform))
            assertNull(resolver.resolve(null, "$title (USA, Japan)", platform))
        }
    }

    @Test fun retailFixturesMatchWithinPlatformAndRetainDsSerialCompatibility() {
        val cases = listOf(
            GamePlatform.GAMECUBE to "Mario Party 7 (USA) (Rev 1)",
            GamePlatform.WII to "Super Mario Galaxy 2 (USA)",
            GamePlatform.GBA to "Defender (USA)",
            GamePlatform.GB to "Mega Man IV (USA)",
            GamePlatform.GBC to "The Legend of Zelda: Oracle of Ages (USA)",
            GamePlatform.SNES to "Donkey Kong Country (USA)"
        )
        for ((platform, title) in cases) {
            val index = catalog(platform)
            val id = CoverIdentityResolver(index, emptyMap(), platform).resolve(null, title, platform)
            assertNotNull("$platform: $title", id)
            assertTrue(index.getJSONObject("games").has(id!!))
        }
        for (platform in listOf(GamePlatform.NDS, GamePlatform.N3DS)) {
            val index = catalog(platform)
            val resolver = CoverIdentityResolver(index, emptyMap(), platform)
            val serials = index.getJSONObject("serials")
            assertTrue(serials.length() > 1000)
            for (serial in serials.keys()) {
                assertEquals("$platform/$serial", serials.getString(serial), resolver.resolve(serial, null, platform))
            }
        }
    }

    @Test fun everyUniqueCatalogTitleResolvesBackToItsOwnEntry() {
        for (platform in GamePlatform.entries) {
            val index = catalog(platform)
            val games = index.getJSONObject("games")
            val resolver = CoverIdentityResolver(index, emptyMap(), platform)
            val idsByTitle = mutableMapOf<String, MutableList<String>>()
            for (id in games.keys()) {
                val name = games.getJSONObject(id).optString("name")
                if (name.isBlank()) continue
                idsByTitle.getOrPut(CoverIndexRepository.normalizeTitle(name)) { mutableListOf() }.add(id)
            }
            val candidateTable = index.optJSONObject("title_candidates")
            for ((normalized, ids) in idsByTitle) {
                val candidateCount = candidateTable?.optJSONArray(normalized)?.length() ?: 0
                if (ids.size != 1 || candidateCount > 1) continue
                val name = games.getJSONObject(ids.single()).getString("name")
                assertEquals("$platform: $name", ids.single(), resolver.resolve(null, name, platform))
            }
        }
    }

    @Test fun truncatedHeadersAndFilenameSuffixesResolveToPublishedCovers() {
        val idsByPlatform = mapOf(
            GamePlatform.NDS to listOf(
                "Super Mario 64 DS (USA).nds" to "22301",
                "Simpsons Game, The (USA).nds" to "259329",
                "Legend of Zelda, The - Phantom Hourglass (USA) (En,Fr,Es).nds" to "1037"
            ),
            GamePlatform.N3DS to listOf(
                "Disney Planes" to "259323",
                "Mario and Luigi Dream Team Bros.3ds" to "3365",
                "Mario & Luigi Paper Jam Bros.3ds" to "11195",
                "Madagascar 3" to "19648",
                "Luigi's Mansion 2" to "2476"
            ),
            GamePlatform.SNES to listOf(
                "Contra IV - The Alien Wars (USA) (Beta).sfc" to "6351"
            )
        )
        for ((platform, cases) in idsByPlatform) {
            val resolver = CoverIdentityResolver(catalog(platform), emptyMap(), platform)
            for ((title, expected) in cases) {
                assertEquals("$platform: $title", expected, resolver.resolve(null, title, platform))
            }
        }
    }
}
