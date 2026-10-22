// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Emulated software keyboard applet. The core asks the frontend for text and
 * waits for the returned string and pressed button.
 */
@Keep
object SoftwareKeyboard {
    const val BUTTON_SINGLE = 0
    const val BUTTON_DUAL = 1
    const val BUTTON_TRIPLE = 2
    const val BUTTON_NONE = 3

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
    enum class ValidationError {
        None,
        ButtonOutOfRange,
        MaxDigitsExceeded,
        AtSignNotAllowed,
        PercentNotAllowed,
        BackslashNotAllowed,
        ProfanityNotAllowed,
        CallbackFailed,
        FixedLengthRequired,
        MaxLengthExceeded,
        BlankInputNotAllowed,
        EmptyInputNotAllowed
    }

    data class Request(
        val config: KeyboardConfig,
        val result: CompletableDeferred<KeyboardData>
    )

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var handler: ((KeyboardConfig) -> KeyboardData)? = { awaitInput(it) }

    fun setHandler(handler: (KeyboardConfig) -> KeyboardData) {
        this.handler = handler
    }

    fun clearHandler() {
        handler = { awaitInput(it) }
    }

    private fun awaitInput(config: KeyboardConfig): KeyboardData {
        _request.value?.result?.complete(KeyboardData())
        val deferred = CompletableDeferred<KeyboardData>()
        _request.value = Request(config, deferred)
        return runBlocking { deferred.await() }
    }

    fun submit(text: String, button: Int) {
        val pending = _request.value ?: return
        if (_request.compareAndSet(pending, null)) {
            _error.value = null
            pending.result.complete(KeyboardData().apply {
                this.text = text
                this.button = button
            })
        }
    }

    fun cancelPending() {
        val pending = _request.value ?: return
        if (_request.compareAndSet(pending, null)) {
            _error.value = null
            pending.result.complete(KeyboardData())
        }
    }

    fun clearError() {
        _error.value = null
    }

    @JvmStatic
    fun execute(config: KeyboardConfig): KeyboardData =
        handler?.invoke(config) ?: KeyboardData()

    @JvmStatic
    fun showError(error: String) {
        _error.value = error
    }

    @Suppress("FunctionName")
    @JvmStatic
    external fun ValidateFilters(text: String): ValidationError

    @Suppress("FunctionName")
    @JvmStatic
    external fun ValidateInput(text: String): ValidationError
}
