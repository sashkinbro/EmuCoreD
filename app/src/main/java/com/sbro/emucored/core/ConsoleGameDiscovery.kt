// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.content.Context
import androidx.core.net.toUri
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.data.GamePlatform
import java.io.File

/** Disc identities come from the real volume parser, including compressed RVZ/WIA images. */
object ConsoleGameDiscovery {
    fun identify(context: Context, path: String, displayName: String = DocumentPathResolver.getDisplayName(context, path)): GamePlatform? {
        val candidates = ConsoleGameFormats.candidatesForFileName(displayName)
        val hasDolphinCandidate = candidates.any { it.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN }
        val detected = if (hasDolphinCandidate) probeDisc(context, path) else null
        val platform = when {
            detected != null -> GamePlatform.valueOf(detected.name)
            displayName.substringAfterLast('.').equals("elf", true) ->
                if (isArmElf(context, path)) GamePlatform.N3DS else null
            hasDolphinCandidate -> null
            else -> GamePlatform.fromFileName(displayName)
        }
        if (platform != null) GamePlatform.remember(path, platform)
        return platform
    }

    private fun isArmElf(context: Context, path: String): Boolean = runCatching {
        val stream = if (path.startsWith("content://")) context.contentResolver.openInputStream(path.toUri())
            else if (path.startsWith("file://")) File(requireNotNull(path.toUri().path)).inputStream()
            else File(path.removePrefix("!")).inputStream()
        stream?.use { input ->
            val header = ByteArray(20)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count <= 0) return@use false
                offset += count
            }
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) return@use false
            val low = header[18].toInt() and 255
            val high = header[19].toInt() and 255
            val machine = if (header[5].toInt() == 2) (low shl 8) or high else low or (high shl 8)
            machine == 40
        } == true
    }.getOrDefault(false)

    private fun probeDisc(context: Context, path: String): com.sbro.emucored.core.platform.ConsolePlatform? {
        val local = if (path.startsWith("file://")) path.toUri().path.orEmpty() else path.removePrefix("!")
        if (!local.startsWith("content://") && File(local).let { it.isFile && it.canRead() })
            return runCatching { DolphinSessionEngine.probePlatform(local) }.getOrNull()
        val uri = if (path.startsWith("content://")) path.toUri()
            else DocumentPathResolver.findAccessibleTreeUriForRawPath(context, local) ?: return null
        // The descriptor stays alive throughout native parsing. No multi-GB cache copy
        // is needed just to discover a console during a library scan.
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                DolphinSessionEngine.probePlatform("/proc/self/fd/${it.fd}")
            }
        }.getOrNull()
    }
}
