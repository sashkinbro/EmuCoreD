package com.sbro.emucored.data

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

internal const val PROFILE_GAME_PLATFORM_KEY = "p"

enum class ProfilePlatformFilter {
    ALL, NDS, N3DS, GAMECUBE, WII, GBA, GB, GBC, NES, SNES, UNKNOWN;

    val platform: GamePlatform?
        get() = GamePlatform.entries.firstOrNull { it.name == name }
}

data class ProfilePlatformTotal(val games: Int = 0, val playTimeMs: Long = 0L)
data class ProfilePlatformBreakdown(
    val platforms: Map<GamePlatform, ProfilePlatformTotal> = emptyMap(),
    val unknown: ProfilePlatformTotal = ProfilePlatformTotal()
) {
    operator fun get(platform: GamePlatform?): ProfilePlatformTotal =
        if (platform == null) unknown else platforms[platform] ?: ProfilePlatformTotal()

    val ds: ProfilePlatformTotal get() = get(GamePlatform.NDS)
    val threeDs: ProfilePlatformTotal get() = get(GamePlatform.N3DS)
}

/** Paths come from real launch records; legacy records fall back only to known serial formats. */
internal fun resolveProfilePlatform(serial: String?, path: String? = null): GamePlatform? {
    val decodedPath = path?.let {
        runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
    }
    decodedPath?.let(GamePlatform::fromFileName)?.let { return it }
    val code = serial.orEmpty().uppercase(Locale.ROOT).replace(Regex("[-_.\\s]"), "")
    return when {
        code.matches(Regex("(?:NTR|TWL)P?[A-Z0-9]{4}")) || code.matches(Regex("[A-Z0-9]{4}")) -> GamePlatform.NDS
        code.matches(Regex("(?:CTR|KTR)[PN]?[A-Z0-9]{4}")) || code.matches(Regex("[0-9A-F]{16}")) -> GamePlatform.N3DS
        else -> null
    }
}

internal fun readProfilePlatform(record: Map<*, *>): GamePlatform? {
    val stored = record[PROFILE_GAME_PLATFORM_KEY] as? String
    if (!stored.isNullOrBlank()) return GamePlatform.entries.firstOrNull { it.name == stored }
    return resolveProfilePlatform(record["s"] as? String)
}

private fun platformScopedProfileKey(key: String, platform: GamePlatform?): String =
    if (platform == null || platform == GamePlatform.NDS || platform == GamePlatform.N3DS) key
    else "${platform.name}_$key"

/** Old DS/3DS document keys remain readable; new engines cannot merge equal serials. */
internal fun profileGameKey(serial: String?, path: String, platform: GamePlatform?): String {
    val serialKey = serial?.trim()?.uppercase(Locale.ROOT)?.replace(Regex("[^A-Z0-9_-]"), "_")
        ?.takeIf { it.isNotBlank() }
    val key = serialKey ?: "P_" + MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    return platformScopedProfileKey(key, platform)
}

internal fun profilePlayTimeCacheKey(entry: PlayerPlayTimeDelta): String =
    platformScopedProfileKey(entry.serial?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        ?: entry.gamePath.orEmpty(), entry.platform ?: resolveProfilePlatform(entry.serial, entry.gamePath))

internal fun writeProfilePlatform(record: MutableMap<String, Any>, platform: GamePlatform?) {
    if (platform == null) record.remove(PROFILE_GAME_PLATFORM_KEY)
    else record[PROFILE_GAME_PLATFORM_KEY] = platform.name
}

fun PlayerGamePlayStat.profilePlatform(): GamePlatform? = platform ?: resolveProfilePlatform(serial)

fun List<PlayerGamePlayStat>.filterProfilePlatform(filter: ProfilePlatformFilter): List<PlayerGamePlayStat> =
    if (filter == ProfilePlatformFilter.ALL) this else filter { game ->
        game.profilePlatform() == filter.platform
    }

/** Incomplete public pages must never attribute unloaded games/time to a platform. */
fun profilePlatformBreakdown(
    games: List<PlayerGamePlayStat>, totalGames: Int, totalPlayTimeMs: Long, complete: Boolean
): ProfilePlatformBreakdown {
    val groups = games.distinctBy { it.gameKey }.groupBy { it.profilePlatform() }
    fun total(platform: GamePlatform?) = groups[platform].orEmpty().let { entries ->
        ProfilePlatformTotal(entries.size, entries.sumOf { it.totalPlayTimeMs.coerceAtLeast(0L) })
    }
    val platforms = GamePlatform.entries.associateWith(::total)
    val knownGames = platforms.values.sumOf { it.games }
    val knownTime = platforms.values.sumOf { it.playTimeMs }
    val loadedUnknown = total(null)
    val unknown = if (complete) ProfilePlatformTotal(
        games = max(loadedUnknown.games, totalGames - knownGames),
        playTimeMs = max(loadedUnknown.playTimeMs, totalPlayTimeMs - knownTime)
    ) else loadedUnknown
    return ProfilePlatformBreakdown(platforms, unknown)
}
