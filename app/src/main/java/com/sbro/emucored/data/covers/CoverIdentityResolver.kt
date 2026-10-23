package com.sbro.emucored.data.covers

import com.sbro.emucored.data.GamePlatform
import org.json.JSONObject
import java.util.Locale

internal class CoverIdentityResolver(
    private val index: JSONObject,
    private val platforms: Map<String, GamePlatform>
) {
    private fun normalizeTitle(value: String) = CoverIndexRepository.normalizeTitle(value)
    fun resolve(
        serial: String?,
        title: String?,
        platform: GamePlatform
    ): String? = matches.getOrResolve(serial, title, platform) {
        resolveUncached(serial, title, platform)
    }

    private fun resolveUncached(serial: String?, title: String?, platform: GamePlatform): String? {
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
            idFromTableValue(serials?.optString(candidate))?.takeIf { platform in platformMembership[it].orEmpty() }?.let { return it }
        }
        val normalizedTitle = normalizeTitle(title.orEmpty())
        if (normalizedTitle.isNotBlank()) {
            idFromTableValue(index.optJSONObject("titles")?.optString(normalizedTitle))
                ?.takeIf { platform in platformMembership[it].orEmpty() }?.let { return it }
            candidates[platform]?.firstOrNull { it.title == normalizedTitle }?.let { return it.id }
        }
        return findFuzzyGameId(title, platform)
    }

    private data class Candidate(val id: String, val title: String, val tokens: List<String>)

    // Prefixes are stronger evidence than catalog metadata: some DS entries were
    // imported with a 3DS platform label. A shared IGDB entry may support both.
    private val platformMembership: Map<String, Set<GamePlatform>> = buildMap {
        val serials = index.optJSONObject("serials")
        serials?.keys()?.forEach { serial ->
            val normalized = serial.uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
            val platform = when {
                normalized.startsWith("NTR") || normalized.startsWith("TWL") -> GamePlatform.NDS
                normalized.startsWith("CTR") || normalized.startsWith("KTR") ||
                    normalized.matches(Regex("[0-9A-F]{16}")) -> GamePlatform.N3DS
                else -> return@forEach
            }
            val id = idFromTableValue(serials.optString(serial)) ?: return@forEach
            put(id, get(id).orEmpty() + platform)
        }
        platforms.forEach { (id, platform) -> if (id !in this) put(id, setOf(platform)) }
    }

    // Tokenize once, rather than normalizing thousands of names for each tile/publication.
    private val candidates = buildList {
        val games = index.optJSONObject("games") ?: return@buildList
        val ids = games.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val name = games.optJSONObject(id)?.optString("name").orEmpty()
            add(Candidate(id, normalizeTitle(name), titleTokens(name)))
        }
    }.let { all -> GamePlatform.entries.associateWith { platform ->
        all.filter { platform in platformMembership[it.id].orEmpty() }
    } }
    private val matches = CoverMatchCache(4096)

    private fun findFuzzyGameId(title: String?, platform: GamePlatform): String? {
        val queryTokens = titleTokens(title.orEmpty())
        if (queryTokens.isEmpty()) return null
        var bestId: String? = null
        var bestScore = 0
        for (candidate in candidates[platform].orEmpty()) {
            if (candidate.tokens.isEmpty()) continue
            val score = tokenMatchScore(queryTokens, candidate.tokens)
            if (score > bestScore) {
                bestScore = score
                bestId = candidate.id
            }
        }
        return bestId
    }

    private fun tokenMatchScore(query: List<String>, game: List<String>): Int {
        val querySet = query.toSet()
        val gameSet = game.toSet()
        if (querySet == gameSet) return 100
        fun tokensMatch(left: String, right: String): Boolean {
            if (left == right) return true
            if (left.length < 5 || right.length < 5) return false
            return left.contains(right) || right.contains(left)
        }
        val queryInGame = query.all { q -> game.any { tokensMatch(q, it) } }
        val gameInQuery = game.all { g -> query.any { tokensMatch(g, it) } }
        return if (queryInGame || gameInQuery) 50 else 0
    }

    private fun titleTokens(value: String): List<String> =
        normalizeTitle(value)
            .replace(Regex("\\b(the|a|an|game|nintendo|edition|version)\\b"), " ")
            .replace(Regex("ds$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .sorted()

    private fun idFromTableValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.all(Char::isDigit)) return value
        return Regex("covers/(?:3d/(?:dark/)?)?([0-9]+)\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
    }

}

internal class CoverMatchCache(private val capacity: Int) {
    private data class Key(val serial: String?, val title: String?, val platform: GamePlatform)
    private val entries = object : LinkedHashMap<Key, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String?>): Boolean = size > capacity
    }

    @Synchronized
    fun getOrResolve(serial: String?, title: String?, platform: GamePlatform, resolve: () -> String?): String? {
        val key = Key(serial, title, platform)
        if (entries.containsKey(key)) return entries[key]
        return resolve().also { entries[key] = it }
    }
}
