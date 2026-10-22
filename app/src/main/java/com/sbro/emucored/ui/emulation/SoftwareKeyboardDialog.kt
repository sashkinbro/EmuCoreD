// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.emulation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sbro.emucored.R
import com.sbro.emucored.core.SoftwareKeyboard
import com.sbro.emucored.ui.theme.neon.neonShape
import kotlinx.coroutines.delay

@Composable
fun SoftwareKeyboardDialog(request: SoftwareKeyboard.Request) {
    val config = request.config
    var text by remember(request) { mutableStateOf("") }
    var inlineError by remember(request) { mutableStateOf<String?>(null) }
    val coreError by SoftwareKeyboard.error.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val hint = config.hintText.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.software_keyboard_hint)
    val fixedLengthError = stringResource(
        R.string.software_keyboard_fixed_length,
        config.maxTextLength
    )
    val maxLengthError = stringResource(
        R.string.software_keyboard_max_length,
        config.maxTextLength
    )
    val blankInputError = stringResource(R.string.software_keyboard_blank_input)
    val emptyInputError = stringResource(R.string.software_keyboard_empty_input)
    val invalidInputError = stringResource(R.string.software_keyboard_invalid_input)

    fun messageFor(error: SoftwareKeyboard.ValidationError): String = when (error) {
        SoftwareKeyboard.ValidationError.FixedLengthRequired -> fixedLengthError
        SoftwareKeyboard.ValidationError.MaxLengthExceeded -> maxLengthError
        SoftwareKeyboard.ValidationError.BlankInputNotAllowed -> blankInputError
        SoftwareKeyboard.ValidationError.EmptyInputNotAllowed -> emptyInputError
        else -> invalidInputError
    }

    fun submit() {
        val error = SoftwareKeyboard.ValidateInput(text)
        if (error != SoftwareKeyboard.ValidationError.None) {
            inlineError = messageFor(error)
            return
        }
        keyboardController?.hide()
        SoftwareKeyboard.submit(text, config.buttonConfig)
    }

    fun updateText(candidate: String) {
        val limited = if (config.maxTextLength > 0 && candidate.length > config.maxTextLength) {
            candidate.take(config.maxTextLength)
        } else {
            candidate
        }
        if (SoftwareKeyboard.ValidateFilters(limited) != SoftwareKeyboard.ValidationError.None) {
            return
        }
        text = limited
        inlineError = null
        SoftwareKeyboard.clearError()
    }

    val okLabel = config.buttonText.getOrNull(2)?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.software_keyboard_ok)
    val cancelLabel = config.buttonText.getOrNull(0)?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.software_keyboard_cancel)
    val forgotLabel = config.buttonText.getOrNull(1)?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.software_keyboard_forgot)
    val shownError = inlineError ?: coreError

    Dialog(
        onDismissRequest = { SoftwareKeyboard.cancelPending() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = neonShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Color(0xFF6688FF).copy(alpha = 0.5f)),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.software_keyboard_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { SoftwareKeyboard.cancelPending() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.software_keyboard_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = ::updateText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text(hint) },
                    singleLine = !config.multilineMode,
                    minLines = 1,
                    maxLines = if (config.multilineMode) 5 else 1,
                    isError = shownError != null,
                    supportingText = shownError?.let {
                        {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (config.multilineMode) ImeAction.Default else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    shape = neonShape(14.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (config.buttonConfig) {
                        SoftwareKeyboard.BUTTON_TRIPLE -> {
                            TextButton(onClick = { SoftwareKeyboard.submit("", 0) }) {
                                Text(cancelLabel)
                            }
                            TextButton(onClick = { SoftwareKeyboard.submit("", 1) }) {
                                Text(forgotLabel)
                            }
                        }

                        SoftwareKeyboard.BUTTON_DUAL -> {
                            TextButton(onClick = { SoftwareKeyboard.submit("", 0) }) {
                                Text(cancelLabel)
                            }
                        }
                    }
                    Button(
                        onClick = ::submit,
                        modifier = Modifier.padding(start = 8.dp),
                        shape = neonShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3565FF),
                            contentColor = Color.White
                        )
                    ) {
                        Text(okLabel)
                    }
                }
            }
        }
    }

    LaunchedEffect(request) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}
