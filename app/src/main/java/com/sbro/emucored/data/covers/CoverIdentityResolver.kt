package com.sbro.emucored.data.covers

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.core.ConsoleGameFormats
import org.json.JSONObject
import java.util.Locale

internal class CoverIdentityResolver(
    private val index: JSONObject,
    private val platforms: Map<String, GamePlatform>,
    private val scopedPlatform: GamePlatform? = null
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
        if (scopedPlatform != null && platform != scopedPlatform) return null
        val serials = index.optJSONObject("serials")
        val cleanSerial = serial.orEmpty().uppercase(Locale.ROOT)
            .replace(SERIAL_SEPARATORS, "")
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
                    else -> Unit
                }
            }
        }
        for (candidate in serialCandidates) {
            idFromTableValue(serials?.optString(candidate))?.takeIf { platform in platformMembership[it].orEmpty() }?.let { return it }
        }
        val lookupTitle = title.orEmpty().let {
            if (ConsoleGameFormats.isSupportedName(it)) it.substringBeforeLast('.') else it
        }
        val normalizedTitle = normalizeTitle(lookupTitle.replace(ROM_TAGS, " "))
        if (normalizedTitle in ambiguousTitles)
            return resolveRegionCandidate(normalizedTitle, lookupTitle, platform)
        if (normalizedTitle.isNotBlank()) {
            idFromTableValue(index.optJSONObject("titles")?.optString(normalizedTitle))
                ?.takeIf { platform in platformMembership[it].orEmpty() }?.let { return it }
            exactTitles[platform]?.get(normalizedTitle)?.singleOrNull()?.let { return it }
        }
        return findFuzzyGameId(normalizedTitle, platform)
    }

    private data class Candidate(val id: String, val title: String, val tokens: List<String>)

    private fun resolveRegionCandidate(title: String, original: String, platform: GamePlatform): String? {
        val tagWords = ROM_TAGS.findAll(original).flatMap { normalizeTitle(it.value).split(' ').asSequence() }.toSet()
        val regions = buildSet {
            if (tagWords.any { it in setOf("usa", "us", "usa", "canada") }) add("north_america")
            if (tagWords.any { it in setOf("europe", "eur", "eu") }) add("europe")
            if (tagWords.any { it in setOf("japan", "jpn", "jp") }) add("japan")
            if (tagWords.any { it in setOf("australia", "aus") }) add("australia")
            if (tagWords.any { it in setOf("asia") }) add("asia")
        }
        if (regions.isEmpty()) return null
        val ids = index.optJSONObject("title_candidates")?.optJSONArray(title) ?: return null
        val matching = (0 until ids.length()).mapNotNull { idFromTableValue(ids.optString(it)) }.filter { id ->
            if (platform !in platformMembership[id].orEmpty()) return@filter false
            val releases = index.optJSONObject("games")?.optJSONObject(id)?.optJSONArray("release_regions") ?: return@filter false
            (0 until releases.length()).any { releases.optString(it) in regions }
        }.distinct()
        return matching.singleOrNull()
    }

    // Prefixes are stronger evidence than catalog metadata: some DS entries were
    // imported with a 3DS platform label. A shared IGDB entry may support both.
    private val platformMembership: Map<String, Set<GamePlatform>> = buildMap {
        if (scopedPlatform != null) {
            index.optJSONObject("games")?.keys()?.forEach { put(it, setOf(scopedPlatform)) }
            return@buildMap
        }
        val serials = index.optJSONObject("serials")
        serials?.keys()?.forEach { serial ->
            val normalized = serial.uppercase(Locale.ROOT).replace(SERIAL_SEPARATORS, "")
            val platform = when {
                normalized.startsWith("NTR") || normalized.startsWith("TWL") -> GamePlatform.NDS
                normalized.startsWith("CTR") || normalized.startsWith("KTR") ||
                    normalized.matches(HEX_TITLE_ID) -> GamePlatform.N3DS
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
            val normalizedTitle = normalizeTitle(name)
            add(Candidate(id, normalizedTitle, titleTokensFromNormalized(normalizedTitle)))
        }
    }.let { all -> GamePlatform.entries.associateWith { platform ->
        all.filter { platform in platformMembership[it.id].orEmpty() }
    } }
    private val exactTitles = candidates.mapValues { (_, games) -> games.groupBy({ it.title }, { it.id }) }
    private val ambiguousTitles: Set<String> = buildSet {
        val table = index.optJSONObject("title_candidates")
        table?.keys()?.forEach { title ->
            if ((table.optJSONArray(title)?.length() ?: 0) > 1) add(title)
        }
    }
    private val tokenCandidates = candidates.mapValues { (_, games) ->
        buildMap<String, MutableList<Candidate>> {
            for (game in games) for (token in game.tokens) getOrPut(token) { mutableListOf() }.add(game)
        }
    }
    private val matches = CoverMatchCache(4096)

    private fun findFuzzyGameId(normalizedTitle: String, platform: GamePlatform): String? {
        val queryTokens = titleTokensFromNormalized(normalizedTitle)
        if (queryTokens.isEmpty()) return null
        var bestId: String? = null
        var bestScore = 0
        var tied = false
        val shortlist = queryTokens.flatMap { tokenCandidates[platform]?.get(it).orEmpty() }.distinctBy { it.id }
        for (candidate in shortlist) {
            if (candidate.title in ambiguousTitles) continue
            if (candidate.tokens.isEmpty()) continue
            val score = tokenMatchScore(queryTokens, candidate.tokens)
            if (score > bestScore) {
                bestScore = score
                bestId = candidate.id
                tied = false
            } else if (score > 0 && score == bestScore && candidate.id != bestId) {
                tied = true
            }
        }
        return bestId.takeUnless { tied }
    }

    private fun tokenMatchScore(query: List<String>, game: List<String>): Int {
        if (query == game) return 100
        // Extra words can identify a sequel, regional release or enhanced version.
        // A base title must never silently borrow one of those releases' artwork.
        if (query.size != game.size) return 0
        fun tokensMatch(left: String, right: String): Boolean {
            if (left == right) return true
            if (left.length < 5 || right.length < 5) return false
            return left.contains(right) || right.contains(left)
        }
        return if (query.zip(game).all { (q, g) -> tokensMatch(q, g) }) 50 else 0
    }

    private fun titleTokensFromNormalized(value: String): List<String> =
        value
            .replace(TITLE_FILLER_WORDS, " ")
            .replace(TRAILING_DS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .sorted()

    private fun idFromTableValue(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.all(Char::isDigit)) return value
        return COVER_PATH.find(value)?.groupValues?.getOrNull(1)
    }

    private companion object {
        val ROM_TAGS = Regex("\\([^)]*\\)|\\[[^]]*]")
        val SERIAL_SEPARATORS = Regex("[-_.\\s]")
        val HEX_TITLE_ID = Regex("[0-9A-F]{16}")
        val TITLE_FILLER_WORDS = Regex("\\b(the|a|an|game|nintendo|edition|version)\\b")
        val TRAILING_DS = Regex("\\s+ds$")
        val WHITESPACE = Regex("\\s+")
        val COVER_PATH = Regex(
            "covers/(?:3d/(?:dark/)?)?([0-9]+)\\.(?:jpg|jpeg|png|webp)",
            RegexOption.IGNORE_CASE
        )
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
