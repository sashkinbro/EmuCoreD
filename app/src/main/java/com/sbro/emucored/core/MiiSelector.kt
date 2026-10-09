// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Emulated Mii selector applet. The core asks the frontend to show a picker
 * and waits for the returned selection.
 */
@Keep
object MiiSelector {
    @Keep
    class MiiSelectorConfig {
        @JvmField
        var enableCancelButton: Boolean = true
        @JvmField
        var title: String = ""
        @JvmField
        var initiallySelectedMiiIndex: Long = 0L
        @JvmField
        var miiNames: Array<String> = emptyArray()
    }

    @Keep
    class MiiSelectorData {
        @JvmField
        var returnCode: Long = 0L
        @JvmField
        var index: Int = 0
    }

    private var handler: ((MiiSelectorConfig) -> MiiSelectorData)? = null

    fun setHandler(handler: (MiiSelectorConfig) -> MiiSelectorData) {
        this.handler = handler
    }

    @JvmStatic
    fun execute(config: MiiSelectorConfig): MiiSelectorData =
        handler?.invoke(config) ?: MiiSelectorData().also { it.returnCode = 1L }
}
