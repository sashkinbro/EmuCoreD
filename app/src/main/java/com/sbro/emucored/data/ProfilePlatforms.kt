package com.sbro.emucored.data

import java.net.URLDecoder
import java.util.Locale
import kotlin.math.max

internal const val PROFILE_GAME_PLATFORM_KEY = "p"

enum class ProfilePlatformFilter { ALL, NDS, N3DS, UNKNOWN }

data class ProfilePlatformTotal(val games: Int = 0, val playTimeMs: Long = 0L)
data class ProfilePlatformBreakdown(
    val ds: ProfilePlatformTotal = ProfilePlatformTotal(),
    val threeDs: ProfilePlatformTotal = ProfilePlatformTotal(),
    val unknown: ProfilePlatformTotal = ProfilePlatformTotal()
)

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

internal fun readProfilePlatform(record: Map<*, *>): GamePlatform? =
    (record[PROFILE_GAME_PLATFORM_KEY] as? String)?.let { value ->
        GamePlatform.entries.firstOrNull { it.name == value }
    } ?: resolveProfilePlatform(record["s"] as? String)

internal fun writeProfilePlatform(record: MutableMap<String, Any>, platform: GamePlatform?) {
    if (platform == null) record.remove(PROFILE_GAME_PLATFORM_KEY)
    else record[PROFILE_GAME_PLATFORM_KEY] = platform.name
}

fun PlayerGamePlayStat.profilePlatform(): GamePlatform? = platform ?: resolveProfilePlatform(serial)

fun List<PlayerGamePlayStat>.filterProfilePlatform(filter: ProfilePlatformFilter): List<PlayerGamePlayStat> =
    if (filter == ProfilePlatformFilter.ALL) this else filter { game ->
        when (filter) {
            ProfilePlatformFilter.NDS -> game.profilePlatform() == GamePlatform.NDS
            ProfilePlatformFilter.N3DS -> game.profilePlatform() == GamePlatform.N3DS
            ProfilePlatformFilter.UNKNOWN -> game.profilePlatform() == null
            ProfilePlatformFilter.ALL -> true
        }
    }

/** Incomplete public pages must never attribute unloaded games/time to a platform. */
fun profilePlatformBreakdown(
    games: List<PlayerGamePlayStat>, totalGames: Int, totalPlayTimeMs: Long, complete: Boolean
): ProfilePlatformBreakdown {
    val groups = games.distinctBy { it.gameKey }.groupBy { it.profilePlatform() }
    fun total(platform: GamePlatform?) = groups[platform].orEmpty().let { entries ->
        ProfilePlatformTotal(entries.size, entries.sumOf { it.totalPlayTimeMs.coerceAtLeast(0L) })
    }
    val ds = total(GamePlatform.NDS)
    val threeDs = total(GamePlatform.N3DS)
    val loadedUnknown = total(null)
    val unknown = if (complete) ProfilePlatformTotal(
        games = max(loadedUnknown.games, totalGames - ds.games - threeDs.games),
        playTimeMs = max(loadedUnknown.playTimeMs, totalPlayTimeMs - ds.playTimeMs - threeDs.playTimeMs)
    ) else loadedUnknown
    return ProfilePlatformBreakdown(ds, threeDs, unknown)
}
