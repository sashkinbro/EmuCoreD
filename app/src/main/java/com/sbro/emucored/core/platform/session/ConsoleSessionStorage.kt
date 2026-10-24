// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.platform.session

import com.sbro.emucored.core.platform.ConsolePlatform
import java.io.File
import java.security.MessageDigest

object ConsoleSessionStorage {
    fun root(filesDirectory: File): File = File(filesDirectory, "consoles")
    fun stateRoot(filesDirectory: File): File = File(root(filesDirectory), "states")
    fun identityKey(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun stateFile(filesDirectory: File, platform: ConsolePlatform, identity: String, slot: Int): File {
        require(slot in 0..10) { "Unsupported save slot" }
        val key = identityKey(identity)
        // Unique basenames also prevent collisions when exporting several games into a ZIP.
        return File(stateRoot(filesDirectory), "${platform.id}/$key/${platform.id}_${key}_slot$slot.ecs")
    }
}
