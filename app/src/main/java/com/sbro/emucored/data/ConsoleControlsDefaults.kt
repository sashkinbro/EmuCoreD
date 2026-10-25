package com.sbro.emucored.data

import com.sbro.emucored.core.platform.ConsolePlatform
import org.json.JSONObject

/** Layout identity follows the running Wii attachment, independently of pending boot options. */
data class ConsoleControlsScope(val platform: ConsolePlatform, val wiiProfile: String = "nunchuk") {
    val normalizedWiiProfile: String = wiiProfile.takeIf { it in setOf("remote", "nunchuk", "classic") } ?: "nunchuk"
    val key: String get() = if (platform == ConsolePlatform.WII) "wii.$normalizedWiiProfile" else platform.id

    companion object {
        val all: List<ConsoleControlsScope> = ConsolePlatform.entries.flatMap { platform ->
            if (platform == ConsolePlatform.WII) listOf("remote", "nunchuk", "classic").map { ConsoleControlsScope(platform, it) }
            else listOf(ConsoleControlsScope(platform))
        }
    }
}

data class ConsoleControlsProfile(
    val layout: TouchControlsLayoutProfile,
    val customControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty
)

object ConsoleControlsDefaults {
    /** Unsupported controls remain stored but hidden, so switching consoles never removes user data. */
    fun layout(scope: ConsoleControlsScope): TouchControlsLayoutProfile {
        val basic = setOf("a", "b", "select", "start", "dpad_cluster")
        val visible = when (scope.platform) {
            ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES -> basic
            ConsolePlatform.GBA -> basic + setOf("l", "r")
            ConsolePlatform.SNES -> basic + setOf("x", "y", "l", "r")
            ConsolePlatform.NDS -> basic - "dpad_cluster" +
                setOf("x", "y", "l", "r", "dpad_up", "dpad_down", "dpad_left", "dpad_right")
            ConsolePlatform.GAMECUBE -> basic + setOf("x", "y", "l", "r", "left_stick", "right_stick")
            ConsolePlatform.WII -> basic + setOf("x", "y", "home", "right_stick") + when (scope.normalizedWiiProfile) {
                "remote" -> emptySet()
                "classic" -> setOf("l", "r", "zl", "zr", "left_stick")
                else -> setOf("l", "r", "left_stick")
            }
            // Both Circle Pad/C-stick inputs are part of the 3DS control surface. The
            // right analog is used by the C-stick, Circle Pad Pro, and New 3DS models.
            ConsolePlatform.N3DS -> basic +
                setOf("x", "y", "home", "touch", "l", "r", "zl", "zr", "left_stick", "right_stick")
        }
        val controls = AppPreferences.defaultOverlayControlLayouts().mapValues { (id, layout) ->
            layout.copy(visible = id in visible)
        }.toMutableMap()
        if (scope.platform == ConsolePlatform.GAMECUBE) {
            controls["a"] = controls.getValue("a").copy(scale = 120)
            controls["right_stick"] = controls.getValue("right_stick").copy(scale = 90)
        }
        return TouchControlsLayoutProfile(controlLayouts = controls)
    }

    /** Explicit game/console choices win. A legacy custom layout is inherited exactly, never migrated. */
    fun resolve(
        scope: ConsoleControlsScope,
        legacyLayout: TouchControlsLayoutProfile = TouchControlsLayoutProfile(),
        legacyCustom: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
        saved: Map<String, ConsoleControlsProfile> = emptyMap(),
        perGameLayout: TouchControlsLayoutProfile? = null,
        perGameCustom: CustomTouchControlLibrary? = null
    ): ConsoleControlsProfile {
        val inherited = saved[scope.key] ?: ConsoleControlsProfile(
            if (legacyLayout.isFactoryDefault()) layout(scope) else legacyLayout,
            legacyCustom
        )
        if (perGameLayout == null && perGameCustom == null) return inherited
        return ConsoleControlsProfile(perGameLayout ?: inherited.layout, perGameCustom ?: inherited.customControls)
    }

    /**
     * DataStore represents an untouched global layout as an empty control map, while
     * [TouchControlsLayoutProfile] carries the factory map. Both shapes mean "never
     * customized" and must select the console-specific defaults instead of inheriting
     * the shared map where controls such as the right stick are disabled.
     */
    private fun TouchControlsLayoutProfile.isFactoryDefault(): Boolean =
        this == TouchControlsLayoutProfile() ||
            (controlLayouts.isEmpty() &&
                copy(controlLayouts = AppPreferences.defaultOverlayControlLayouts()) == TouchControlsLayoutProfile())
}

/** The same bounded representation is used by DataStore and settings backup/restore. */
object ConsoleControlsProfiles {
    fun encode(profiles: Map<String, ConsoleControlsProfile>): String = JSONObject().apply {
        ConsoleControlsScope.all.forEach { scope -> profiles[scope.key]?.let { profile ->
            put(scope.key, JSONObject().put("layout", profile.layout.toJson())
                .put("customControls", profile.customControls.encode()))
        } }
    }.toString()

    fun decode(raw: String?): Map<String, ConsoleControlsProfile> {
        if (raw.isNullOrBlank() || raw.length > 2_000_000) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return ConsoleControlsScope.all.mapNotNull { scope -> runCatching {
            val record = json.optJSONObject(scope.key) ?: return@runCatching null
            val layout = record.getJSONObject("layout").toTouchControlsLayoutProfile()
            val custom = CustomTouchControlLibrary.decodeOrNull(record.optString("customControls")) ?: return@runCatching null
            scope.key to ConsoleControlsProfile(layout, custom)
        }.getOrNull() }.toMap()
    }
}

fun OverlayLayoutSnapshot.forConsoleControls(scope: ConsoleControlsScope): OverlayLayoutSnapshot {
    val profile = ConsoleControlsDefaults.resolve(scope, toTouchControlsLayoutProfile(), customTouchControls, consoleProfiles)
    val layout = profile.layout
    return copy(dpadOffset = layout.dpadOffset, lstickOffset = layout.lstickOffset, rstickOffset = layout.rstickOffset,
        actionOffset = layout.actionOffset, lbtnOffset = layout.lbtnOffset, rbtnOffset = layout.rbtnOffset,
        centerOffset = layout.centerOffset, stickScale = layout.stickScale, controlLayouts = layout.controlLayouts,
        customTouchControls = profile.customControls)
}
