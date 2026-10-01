package com.sbro.emucored.core

data class GameMetadata(
    val title: String,
    val serial: String? = null,
    val serialWithCrc: String? = null
)
