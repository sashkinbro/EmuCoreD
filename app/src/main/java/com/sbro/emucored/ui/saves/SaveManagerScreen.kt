package com.sbro.emucored.ui.saves

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import com.sbro.emucored.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import com.sbro.emucored.R
import com.sbro.emucored.data.SaveStateEntryInfo
import com.sbro.emucored.ui.common.gamePlatformLabelResource
import com.sbro.emucored.data.SaveStateImportFormat
import com.sbro.emucored.data.SaveStateImportPreview
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.ui.common.GameCoverArt
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import com.sbro.emucored.ui.theme.neon.LocalNeonTheme
import com.sbro.emucored.ui.theme.neon.NeonSystemBanner
import com.sbro.emucored.ui.theme.neon.neonButtonShape
import com.sbro.emucored.ui.theme.neon.neonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveManagerScreen(
    gamePath: String? = null,
    gameTitle: String? = null,
    gameSerial: String? = null,
    onLoadClick: (String, Int) -> Unit,
    onBackClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { SaveStateRepository(context) }
    val scope = rememberCoroutineScope()
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()
    val backupSuccessMessage = stringResource(R.string.save_manager_backup_success)
    val backupFailureMessage = stringResource(R.string.save_manager_backup_failed)
    val restoreFailureMessage = stringResource(R.string.save_manager_restore_failed)
    val importSuccessTemplate = stringResource(R.string.save_manager_import_success)
    val importPartialTemplate = stringResource(R.string.save_manager_import_partial)
    val deleteSuccessMessage = stringResource(R.string.save_manager_delete_success)
    val deleteFailureMessage = stringResource(R.string.save_manager_delete_failed)

    var snapshot by remember(context, gamePath, gameTitle, gameSerial) {
        mutableStateOf(takePreparedSaveManager(context, gamePath, gameTitle, gameSerial))
    }
    val entries = snapshot?.entries.orEmpty()
    val previewPaths = snapshot?.previews.orEmpty()
    var isWorking by remember(gamePath, gameTitle, gameSerial) { mutableStateOf(false) }
    val isPreparingEntries = snapshot == null
    var preparationFailed by remember(gamePath, gameTitle, gameSerial) { mutableStateOf(false) }
    var refreshJob by remember(gamePath, gameTitle, gameSerial) { mutableStateOf<Job?>(null) }
    val pendingDelete = remember(gamePath, gameTitle, gameSerial) { mutableStateOf<SaveStateEntryInfo?>(null) }
    var importPreview by remember(gamePath, gameTitle, gameSerial) { mutableStateOf<SaveStateImportPreview?>(null) }
    var refreshGeneration by remember(gamePath, gameTitle, gameSerial) { mutableIntStateOf(0) }

    val isFiltered = !gamePath.isNullOrBlank()
    val screenSubtitle = remember(isFiltered, gameTitle, entries) {
        when {
            isFiltered && entries.isNotEmpty() -> entries.first().gameTitle
            isFiltered && gameTitle.isUsableDisplayTitle() -> gameTitle
            isFiltered -> ""
            else -> null
        }
    }

    fun refresh() {
        invalidatePreparedSaveManagers()
        refreshJob?.cancel()
        val generation = refreshGeneration + 1
        refreshGeneration = generation
        refreshJob = scope.launch {
            isWorking = true
            snapshot = null
            preparationFailed = false
            try {
                val prepared = loadSaveManagerSnapshot(context.applicationContext, gamePath, gameTitle, gameSerial)
                if (refreshGeneration == generation) snapshot = prepared
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (refreshGeneration == generation) preparationFailed = true
            } finally {
                if (refreshGeneration == generation) isWorking = false
            }
        }
    }

    LaunchedEffect(gamePath, gameTitle, gameSerial) {
        if (snapshot == null) refresh()
    }
    DisposableEffect(gamePath, gameTitle, gameSerial) {
        onDispose { refreshJob?.cancel() }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            val success = withContext(Dispatchers.IO) {
                repository.backupEntries(entries, uri)
            }
            isWorking = false
            Toast.makeText(
                context,
                if (success) backupSuccessMessage else backupFailureMessage,
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            val preview = runCatching {
                withContext(Dispatchers.IO) {
                    repository.analyzeImport(
                        source = uri,
                        gamePath = gamePath,
                        gameSerial = gameSerial
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            isWorking = false
            if (preview == null) {
                Toast.makeText(context, restoreFailureMessage, Toast.LENGTH_SHORT).show()
            } else {
                importPreview = preview
            }
        }
    }

    importPreview?.let { preview ->
        SaveImportPreviewDialog(
            preview = preview,
            isFiltered = isFiltered,
            onDismiss = {
                repository.discardImport(preview)
                importPreview = null
            },
            onConfirm = {
                importPreview = null
                scope.launch {
                    isWorking = true
                    val result = withContext(Dispatchers.IO) { repository.importStates(preview) }
                    isWorking = false
                    val message = when {
                        result.importedCount == 0 -> restoreFailureMessage
                        result.failedCount > 0 -> String.format(
                            Locale.getDefault(),
                            importPartialTemplate,
                            result.importedCount,
                            result.failedCount
                        )
                        else -> String.format(Locale.getDefault(), importSuccessTemplate, result.importedCount)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        )
    }

    pendingDelete.value?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            title = { Text(stringResource(R.string.save_manager_delete_confirm_title)) },
            text = {
                Text(
                    if (entry.isAutoSave) {
                        stringResource(
                            R.string.save_manager_delete_confirm_body_named,
                            entry.gameTitle,
                            stringResource(R.string.save_manager_auto_save_label)
                        )
                    } else {
                        stringResource(
                            R.string.save_manager_delete_confirm_body,
                            entry.gameTitle,
                            entry.slot
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isWorking = true
                            val success = withContext(Dispatchers.IO) {
                                repository.deleteEntry(entry)
                            }
                            isWorking = false
                            pendingDelete.value = null
                            Toast.makeText(
                                context,
                                if (success) deleteSuccessMessage else deleteFailureMessage,
                                Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                ) {
                    Text(stringResource(R.string.save_manager_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete.value = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    val neonThemeActive = LocalNeonTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = 0.dp,
                bottom = 36.dp + bottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isPreparingEntries) {
                item {
                    SaveManagerHeader(topInset, screenSubtitle, !preparationFailed, onBackClick)
                }
                item {
                    if (preparationFailed) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.save_manager_list_failed), color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = ::refresh) { Text(stringResource(R.string.tv_storage_picker_install_retry)) }
                        }
                    } else Box(Modifier.fillMaxWidth().padding(24.dp).testTag("save-manager-loading"), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    SaveManagerHeader(
                        topInset = topInset,
                        subtitle = screenSubtitle,
                        isWorking = isWorking,
                        onBackClick = onBackClick
                    )
                }

                if (neonThemeActive) {
                    item {
                        NeonSystemBanner()
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val fileName = if (isFiltered) {
                                    "${(screenSubtitle ?: "game").replace(' ', '_')}_saves.zip"
                                } else {
                                    "EmuCoreD_saves.zip"
                                }
                                backupLauncher.launch(fileName)
                            },
                            enabled = entries.isNotEmpty() && !isWorking,
                            modifier = Modifier.weight(0.86f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.save_manager_backup_action),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1.14f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.save_manager_restore_action),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        EmptyStateCard(isFiltered = isFiltered)
                    }
                } else {
                    items(entries, key = { it.absolutePath }) { entry ->
                        SaveEntryCard(
                            entry = entry,
                            preview = previewPaths[entry.absolutePath],
                            showLoadUnavailable = true,
                            onLoadClick = {
                                val path = entry.gamePath ?: return@SaveEntryCard
                                onLoadClick(path, entry.slot)
                            },
                            onDeleteClick = { pendingDelete.value = entry }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveImportPreviewDialog(
    preview: SaveStateImportPreview,
    isFiltered: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val sourceName = stringResource(R.string.save_manager_import_emucored)
    val formats = preview.candidates.map { candidate ->
        when (candidate.format) {
            SaveStateImportFormat.CURRENT -> stringResource(R.string.save_manager_import_format_current)
            SaveStateImportFormat.UNKNOWN -> stringResource(R.string.save_manager_import_format_unknown)
        }
    }.distinct().joinToString()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_manager_import_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = neonShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = preview.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.save_manager_import_detected_source, sourceName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (formats.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.save_manager_import_detected_format, formats),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(
                        if (isFiltered) R.string.save_manager_import_target_game else R.string.save_manager_import_target_library
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.save_manager_import_ready_count, preview.importableCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (preview.importableCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                if (preview.skippedCount > 0) {
                    Text(
                        text = stringResource(R.string.save_manager_import_skipped_count, preview.skippedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (preview.incompatibleCount > 0) {
                    Text(
                        text = stringResource(R.string.save_manager_import_incompatible_count, preview.incompatibleCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = stringResource(R.string.save_manager_import_no_overwrite_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = preview.importableCount > 0) {
                Text(stringResource(R.string.save_manager_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun SaveManagerHeader(
    topInset: androidx.compose.ui.unit.Dp,
    subtitle: String?,
    isWorking: Boolean,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenTopBar(
                title = stringResource(R.string.save_manager_title),
                subtitle = subtitle,
                onBackClick = onBackClick,
                modifier = Modifier.weight(1f)
            )
            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun SaveEntryCard(
    entry: SaveStateEntryInfo,
    preview: SaveStatePreview?,
    showLoadUnavailable: Boolean,
    onLoadClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("save-entry-${entry.absolutePath}"),
        shape = neonShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    modifier = if (preview == null) {
                        Modifier.size(48.dp)
                    } else {
                        Modifier
                            .width(152.dp)
                            .aspectRatio(16f / 9f)
                    },
                    shape = neonShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    if (preview == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        GameCoverArt(
                            coverPath = preview.path,
                            fallbackTitle = "",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = entry.gameTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.platform?.let { stringResource(gamePlatformLabelResource(it)) }
                            ?.let { label -> if (entry.serial.equals(entry.platform?.console?.id, true)) label else "$label • ${entry.serial}" }
                            ?: entry.serial,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (entry.isAutoSave) {
                            stringResource(R.string.save_manager_auto_save_label)
                        } else {
                            stringResource(R.string.save_manager_entry_meta, entry.serial, entry.slot)
                                .substringAfter('•')
                                .trim()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSaveEntryDate(entry.lastModified),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.save_manager_entry_size,
                            formatBytes(entry.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showLoadUnavailable && !entry.canLoad) {
                        Text(
                            text = stringResource(R.string.save_manager_load_unavailable),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    shape = neonButtonShape(),
                    onClick = onLoadClick,
                    enabled = entry.canLoad,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.save_manager_load_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    shape = neonButtonShape(),
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.save_manager_delete_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(isFiltered: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.save_manager_empty_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = stringResource(
                    if (isFiltered) {
                        R.string.save_manager_empty_body_game
                    } else {
                        R.string.save_manager_empty_body_all
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@SuppressLint("DefaultLocale")
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatSaveEntryDate(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestamp))
}

private fun String?.isUsableDisplayTitle(): Boolean {
    if (this.isNullOrBlank()) return false
    val value = this.trim()
    return !value.startsWith("content://") &&
        !value.startsWith("primary%3A", ignoreCase = true) &&
        !value.contains("%2F", ignoreCase = true) &&
        !value.contains("%3A", ignoreCase = true)
}
