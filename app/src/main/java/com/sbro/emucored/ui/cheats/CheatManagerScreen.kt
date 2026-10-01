package com.sbro.emucored.ui.cheats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.core.NativeApp
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.ContentLibraryRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.N3dsCheatRepository
import com.sbro.emucored.ui.common.LibraryGamePicker
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import com.sbro.emucored.ui.theme.neon.neonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 3DS cheat manager: Gateway cheat files keyed by the 16-digit title id. */
@Composable
fun CheatManagerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { AppPreferences(context) }
    val repository = remember(context) { N3dsCheatRepository(context) }
    val libraryRepository = remember(context) { ContentLibraryRepository(context) }
    val cheatsEnabled by preferences.enableCheats.collectAsState(initial = false)

    var games by remember { mutableStateOf<List<GameItem>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<N3dsCheatRepository.Section>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val selectedGame = games.firstOrNull { it.path == selectedPath }
    val titleId = selectedGame?.let(repository::titleIdOf)
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val importSuccess = stringResource(R.string.cheat_manager_import_success)
    val importFailed = stringResource(R.string.cheat_manager_import_failed)
    val installSuccess = stringResource(R.string.cheat_catalog_install_success)
    val installFailed = stringResource(R.string.cheat_catalog_install_failed)
    val deleteSuccess = stringResource(R.string.cheat_manager_delete_success)
    val noTitleId = stringResource(R.string.cheat_manager_crc_unavailable, selectedGame?.title.orEmpty())

    fun applyToCore() {
        titleId?.let { NativeApp.loadCheats(repository.cheatFile(it).absolutePath) }
    }

    fun reloadSections() {
        titleId?.let { sections = repository.read(it) }
    }

    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { libraryRepository.loadGames() }
        selectedPath = games.firstOrNull()?.path
    }
    LaunchedEffect(selectedPath) {
        sections = withContext(Dispatchers.IO) {
            titleId?.let { repository.read(it) } ?: emptyList()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val id = titleId
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { repository.import(id, uri) }
            if (ok) {
                reloadSections()
                applyToCore()
            }
            statusMessage = if (ok) importSuccess else importFailed
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = bottomInset)
    ) {
        ScreenTopBar(
            title = stringResource(R.string.cheat_manager_title),
            onBackClick = onBackClick
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = 12.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                LibraryGamePicker(
                    games = games,
                    selectedPath = selectedPath,
                    onSelected = { game -> selectedPath = game.path }
                )
            }

            item {
                Surface(shape = neonShape(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.cheat_manager_enable),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.cheat_manager_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cheatsEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { preferences.setEnableCheats(enabled) }
                                if (enabled) applyToCore()
                            }
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val id = titleId ?: return@OutlinedButton
                            scope.launch {
                                busy = true
                                val ok = withContext(Dispatchers.IO) {
                                    repository.installFromCatalog(id)
                                }
                                if (ok) {
                                    reloadSections()
                                    applyToCore()
                                }
                                statusMessage = if (ok) installSuccess else installFailed
                                busy = false
                            }
                        },
                        enabled = !busy && titleId != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Text(stringResource(R.string.cheat_catalog_title))
                    }
                    OutlinedButton(
                        onClick = { importPicker.launch(arrayOf("text/plain", "*/*")) },
                        enabled = !busy && titleId != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.cheat_manager_import_pnach))
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !busy && titleId != null && sections.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Text(stringResource(R.string.cheat_manager_delete))
                }
            }

            statusMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (titleId == null) {
                item {
                    Text(
                        text = noTitleId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (sections.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.cheat_catalog_no_packs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.cheat_manager_installed),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(sections) { section ->
                    Surface(shape = neonShape(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = section.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = section.enabled,
                                onCheckedChange = { checked ->
                                    val id = titleId ?: return@Switch
                                    val updated = sections.map {
                                        if (it.name == section.name) it.copy(enabled = checked) else it
                                    }
                                    sections = updated
                                    scope.launch {
                                        withContext(Dispatchers.IO) { repository.write(id, updated) }
                                        applyToCore()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.cheat_manager_delete_title)) },
            text = { Text(stringResource(R.string.cheat_manager_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val id = titleId ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            repository.delete(id)
                            NativeApp.clearCheats()
                        }
                        reloadSections()
                        statusMessage = deleteSuccess
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
