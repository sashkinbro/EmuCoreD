package com.sbro.emucored.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.core.MultiplayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Azahar Room multiplayer controls shown in the settings Network tab. */
@Composable
fun MultiplayerSettingsSection() {
    val scope = rememberCoroutineScope()
    var joined by remember { mutableStateOf(false) }
    var hosting by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(listOf<String>()) }
    var publicRooms by remember { mutableStateOf(listOf<MultiplayerController.PublicRoom>()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showHostDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val chat = remember { mutableStateListOf<String>() }
    val errorLabel = stringResource(R.string.settings_multiplayer_error)

    fun refreshStatus() {
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                Triple(
                    MultiplayerController.isJoined(),
                    MultiplayerController.isHosting(),
                    MultiplayerController.roomInfo()
                )
            }
            joined = status.first
            hosting = status.second
            roomName = status.third?.name.orEmpty()
            members = status.third?.members.orEmpty()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { MultiplayerController.init() }
        refreshStatus()
        while (isActive) {
            val event = withContext(Dispatchers.IO) { MultiplayerController.pollEvent() }
            if (event != null) {
                if (event.message.isNotBlank()) chat.add(event.message)
                if (event.status == 1 || event.status >= 5 && event.status <= 15) {
                    errorText = event.message.ifBlank { errorLabel }
                }
                refreshStatus()
            }
            delay(500)
        }
    }

    SettingsSection(title = stringResource(R.string.settings_multiplayer_title)) {
        SettingsItem(
            icon = Icons.Rounded.Language,
            label = stringResource(R.string.settings_multiplayer_title),
            value = when {
                hosting -> stringResource(R.string.settings_multiplayer_status_hosting)
                joined -> stringResource(R.string.settings_multiplayer_status_joined)
                else -> stringResource(R.string.settings_multiplayer_status_offline)
            },
            onClick = {}
        )
        errorText?.let { message ->
            SettingsItem(
                icon = Icons.Rounded.Language,
                label = stringResource(R.string.settings_multiplayer_error),
                value = message,
                onClick = { errorText = null }
            )
        }

        if (!joined) {
            SettingsItem(
                icon = Icons.Rounded.Add,
                label = stringResource(R.string.settings_multiplayer_host),
                value = stringResource(R.string.settings_multiplayer_hosted_hint),
                onClick = { showHostDialog = true }
            )
            SettingsItem(
                icon = Icons.Rounded.CallReceived,
                label = stringResource(R.string.settings_multiplayer_join),
                value = "",
                onClick = { showJoinDialog = true }
            )
        } else {
            SettingsItem(
                icon = Icons.Rounded.ExitToApp,
                label = stringResource(R.string.settings_multiplayer_leave),
                value = roomName,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { MultiplayerController.leaveRoom() }
                        chat.clear()
                        refreshStatus()
                    }
                }
            )
            if (members.isNotEmpty()) {
                SettingsItem(
                    icon = Icons.Rounded.Language,
                    label = stringResource(R.string.settings_multiplayer_members),
                    value = members.joinToString(", "),
                    onClick = {}
                )
            }
        }

        SettingsItem(
            icon = Icons.Rounded.Refresh,
            label = stringResource(R.string.settings_multiplayer_refresh),
            value = stringResource(R.string.settings_multiplayer_public_rooms),
            onClick = {
                scope.launch {
                    publicRooms = withContext(Dispatchers.IO) { MultiplayerController.publicRooms() }
                }
            }
        )
        if (publicRooms.isEmpty()) {
            SettingsItem(
                icon = Icons.Rounded.Refresh,
                label = stringResource(R.string.settings_multiplayer_public_rooms),
                value = stringResource(R.string.settings_multiplayer_no_rooms),
                onClick = {}
            )
        } else {
            publicRooms.forEach { room ->
                SettingsItem(
                    icon = Icons.Rounded.Language,
                    label = room.name,
                    value = "${room.owner} - ${room.gameName} (${room.maxPlayers})",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                MultiplayerController.joinRoom(
                                    room.ip,
                                    room.port,
                                    "EmuCoreD",
                                    ""
                                )
                            }
                            refreshStatus()
                        }
                    }
                )
            }
        }

        if (joined) {
            var message by remember { mutableStateOf("") }
            SettingsItem(
                icon = Icons.Rounded.Send,
                label = stringResource(R.string.settings_multiplayer_message),
                value = chat.lastOrNull().orEmpty(),
                onClick = {}
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_multiplayer_message)) }
                )
                TextButton(onClick = {
                    val outgoing = message.trim()
                    if (outgoing.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { MultiplayerController.sendChat(outgoing) }
                        }
                        chat.add(outgoing)
                        message = ""
                    }
                }) {
                    Text(stringResource(R.string.settings_multiplayer_send))
                }
            }
        }
    }

    if (showHostDialog) {
        RoomFormDialog(
            title = stringResource(R.string.settings_multiplayer_host),
            confirmLabel = stringResource(R.string.settings_multiplayer_host_action),
            onDismiss = { showHostDialog = false },
            onConfirm = { room, password, port, maxPlayers ->
                showHostDialog = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        MultiplayerController.createRoom(
                            "0.0.0.0",
                            port,
                            "EmuCoreD",
                            "",
                            0L,
                            password,
                            room,
                            maxPlayers
                        )
                    }
                    if (result != 0) {
                        errorText = errorLabel
                    }
                    refreshStatus()
                }
            }
        )
    }

    if (showJoinDialog) {
        RoomFormDialog(
            title = stringResource(R.string.settings_multiplayer_join),
            confirmLabel = stringResource(R.string.settings_multiplayer_join_action),
            onDismiss = { showJoinDialog = false },
            onConfirm = { address, password, port, _ ->
                showJoinDialog = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        MultiplayerController.joinRoom(address, port, "EmuCoreD", password)
                    }
                    if (result != 0) {
                        errorText = errorLabel
                    }
                    refreshStatus()
                }
            }
        )
    }
}

@Composable
private fun RoomFormDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, Int) -> Unit
) {
    var first by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("45454") }
    var maxPlayers by remember { mutableStateOf("8") }
    val isHost = title == stringResource(R.string.settings_multiplayer_host)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                if (isHost) R.string.settings_multiplayer_room_name
                                else R.string.settings_multiplayer_address
                            )
                        )
                    }
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.settings_multiplayer_password)) }
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.settings_multiplayer_port)) }
                )
                if (isHost) {
                    OutlinedTextField(
                        value = maxPlayers,
                        onValueChange = { maxPlayers = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.settings_multiplayer_max_players)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        first.trim(),
                        password,
                        port.toIntOrNull() ?: 45454,
                        maxPlayers.toIntOrNull() ?: 8
                    )
                },
                enabled = first.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}


