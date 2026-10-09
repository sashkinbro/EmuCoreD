// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Emulated software keyboard applet. The core asks the frontend for text and
 * waits for the returned string and pressed button.
 */
@Keep
object SoftwareKeyboard {
    @Keep
    class KeyboardConfig {
        @JvmField
        var buttonConfig: Int = 0
        @JvmField
        var maxTextLength: Int = 0
        @JvmField
        var multilineMode: Boolean = false
        @JvmField
        var hintText: String = ""
        @JvmField
        var buttonText: Array<String> = emptyArray()
    }

    @Keep
    class KeyboardData {
        @JvmField
        var text: String = ""
        @JvmField
        var button: Int = 0
    }

    @Keep
    class ValidationError {
        @JvmField
        var text: String = ""
    }

    private var handler: ((KeyboardConfig) -> KeyboardData)? = null

    fun setHandler(handler: (KeyboardConfig) -> KeyboardData) {
        this.handler = handler
    }

    @JvmStatic
    fun execute(config: KeyboardConfig): KeyboardData =
        handler?.invoke(config) ?: KeyboardData()

    @JvmStatic
    fun showError(error: String) = Unit
}
