package com.sbro.emucored.data.drive

/** User saves only. ROMs, firmware, keys, caches and Dolphin NAND content are excluded. */
internal object ConsoleBackupPaths {
    internal val framePlatforms = setOf("n64", "gba", "gb", "gbc", "nes", "snes")
    private val platforms = framePlatforms + setOf("gamecube", "wii")
    private val hash = Regex("[0-9a-f]{64}")
    private val dsState = Regex("slot(\\d{1,2})\\.dss(?:\\.png|\\.json)?")

    fun category(prefix: String): String = when (prefix) {
        "console-states", "ds-states" -> "save-states"
        "console-data", "console-dolphin", "ds-saves" -> "memory-cards"
        else -> prefix
    }

    fun allowed(name: String): Boolean {
        val p = name.split('/')
        if (p.any { it.isBlank() || it.startsWith('.') || '\\' in it || ':' in it }) return false
        return when (p.firstOrNull()) {
            "console-states" -> p.size == 4 && p[1] in platforms && p[2].matches(hash) &&
                p[3].matches(Regex("${p[1]}_${p[2]}_slot(?:[0-9]|10)\\.ecs(?:\\.png|\\.json)?"))
            "ds-states" -> p.size == 3 && p[1].matches(Regex("[A-Za-z0-9._-]{1,120}")) &&
                dsState.matchEntire(p[2])?.groupValues?.get(1)?.toIntOrNull()?.let { it in 0..10 } == true
            "ds-saves" -> p.size == 2 && p[1].matches(Regex("[A-Za-z0-9._-]{1,128}\\.sav"))
            "console-data" -> p.size == 4 && p[1] in framePlatforms && p[2].matches(hash) &&
                (p[3] in setOf("battery.sav", "battery.sav.fds.ups", "game.srm", "game.rtc", "game.sav", "game.ram") ||
                    (p[1] == "n64" && p[3] == "battery.srm"))
            "console-dolphin" -> when {
                p.size >= 3 && p[1] == "GC" -> p.last().substringAfterLast('.').lowercase() in setOf("raw", "gci", "gcs")
                p.size >= 7 && p[1] == "Wii" && p[2] == "title" &&
                    p[3] in setOf("00010000", "00010001", "00010004") &&
                    p[4].matches(Regex("[0-9a-fA-F]{8}")) && p[5] == "data" -> !p.last().endsWith(".tmp")
                else -> false
            }
            else -> false
        }
    }
}
