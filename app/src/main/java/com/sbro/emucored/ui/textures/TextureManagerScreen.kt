package com.sbro.emucored.ui.textures

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.textures.AndroidTexturePackRepository
import com.sbro.emucored.data.textures.InstalledTexturePackInfo
import com.sbro.emucored.data.textures.TexturePackSummary
import com.sbro.emucored.ui.common.AppAlertDialog
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import com.sbro.emucored.ui.theme.neon.neonButtonShape
import com.sbro.emucored.ui.theme.neon.neonShape
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Texture packs are managed globally; the online catalog selects a compatible game. */
@Composable
fun TextureManagerScreen(onBackClick: () -> Unit, initialGamePath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { AppPreferences(context) }
    val repository = remember(context) { AndroidTexturePackRepository(context) }
    val replacementsEnabled by preferences.textureReplacementsEnabled.collectAsState(initial = true)
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()

    var summary by remember { mutableStateOf<TexturePackSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<InstalledTexturePackInfo?>(null) }
    val importSuccess = stringResource(R.string.texture_manager_import_success)
    val importFailed = stringResource(R.string.texture_manager_import_failed)
    val deleteSuccess = stringResource(R.string.texture_manager_delete_success)
    val deleteFailed = stringResource(R.string.texture_manager_delete_failed)

    fun refresh() {
        val generation = refreshGeneration + 1
        refreshGeneration = generation
        scope.launch {
            loading = true
            val loaded = withContext(Dispatchers.IO) { runCatching { repository.listPacks() }.getOrNull() }
            if (refreshGeneration != generation) return@launch
            if (loaded != null) summary = loaded
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importAnyZip(uri) }.getOrNull()
            }
            working = false
            Toast.makeText(
                context,
                if (result != null) {
                    importSuccess.format(result.textureCount)
                } else {
                    importFailed
                },
                Toast.LENGTH_LONG
            ).show()
            if (result != null) refresh()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontalSystemBarPadding),
            contentPadding = PaddingValues(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                bottom = 24.dp + bottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topInset, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScreenTopBar(
                        title = stringResource(R.string.texture_manager_title),
                        onBackClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    )
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item {
                FilledTonalButton(
                    shape = neonButtonShape(),
                    onClick = {
                        importer.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                        )
                    },
                    enabled = !working,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.texture_manager_import_zip))
                }
            }
            item {
                TextureOnlineCatalogSection(
                    initialGamePath = initialGamePath,
                    onInstalled = { refresh() }
                )
            }
            item {
                TextureOptionsPanel(
                    replacementsEnabled = replacementsEnabled,
                    enabled = !working,
                    onReplacementsChanged = { enabled ->
                        scope.launch { repository.setReplacementsEnabled(enabled) }
                    }
                )
            }
            if (loading && summary == null) {
                item {
                    LoadingPanel()
                }
            } else if (summary?.packs.orEmpty().isEmpty()) {
                item {
                    EmptyTexturePacksPanel(repository.storageRoots())
                }
            } else {
                item {
                    TexturePacksSectionHeader(summary = summary, isLoading = loading)
                }
                items(summary?.packs.orEmpty(), key = { "${it.target.platform}:${it.target.id}" }) { pack ->
                    TexturePackCard(
                        pack = pack,
                        isWorking = working,
                        onDelete = { pendingDelete = pack }
                    )
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.texture_manager_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.texture_manager_delete_confirm,
                        pack.gameTitle ?: pack.target.id
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            working = true
                            val success = withContext(Dispatchers.IO) {
                                runCatching { repository.deletePack(pack.target) }.isSuccess
                            }
                            working = false
                            Toast.makeText(
                                context,
                                if (success) deleteSuccess else deleteFailed,
                                Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TextureOptionsPanel(
    replacementsEnabled: Boolean,
    enabled: Boolean,
    onReplacementsChanged: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.texture_manager_options),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.texture_manager_enable_replacements),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.texture_manager_enable_replacements_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = replacementsEnabled,
                    enabled = enabled,
                    onCheckedChange = onReplacementsChanged
                )
            }
            Text(
                text = stringResource(R.string.texture_manager_platform_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.texture_manager_loading),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TexturePacksSectionHeader(summary: TexturePackSummary?, isLoading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.texture_manager_installed_packs),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    R.string.texture_manager_summary_meta,
                    summary?.packs?.size ?: 0,
                    summary?.totalTextureCount ?: 0,
                    formatBytes(summary?.totalSizeBytes ?: 0L)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun TexturePackCard(
    pack: InstalledTexturePackInfo,
    isWorking: Boolean,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            shape = neonShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.gameTitle ?: pack.target.id,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pack.target.id,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (pack.platform == GamePlatform.N3DS) {
                            stringResource(R.string.profile_platform_3ds)
                        } else {
                            "GameCube / Wii"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.texture_manager_pack_modified,
                            formatDate(pack.stats.lastModifiedAt)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.texture_manager_count,
                    pack.stats.textureCount,
                    formatBytes(pack.stats.totalBytes)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                shape = neonButtonShape(),
                onClick = onDelete,
                enabled = !isWorking,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun EmptyTexturePacksPanel(storageRoots: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(22.dp),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            shape = neonShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.texture_manager_empty_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.texture_manager_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            storageRoots.filter(String::isNotBlank).forEach { root ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = neonShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.texture_manager_storage),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = root,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "$bytes ${units[unit]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}
