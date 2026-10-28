// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Stable, unambiguous identity for an archive and one specific game within it. */
internal data class ArchiveGameIdentity(val archive: String, val entry: String) {
    fun encode(): String {
        require(validComponent(archive) && validComponent(entry))
        val encoded = PREFIX + encodePart(archive) + ":" + encodePart(entry)
        require(encoded.length <= MAX_IDENTITY_LENGTH)
        return encoded
    }

    companion object {
        private const val PREFIX = "gamezip:v1:"
        private const val MAX_IDENTITY_LENGTH = 32_768
        private fun validComponent(value: String) = value.isNotBlank() && '\u0000' !in value &&
            StandardCharsets.UTF_8.newEncoder().canEncode(value)
        private fun encodePart(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        fun parse(value: String): ArchiveGameIdentity? {
            if (!value.startsWith(PREFIX) || value.length > MAX_IDENTITY_LENGTH) return null
            val parts = value.removePrefix(PREFIX).split(':')
            if (parts.size != 2) return null
            fun decode(part: String): String? = runCatching {
                require(part.isNotEmpty() && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
                val bytes = Base64.getUrlDecoder().decode(part)
                val result = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                require(validComponent(result) && encodePart(result) == part)
                result
            }.getOrNull()
            return ArchiveGameIdentity(decode(parts[0]) ?: return null, decode(parts[1]) ?: return null)
        }
    }
}
