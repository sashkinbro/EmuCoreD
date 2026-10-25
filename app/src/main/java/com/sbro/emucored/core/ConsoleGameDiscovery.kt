// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.content.Context
import androidx.core.net.toUri
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.data.GamePlatform
import java.io.File

/** Disc identities come from the real volume parser, including compressed RVZ/WIA images. */
object ConsoleGameDiscovery {
    fun identify(
        context: Context, path: String,
        displayName: String = DocumentPathResolver.getDisplayName(context, path),
        siblingDocuments: List<Pair<String, String>>? = null,
        checkActive: () -> Unit = {}
    ): GamePlatform? {
        checkActive()
        val candidates = ConsoleGameFormats.candidatesForFileName(displayName)
        val hasDolphinCandidate = candidates.any { it.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN }
        val detected = if (hasDolphinCandidate) probeDisc(context, path, displayName, siblingDocuments, checkActive) else null
        val platform = when {
            detected != null -> GamePlatform.valueOf(detected.name)
            displayName.substringAfterLast('.').equals("elf", true) ->
                if (isArmElf(context, path)) GamePlatform.N3DS else null
            // Keep unambiguous Dolphin extensions (for example .gcm or .wad)
            // discoverable even if the native volume probe cannot open the
            // image; .iso and .rvz remain ambiguous and still require a probe.
            hasDolphinCandidate -> candidates.singleOrNull()?.let { GamePlatform.valueOf(it.name) }
            else -> GamePlatform.fromFileName(displayName)
        }
        checkActive()
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

    private fun probeDisc(
        context: Context,
        path: String,
        displayName: String,
        siblings: List<Pair<String, String>>?,
        checkActive: () -> Unit
    ): com.sbro.emucored.core.platform.ConsolePlatform? {
        return runCatching {
            // Dolphin can open a single SAF document directly through the app's
            // ContentResolver. Only split images need the named sibling stage.
            if (!ConsoleImageParts.isMultipart(displayName)) {
                checkActive()
                return@runCatching DolphinSessionEngine.probePlatform(path)
            }
            // The probe needs the same sibling namespace and ownership as boot:
            // a bare FD loses WBFS and .part0.iso companion names.
            ConsoleImageAccess.prepareForProbe(context, path, siblings, checkActive)?.use {
                checkActive()
                DolphinSessionEngine.probePlatform(it.path)
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
    }
}
