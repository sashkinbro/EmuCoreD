package com.sbro.emucored.ui.textures

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucored.R
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.ui.common.AppAlertDialog
import com.sbro.emucored.ui.common.LibraryGamePicker
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import com.sbro.emucored.ui.theme.neon.neonButtonShape

/** The same manager handles global library entry and an explicitly selected game. */
@Composable
fun TextureManagerScreen(onBackClick: () -> Unit, initialGamePath: String? = null) {
    val model: TextureManagerViewModel = viewModel()
    TextureManagerContent(model, onBackClick, initialGamePath)
}

@Composable
internal fun TextureManagerContent(model: TextureManagerViewModel, onBackClick: () -> Unit, initialGamePath: String?) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // Document pickers may recreate this screen; retain the exact requested game, not the current selection.
    var importing by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var exporting by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var deleting by remember { mutableStateOf<TextureGameSelection?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selected = importing?.let(::restoreTextureSelection)
        importing = null
        if (uri != null && selected != null) model.importZip(uri, selected)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val selected = exporting?.let(::restoreTextureSelection)
        exporting = null
        if (uri != null && selected != null) model.export(uri, selected)
    }
    LaunchedEffect(initialGamePath) { model.initialize(initialGamePath) }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show(); model.consumeMessage() }
    }
    val selected = state.selection
    val canChange = selected != null && !state.busy && !state.loading && !state.gameBusy
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(navigationBarsHorizontalPaddingValues()),
            contentPadding = PaddingValues(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ScreenTopBar(stringResource(R.string.shell_texture_manager), onBackClick,
                    modifier = Modifier.padding(top = appScreenTopPadding()))
            }
            item { Text(stringResource(R.string.texture_manager_help), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                LibraryGamePicker(state.games, state.selectedPath, { model.select(it.path) })
            }
            if (state.gameBusy) item { Text(stringResource(R.string.texture_manager_stop), color = MaterialTheme.colorScheme.error) }
            if (state.loading || state.busy) item { CircularProgressIndicator() }
            if (state.identityFailed) item { Text(stringResource(R.string.texture_manager_identity_failed), color = MaterialTheme.colorScheme.error) }
            if (selected != null) {
                item {
                    Text(selected.target.id, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (selected.game.platform == GamePlatform.N3DS)
                        R.string.texture_manager_scope_global else R.string.texture_manager_scope_game),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.texture_manager_enable), modifier = Modifier.weight(1f))
                        Switch(checked = state.installed?.enabled ?: false, enabled = canChange && state.installed != null,
                            onCheckedChange = { model.setEnabled(selected, it) })
                    }
                }
                item {
                    val installed = state.installed
                    if (installed != null) Text(if (installed.stats.textureCount == 0) stringResource(R.string.texture_manager_empty)
                        else stringResource(R.string.texture_manager_count, installed.stats.textureCount,
                            Formatter.formatFileSize(context, installed.stats.totalBytes)))
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canChange, shape = neonButtonShape(), onClick = {
                            importing = saveTextureSelection(selected)
                            importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        }) { Text(stringResource(R.string.texture_manager_import)) }
                        OutlinedButton(enabled = canChange && (state.installed?.stats?.textureCount ?: 0) > 0,
                            shape = neonButtonShape(), onClick = {
                                exporting = saveTextureSelection(selected)
                                exporter.launch("${selected.target.id}-textures.zip")
                            }) { Text(stringResource(R.string.texture_manager_export)) }
                        OutlinedButton(enabled = canChange && (state.installed?.stats?.fileCount ?: 0) > 0,
                            shape = neonButtonShape(), onClick = { deleting = selected }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
                items(state.installed?.files.orEmpty(), key = { "installed:$it" }) { file ->
                    Text(file, style = MaterialTheme.typography.bodySmall)
                }
                // The existing online catalog identifies only 3DS title IDs.
                if (selected.game.platform == GamePlatform.N3DS) {
                    item {
                        OutlinedButton(enabled = canChange, shape = neonButtonShape(), onClick = model::loadOnline) {
                            Text(stringResource(R.string.content_compatible_game_version_section))
                        }
                        if (state.onlineLoaded && state.online.isEmpty()) Text(stringResource(R.string.content_not_compatible))
                    }
                    items(state.online, key = { "online:${it.id}" }) { pack ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pack.name, style = MaterialTheme.typography.titleMedium)
                            Text(pack.description)
                            Text(stringResource(R.string.content_authors, pack.authors.joinToString(", ")))
                            Text(stringResource(R.string.settings_about_core_license, pack.license))
                            Row {
                                Button(enabled = canChange, shape = neonButtonShape(),
                                    onClick = { model.installOnline(selected, pack) }) {
                                    Text(stringResource(R.string.content_download_install))
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { uriHandler.openUri(pack.sourceUrl) }) { Text(stringResource(R.string.content_source)) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { requested ->
        AppAlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.texture_manager_delete_title)) },
            text = { Text(stringResource(R.string.texture_manager_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(requested) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

internal fun saveTextureSelection(selection: TextureGameSelection): ArrayList<String> = arrayListOf(
    selection.game.path, selection.game.platform.name, selection.game.title, selection.target.id)

internal fun restoreTextureSelection(values: List<String>): TextureGameSelection? = runCatching {
    require(values.size == 4 && values[0].isNotBlank())
    val platform = GamePlatform.valueOf(values[1])
    require(com.sbro.emucored.data.textures.TexturePackIdentity.supports(platform))
    val game = com.sbro.emucored.data.GameItem(values[2], values[0], "", 0, 0, platform = platform)
    TextureGameSelection(game, com.sbro.emucored.data.textures.TexturePackTarget(platform.console, values[3]))
}.getOrNull()
