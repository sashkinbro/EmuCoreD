package com.sbro.emucored.data.n3ds

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.sbro.emucored.data.GamePlatform
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale

class N3dsCatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "N3dsCatalogRepository"
        // New cache epoch extracts the all-console asset on upgrades; retain the legacy file.
        internal const val DB_NAME = "nintendo_games_v2.db"
        private const val ASSET_PATH = "catalog/games.db"

        private val sharedCoverMatchCache =
            java.util.concurrent.ConcurrentHashMap<String, CatalogCover>()
        private val sharedCoverMatchMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        @Volatile private var sharedFuzzyCandidates: Pair<String, List<FuzzyCandidate>>? = null

        /** One catalog instance per process: the database is read-only and shared. */
        @Volatile private var sharedInstance: N3dsCatalogRepository? = null

        fun getInstance(context: Context): N3dsCatalogRepository {
            sharedInstance?.let { return it }
            return synchronized(this) {
                sharedInstance ?: N3dsCatalogRepository(context.applicationContext)
                    .also { sharedInstance = it }
            }
        }
    }

    private val dbFile: File by lazy { File(context.noBackupFilesDir, DB_NAME) }
    private var database: SQLiteDatabase? = null

    private val defaultSortOrder = """
        CASE WHEN rating IS NULL THEN 1 ELSE 0 END,
        rating DESC,
        CASE WHEN year IS NULL THEN 1 ELSE 0 END,
        year DESC,
        name COLLATE NOCASE ASC
    """.trimIndent()

    fun ensureDatabaseReady(): Boolean {
        if (dbFile.exists() && dbFile.length() > 0L) {
            return true
        }

        return runCatching {
            dbFile.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "Catalog DB is not bundled yet: ${it.message}")
            false
        }
    }

    fun hasCatalog(): Boolean = ensureDatabaseReady()

    fun search(query: String, limit: Int = 60, offset: Int = 0): List<N3dsCatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        return search(
            query = query,
            genre = null,
            year = null,
            minRating = null,
            limit = limit,
            offset = offset
        )
    }

    fun search(
        query: String,
        genre: String?,
        year: Int?,
        minRating: Double?,
        platform: String? = null,
        limit: Int = 60,
        offset: Int = 0
    ): List<N3dsCatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        val normalized = normalizeSearchText(query)
        if (normalized.isBlank()) {
            return topRated(
                genre = genre,
                year = year,
                minRating = minRating,
                platform = platform,
                limit = limit,
                offset = offset
            )
        }

        val db = getDatabase() ?: return emptyList()
        val out = ArrayList<N3dsCatalogSummary>(limit)
        val seen = HashSet<Long>(limit * 2)
        val fetchWindow = limit + offset
        querySearchPage(
            db = db,
            normalizedPattern = "$normalized%",
            genre = genre,
            year = year,
            minRating = minRating,
            platform = platform,
            limit = fetchWindow
        ).forEach { summary ->
            if (seen.add(summary.igdbId)) {
                out += summary
            }
        }

        if (out.size < fetchWindow) {
            querySearchPage(
                db = db,
                normalizedPattern = "%$normalized%",
                genre = genre,
                year = year,
                minRating = minRating,
                platform = platform,
                limit = fetchWindow
            ).forEach { summary ->
                if (seen.add(summary.igdbId)) {
                    out += summary
                }
            }
        }

        return out.drop(offset).take(limit)
    }

    fun getDetails(igdbId: Long): N3dsCatalogDetails? {
        if (!ensureDatabaseReady()) return null
        val db = getDatabase() ?: return null
        return db.rawQuery(
            """
            SELECT igdb_id, name, normalized_name, year, rating, summary, storyline, cover_url, hero_url, platform
            FROM games
            WHERE igdb_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(igdbId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            loadDetails(db, cursorToSummary(cursor, db))
        }
    }

    /** Platform metadata for the shared cover index, loaded once with that index. */
    internal fun coverPlatforms(): Map<String, com.sbro.emucored.data.GamePlatform>? {
        if (!ensureDatabaseReady()) return null
        val db = getDatabase() ?: return null
        return runCatching {
            buildMap {
                db.rawQuery("SELECT igdb_id, platform FROM games", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val platform = when (cursor.getString(1)) {
                            "3DS" -> com.sbro.emucored.data.GamePlatform.N3DS
                            "DS", "DSI" -> com.sbro.emucored.data.GamePlatform.NDS
                            else -> continue
                        }
                        put(cursor.getLong(0).toString(), platform)
                    }
                }
            }
        }.getOrNull()
    }

    data class CatalogCover(val igdbId: Long, val coverUrl: String?, val heroUrl: String?)

    private val coverMatchCache get() = sharedCoverMatchCache
    private val coverMatchMisses get() = sharedCoverMatchMisses

    /** Cover art for the library, matched through the bundled IGDB catalog. */
    fun findCoverMatch(serial: String?, title: String?): CatalogCover? {
        val cacheKey = "${serial.orEmpty()}\u0000${title.orEmpty()}".lowercase(Locale.ROOT)
        sharedCoverMatchCache[cacheKey]?.let { return it }
        if (cacheKey in sharedCoverMatchMisses) return null
        val match = runCatching {
            val id = findBestMatchId(serial, title) ?: return@runCatching null
            val db = getDatabase() ?: return@runCatching null
            db.rawQuery(
                "SELECT cover_url, hero_url FROM games WHERE igdb_id = ? LIMIT 1",
                arrayOf(id.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else CatalogCover(id, cursor.getString(0), cursor.getString(1))
            }
        }.getOrNull()
        // ConcurrentHashMap rejects null values, so misses get their own set.
        if (match == null) sharedCoverMatchMisses.add(cacheKey) else sharedCoverMatchCache[cacheKey] = match
        return match
    }

    fun findBestMatchId(serial: String?, title: String?, platform: GamePlatform? = null): Long? {
        if (!ensureDatabaseReady()) return null
        val candidateSerials = listOfNotNull(serial)
            .map { it.normalizeSerialForMatch() }
            .filter { it.isNotBlank() }
            .distinct()

        val db = getDatabase() ?: return null
        findIdByDatabaseSerial(db, candidateSerials, platform)?.let { id ->
            Log.d(TAG, "Catalog match by database serial: $serial -> $id")
            return id
        }

        val candidateTitles = listOfNotNull(title)
            .map(::normalizeIdentityTitle)
            .filter { it.isNotBlank() }
            .distinct()
        findExactTitleId(db, candidateTitles, platform)?.let { id ->
            Log.d(TAG, "Catalog match by exact title: $title -> $id")
            return id
        }
        findFuzzyTitleId(db, candidateTitles, platform)?.let { id ->
            Log.d(TAG, "Catalog match by fuzzy title: $title -> $id")
            return id
        }

        Log.w(TAG, "No catalog match for serial=$serial title=$title")
        return null
    }

    private fun findIdByDatabaseSerial(db: SQLiteDatabase, serials: List<String>, platform: GamePlatform?): Long? {
        if (serials.isEmpty()) return null
        return runCatching {
            val placeholders = serials.joinToString(",") { "?" }
            db.rawQuery(
                """
                SELECT igdb_id
                FROM game_serials
                WHERE UPPER(REPLACE(REPLACE(REPLACE(REPLACE(serial, '-', ''), '_', ''), '.', ''), ' ', ''))
                    IN ($placeholders)
                    AND igdb_id IN (SELECT igdb_id FROM games WHERE 1=1 ${catalogPlatformClause(platform)})
                LIMIT 1
                """.trimIndent(),
                serials.toTypedArray()
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
    }

    private fun findExactTitleId(db: SQLiteDatabase, titles: List<String>, platform: GamePlatform?): Long? {
        titles.forEach { title ->
            db.rawQuery(
                "SELECT igdb_id FROM games WHERE normalized_name = ? ${catalogPlatformClause(platform)} LIMIT 1",
                arrayOf(title)
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        }
        return null
    }

    private fun findFuzzyTitleId(db: SQLiteDatabase, titles: List<String>, platform: GamePlatform?): Long? {
        if (titles.isEmpty()) return null
        val candidateTokens = titles.map(::catalogMatchTokens).filter { it.isNotEmpty() }
        if (candidateTokens.isEmpty()) return null
        val candidates = fuzzyCandidates(db) ?: return null
        var bestId: Long? = null
        var bestScore = 0.0
        val allowedPlatforms = catalogPlatformCodes(platform)
        for (candidate in candidates) {
            if (allowedPlatforms != null && candidate.platform !in allowedPlatforms) continue
            for (tokens in candidateTokens) {
                val score = tokenSimilarity(tokens, candidate.tokens)
                if (score > bestScore) {
                    bestScore = score
                    bestId = candidate.id
                }
            }
        }
        return bestId?.takeIf { bestScore >= MIN_FUZZY_TITLE_SCORE }
    }

    private data class FuzzyCandidate(val id: Long, val tokens: Set<String>, val platform: String)

    /** All catalog titles are tokenised once per process, not per lookup. */
    private fun fuzzyCandidates(db: SQLiteDatabase): List<FuzzyCandidate>? {
        sharedFuzzyCandidates?.takeIf { it.first == db.path }?.let { return it.second }
        return runCatching {
            val candidates = ArrayList<FuzzyCandidate>(2400)
            db.rawQuery("SELECT igdb_id, normalized_name, platform FROM games", null).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates += FuzzyCandidate(cursor.getLong(0), catalogMatchTokens(cursor.getString(1).orEmpty()), cursor.getString(2).orEmpty())
                }
            }
            candidates.also { sharedFuzzyCandidates = db.path to it }
        }.getOrNull()
    }

    fun topRated(
        genre: String?,
        year: Int?,
        minRating: Double?,
        platform: String? = null,
        limit: Int = 60,
        offset: Int = 0
    ): List<N3dsCatalogSummary> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<N3dsCatalogSummary>(limit)
        buildCatalogQuery(
            namePattern = null,
            genre = genre,
            year = year,
            minRating = minRating,
            platform = platform,
            includeOffset = true
        ).let { (sql, args) ->
            db.rawQuery(sql, (args + limit.toString() + offset.toString()).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    items += cursorToSummary(cursor, db)
                }
            }
        }
        return items
    }

    fun getAvailableGenres(limit: Int = 18): List<String> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<String>(limit)
        db.rawQuery(
            """
            SELECT genre_name, COUNT(*) AS usage_count
            FROM game_genres
            GROUP BY genre_name
            ORDER BY usage_count DESC, genre_name COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.getString(0)
            }
        }
        return items
    }

    fun getAvailablePlatforms(): List<String> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<String>(3)
        db.rawQuery(
            "SELECT DISTINCT platform FROM games WHERE platform IS NOT NULL ORDER BY platform",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) items += cursor.getString(0)
        }
        return items
    }

    fun getAvailableYears(limit: Int = 30): List<Int> {
        if (!ensureDatabaseReady()) return emptyList()
        val db = getDatabase() ?: return emptyList()
        val items = ArrayList<Int>(limit)
        db.rawQuery(
            """
            SELECT DISTINCT year
            FROM games
            WHERE year IS NOT NULL
            ORDER BY year DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += cursor.getInt(0)
            }
        }
        return items
    }

    private fun getDatabase(): SQLiteDatabase? {
        val current = database
        if (current != null && current.isOpen) {
            return current
        }

        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).also { database = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open catalog database: ${e.message}")
            null
        }
    }

    fun close() {
        database?.close()
        database = null
    }

    private fun querySearchPage(
        db: SQLiteDatabase,
        normalizedPattern: String,
        genre: String?,
        year: Int?,
        minRating: Double?,
        platform: String?,
        limit: Int
    ): List<N3dsCatalogSummary> {
        val out = ArrayList<N3dsCatalogSummary>(limit)
        buildCatalogQuery(
            namePattern = normalizedPattern,
            genre = genre,
            year = year,
            minRating = minRating,
            platform = platform,
            includeOffset = false
        ).let { (sql, args) ->
            db.rawQuery(sql, (args + limit.toString()).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    out += cursorToSummary(cursor, db)
                }
            }
        }
        return out
    }

    private fun buildCatalogQuery(
        namePattern: String?,
        genre: String?,
        year: Int?,
        minRating: Double?,
        platform: String?,
        includeOffset: Boolean
    ): Pair<String, List<String>> {
        val where = ArrayList<String>(5)
        val args = ArrayList<String>(5)

        if (!namePattern.isNullOrBlank()) {
            where += "normalized_name LIKE ?"
            args += namePattern
        }
        if (!genre.isNullOrBlank()) {
            where += """
                EXISTS (
                    SELECT 1
                    FROM game_genres gg
                    WHERE gg.igdb_id = games.igdb_id
                      AND gg.genre_name = ?
                )
            """.trimIndent()
            args += genre
        }
        year?.let {
            where += "year = ?"
            args += it.toString()
        }
        minRating?.let {
            where += "rating >= ?"
            args += it.toString()
        }
        if (!platform.isNullOrBlank()) {
            where += "platform = ?"
            args += platform
        }

        val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val limitClause = if (includeOffset) "LIMIT ? OFFSET ?" else "LIMIT ?"
        return """
            SELECT igdb_id, name, normalized_name, year, rating, summary, storyline, cover_url, hero_url, platform
            FROM games
            $whereClause
            ORDER BY $defaultSortOrder
            $limitClause
        """.trimIndent() to args
    }

    private fun loadDetails(db: SQLiteDatabase, summary: N3dsCatalogSummary): N3dsCatalogDetails {
        val genres = ArrayList<String>(summary.genres)
        if (genres.isEmpty()) {
            db.rawQuery(
                """
                SELECT genre_name
                FROM game_genres
                WHERE igdb_id = ?
                ORDER BY genre_name COLLATE NOCASE ASC
                """.trimIndent(),
                arrayOf(summary.igdbId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    genres += cursor.getString(0)
                }
            }
        }

        val screenshots = ArrayList<String>(10)
        db.rawQuery(
            """
            SELECT image_url
            FROM game_screenshots
            WHERE igdb_id = ?
            ORDER BY position ASC
            LIMIT 10
            """.trimIndent(),
            arrayOf(summary.igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                toHighResImageUrl(cursor.getString(0))?.let { screenshots += it }
            }
        }

        val videos = ArrayList<String>(10)
        db.rawQuery(
            """
            SELECT youtube_id
            FROM game_videos
            WHERE igdb_id = ?
            ORDER BY position ASC
            LIMIT 10
            """.trimIndent(),
            arrayOf(summary.igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                videos += cursor.getString(0)
            }
        }

        return N3dsCatalogDetails(
            igdbId = summary.igdbId,
            name = summary.name,
            normalizedName = summary.normalizedName,
            year = summary.year,
            rating = summary.rating,
            storyline = summary.storyline,
            summary = summary.summary,
            genres = genres.distinct(),
            screenshots = screenshots,
            videos = videos,
            coverUrl = toHighResImageUrl(summary.coverUrl),
            heroUrl = toHighResImageUrl(summary.heroUrl),
            primarySerial = summary.primarySerial,
            platform = summary.platform
        )
    }

    private fun cursorToSummary(cursor: android.database.Cursor, db: SQLiteDatabase): N3dsCatalogSummary {
        val igdbId = cursor.getLong(0)
        val name = cursor.getString(1)
        return N3dsCatalogSummary(
            igdbId = igdbId,
            name = name,
            normalizedName = cursor.getString(2),
            year = cursor.getIntOrNull(3),
            rating = cursor.getDoubleOrNull(4),
            summary = cursor.getStringOrNull(5),
            storyline = cursor.getStringOrNull(6),
            coverUrl = cursor.getStringOrNull(7),
            heroUrl = cursor.getStringOrNull(8),
            genres = loadGenresPreview(db, igdbId),
            primarySerial = loadPrimarySerial(db, igdbId),
            platform = cursor.getStringOrNull(9) ?: "3DS"
        )
    }

    private fun loadPrimarySerial(db: SQLiteDatabase, igdbId: Long): String? = db.rawQuery(
        "SELECT serial FROM game_serials WHERE igdb_id = ? ORDER BY serial LIMIT 1",
        arrayOf(igdbId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun loadGenresPreview(db: SQLiteDatabase, igdbId: Long): List<String> {
        val out = ArrayList<String>(4)
        db.rawQuery(
            """
            SELECT genre_name
            FROM game_genres
            WHERE igdb_id = ?
            ORDER BY genre_name COLLATE NOCASE ASC
            LIMIT 4
            """.trimIndent(),
            arrayOf(igdbId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += cursor.getString(0)
            }
        }
        return out
    }

    private fun cleanupTitle(value: String): String {
        return value
            .substringBeforeLast('.')
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cd|dvd)\s*[0-9]+\b"""), " ")
            .replace(Regex("""\b(v[0-9]+|ver\s*[0-9]+(\.[0-9]+)?|rev\s*[a-z0-9]+|patch\s*[0-9]+)\b"""), " ")
            .replace(Regex("""\b(usa|us|ntsc|pal|eur|europe|japan|jpn|korea|kor|france|germany|spain|esp|italy|russia|russian|beta|demo|proto|prototype|undub|unl|translated|en|fr|de|es|it|ru|pt|pl|ko|ja|jp)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeSearchText(value: String): String {
        val cleaned = cleanupTitle(value).lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return normalized
            .replace("&", " and ")
            .replace("@", " at ")
            .replace(Regex("""\bviii\b"""), " 8 ")
            .replace(Regex("""\bvii\b"""), " 7 ")
            .replace(Regex("""\bvi\b"""), " 6 ")
            .replace(Regex("""\biv\b"""), " 4 ")
            .replace(Regex("""\biii\b"""), " 3 ")
            .replace(Regex("""\bii\b"""), " 2 ")
            .replace(Regex("""\bix\b"""), " 9 ")
            .replace(Regex("""\bxii\b"""), " 12 ")
            .replace(Regex("""\bxi\b"""), " 11 ")
            .replace(Regex("""\bv\b"""), " 5 ")
            .replace(Regex("""\bx\b"""), " 10 ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.normalizeSerialForMatch(): String {
        return trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
    }

    private fun toHighResImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return url.replace(Regex("""/t_[^/]+/"""), "/t_1080p/")
    }
}

private const val MIN_FUZZY_TITLE_SCORE = 0.60

internal fun normalizeIdentityTitle(value: String): String {
    return Normalizer.normalize(
        value.lowercase(Locale.ROOT)
            .replace(Regex("""['’]s\b"""), ""),
        Normalizer.Form.NFD
    )
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun catalogTitleSimilarity(left: String, right: String): Double {
    val leftTokens = catalogMatchTokens(left)
    val rightTokens = catalogMatchTokens(right)
    return tokenSimilarity(leftTokens, rightTokens)
}

internal fun tokenSimilarity(leftTokens: Set<String>, rightTokens: Set<String>): Double {
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    if (leftTokens == rightTokens) return 1.0
    val remaining = rightTokens.toMutableSet()
    var matched = 0
    for (token in leftTokens) {
        val exact = remaining.firstOrNull { it == token }
        val match = exact ?: remaining.firstOrNull { catalogTokensLookAlike(token, it) }
        if (match != null) {
            matched++
            remaining.remove(match)
        }
    }
    if (matched == 0 || (matched == 1 && minOf(leftTokens.size, rightTokens.size) > 1)) return 0.0
    val candidateCoverage = matched.toDouble() / leftTokens.size
    val catalogCoverage = matched.toDouble() / rightTokens.size
    return candidateCoverage * 0.75 + catalogCoverage * 0.25
}

/**
 * Tolerates the spelling differences dumps and catalogue names use: plurals
 * ("Yoshis" vs "Yoshi"), possessives ("Luigis" vs "Luigi's") and single
 * character typos.
 */
private fun catalogTokensLookAlike(left: String, right: String): Boolean {
    if (left.length < 3 || right.length < 3) return false
    if (left == right) return true
    if (left.removeSuffix("s") == right.removeSuffix("s")) return true
    if (kotlin.math.abs(left.length - right.length) > 1) return false
    return isWithinOneEdit(left, right)
}

private fun isWithinOneEdit(left: String, right: String): Boolean {
    if (left == right) return true
    val shorter = if (left.length <= right.length) left else right
    val longer = if (left.length <= right.length) right else left
    if (longer.length - shorter.length > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < shorter.length && j < longer.length) {
        if (shorter[i] == longer[j]) {
            i++
            j++
            continue
        }
        edits++
        if (edits > 1) return false
        if (shorter.length == longer.length) i++
        j++
    }
    return true
}

private fun catalogMatchTokens(value: String): Set<String> {
    return normalizeIdentityTitle(value)
        .split(' ')
        .asSequence()
        .filter { it.isNotBlank() && it !in CATALOG_TITLE_STOP_WORDS }
        // Single letters left over from possessives ("luigi s mansion") are
        // noise; single digits ("mansion 2") still matter.
        .filter { it.length > 1 || it.all(Char::isDigit) }
        .map { CATALOG_ROMAN_NUMERALS[it] ?: it }
        .toCollection(LinkedHashSet())
}

private val CATALOG_TITLE_STOP_WORDS = setOf("a", "an", "and", "of", "the")
private val CATALOG_ROMAN_NUMERALS = mapOf(
    "ii" to "2",
    "iii" to "3",
    "iv" to "4",
    "v" to "5",
    "vi" to "6",
    "vii" to "7",
    "viii" to "8",
    "ix" to "9",
    "x" to "10",
    "xi" to "11",
    "xii" to "12"
)

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? {
    return if (isNull(index)) null else getDouble(index)
}

/** Existing single-platform catalog labels; DSi belongs to the DS product family. */
internal fun catalogPlatformCodes(platform: GamePlatform?): Set<String>? = when (platform) {
    null -> null
    GamePlatform.N3DS -> setOf("3DS")
    GamePlatform.NDS -> setOf("DS", "DSI")
    else -> setOf(platform.name)
}

private fun catalogPlatformClause(platform: GamePlatform?): String = catalogPlatformCodes(platform)
    ?.joinToString(prefix = " AND platform IN (", postfix = ")") { "'$it'" }.orEmpty()
