package com.sbro.emucored.data.covers

import com.sbro.emucored.data.GamePlatform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoverIdentityResolverTest {
    private val index = JSONObject("""{
        "serials":{"NTRPABCE":"covers/1.jpg","CTRPABCE":"covers/2.jpg"},
        "titles":{"shared adventure":"covers/1.jpg"},
        "games":{"1":{"name":"Shared Adventure"},"2":{"name":"Shared Adventure"},
            "3":{"name":"Only DS Adventure"},"4":{"name":"Only 3DS Adventure"}}
    }""")
    private fun resolver() = CoverIdentityResolver(index, mapOf(
        "1" to GamePlatform.NDS, "2" to GamePlatform.N3DS,
        "3" to GamePlatform.NDS, "4" to GamePlatform.N3DS
    ))

    @Test fun sameTitleResolvesWithinRequestedPlatform() {
        val resolver = resolver()
        assertEquals("1", resolver.resolve(null, "Shared Adventure", GamePlatform.NDS))
        assertEquals("2", resolver.resolve(null, "Shared Adventure", GamePlatform.N3DS))
    }

    @Test fun serialCannotSelectOtherPlatformAndFuzzyCannotCrossPlatforms() {
        val resolver = resolver()
        assertNull(resolver.resolve("NTR-P-ABCE", "Only DS Adventure", GamePlatform.N3DS))
        assertNull(resolver.resolve("CTR-P-ABCE", "Only 3DS Adventure", GamePlatform.NDS))
        assertEquals("1", resolver.resolve("NTR-P-ABCE", null, GamePlatform.NDS))
        assertEquals("2", resolver.resolve("CTR-P-ABCE", null, GamePlatform.N3DS))
    }

    @Test fun dsSerialEvidenceOverridesIncorrectCatalogPlatformLabel() {
        // These IDs, titles and serials are present in the bundled catalog.
        val realEntries = JSONObject("""{
            "serials":{"NTRPB6FE":"covers/196417.jpg","CTRNTDVJ":"covers/426.jpg"},
            "titles":{"lego batman 2 dc super heroes":"covers/196417.jpg","final fantasy iii":"covers/426.jpg"},
            "games":{"196417":{"name":"LEGO Batman 2: DC Super Heroes"},"426":{"name":"Final Fantasy III"}}
        }""")
        val resolver = CoverIdentityResolver(realEntries, mapOf(
            "196417" to GamePlatform.N3DS, "426" to GamePlatform.N3DS
        ))
        assertEquals("196417", resolver.resolve("NTR-P-B6FE", "LEGO Batman 2: DC Super Heroes", GamePlatform.NDS))
        assertNull(resolver.resolve(null, "LEGO Batman 2: DC Super Heroes", GamePlatform.N3DS))
        assertNull(resolver.resolve(null, "Final Fantasy III", GamePlatform.NDS))
        assertEquals("196417", CoverIdentityResolver(realEntries, emptyMap())
            .resolve("NTR-P-B6FE", null, GamePlatform.NDS))
    }

    @Test fun concurrentRepeatedMissResolvesOnlyOnce() {
        val cache = CoverMatchCache(4)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (1..16).map {
                executor.submit<String?> {
                    cache.getOrResolve(null, "Unmatched homebrew", GamePlatform.NDS) {
                        calls.incrementAndGet()
                        null
                    }
                }
            }
            futures.forEach { assertNull(it.get()) }
            assertEquals(1, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun cacheStoresHitsAndMissesSeparatelyByPlatformAndEvictsOldest() {
        val cache = CoverMatchCache(2)
        var lookups = 0
        fun lookup(serial: String?, title: String?, platform: GamePlatform, result: String?) =
            cache.getOrResolve(serial, title, platform) { lookups++; result }
        assertEquals("1", lookup("ABCE", "Shared Adventure", GamePlatform.NDS, "1"))
        assertEquals("1", lookup("ABCE", "Shared Adventure", GamePlatform.NDS, "unused"))
        assertNull(lookup("ABCE", "Shared Adventure", GamePlatform.N3DS, null))
        assertNull(lookup("ABCE", "Shared Adventure", GamePlatform.N3DS, "unused"))
        assertEquals(2, lookups)
        lookup(null, "Another", GamePlatform.NDS, "3")
        lookup("ABCE", "Shared Adventure", GamePlatform.NDS, "1")
        assertEquals(4, lookups)
    }
}
