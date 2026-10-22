package com.sbro.emucored.core

import java.util.Locale

object NdsGameFormats {
    val extensions = setOf("nds", "srl", "dsi")

    fun isSupportedName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return fileName.substringAfterLast('.', "") in extensions
    }
}