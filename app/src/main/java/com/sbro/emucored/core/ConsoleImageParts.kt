package com.sbro.emucored.core

/** Only native Dolphin multipart conventions; arbitrary playlist paths are not followed. */
internal object ConsoleImageParts {
    private val secondaryIso = Regex("(?i).*\\.part[1-9][0-9]*\\.iso$")
    private val secondaryWbfs = Regex("(?i).*\\.wbf[1-9]$")
    fun isMultipart(name: String): Boolean = safe(name) &&
        (name.endsWith(".wbfs", true) || name.endsWith(".part0.iso", true))

    fun isSecondary(name: String): Boolean =
        secondaryIso.matches(name) || secondaryWbfs.matches(name)

    fun parts(name: String, available: List<String>): List<String>? {
        if (!isMultipart(name)) return null
        val wbfs = name.endsWith(".wbfs", true)
        val stem = name.dropLast(if (wbfs) 5 else 10)
        val entries = available.filter(::safe).groupBy { it.lowercase(java.util.Locale.ROOT) }
        val result = mutableListOf(name)
        var gap = false
        for (index in 1..(if (wbfs) 9 else 1024)) {
            val expected = stem + if (wbfs) ".wbf$index" else ".part$index.iso"
            val matches = entries[expected.lowercase(java.util.Locale.ROOT)].orEmpty()
            if (matches.size > 1 || (gap && matches.isNotEmpty())) return null
            if (matches.isEmpty()) gap = true else result += matches.single()
        }
        return result.takeIf { wbfs || it.size > 1 }
    }

    fun nativeName(name: String, index: Int): String {
        require(isMultipart(name) && index >= 0)
        return if (name.endsWith(".wbfs", true)) {
            require(index <= 9)
            if (index == 0) "image.wbfs" else "image.wbf$index"
        } else "image.part$index.iso"
    }

    private fun safe(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it == '\u0000' }
}
