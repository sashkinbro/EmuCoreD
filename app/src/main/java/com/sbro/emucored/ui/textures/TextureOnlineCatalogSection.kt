package com.sbro.emucored.ui.textures

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.InstalledRemoteTexture
import com.sbro.emucored.data.RemoteContentCatalogRepository
import com.sbro.emucored.data.RemoteContentInstallState
import com.sbro.emucored.data.RemoteTexturePack
import com.sbro.emucored.data.textures.AndroidTexturePackRepository
import com.sbro.emucored.data.textures.TexturePackTarget
import com.sbro.emucored.ui.common.ManagerGameSelector
import com.sbro.emucored.ui.common.contentCatalogTitleKey
import com.sbro.emucored.ui.common.rememberRetainedState
import com.sbro.emucored.ui.theme.neon.neonButtonShape
import com.sbro.emucored.ui.theme.neon.neonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TextureOnlineSelection(
    val matchingPacks: List<RemoteTexturePack>,
    val otherVersionPacks: List<RemoteTexturePack>
)

internal fun selectTextureOnlinePacks(
    packs: List<RemoteTexturePack>,
    serial: String?,
    gameTitle: String
): TextureOnlineSelection {
    val normalizedSerial = serial?.trim()?.uppercase(java.util.Locale.US)?.takeIf(String::isNotBlank)
    val ordered = compareBy<RemoteTexturePack>({ it.name.lowercase(java.util.Locale.US) }, { it.id })
    val matching = if (normalizedSerial == null) {
        emptyList()
    } else {
        packs.filter { pack -> pack.serials.any { it.equals(normalizedSerial, ignoreCase = true) } }
            .sortedWith(ordered)
    }
    val titleKey = gameTitle.contentCatalogTitleKey()
    val otherVersions = if (titleKey.isBlank()) {
        emptyList()
    } else {
        packs.filter { pack ->
            pack !in matching && pack.gameTitle.contentCatalogTitleKey() == titleKey
        }.sortedWith(ordered)
    }
    return TextureOnlineSelection(matching, otherVersions)
}

@Composable
internal fun TextureOnlineCatalogSection(
    initialGamePath: String?,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val catalogRepository = remember(context) { RemoteContentCatalogRepository(context) }
    val packRepository = remember(context) { AndroidTexturePackRepository(context) }
    val installState = remember(context) { RemoteContentInstallState(context) }

    var games by rememberRetainedState("texture_online.games", emptyList<GameItem>())
    var selectedPath by rememberRetainedState<String?>("texture_online.selected_path", null)
    var packs by rememberRetainedState("texture_online.packs", emptyList<RemoteTexturePack>())
    var installed by rememberRetainedState("texture_online.installed", emptyMap<String, InstalledRemoteTexture>())
    var target by rememberRetainedState<TexturePackTarget?>("texture_online.target", null)
    var resolvingTarget by rememberRetainedState("texture_online.resolving", false)
    var loading by rememberRetainedState("texture_online.loading", true)
    var cached by rememberRetainedState("texture_online.cached", false)
    var failed by rememberRetainedState("texture_online.failed", false)
    var installing by remember { mutableStateOf<String?>(null) }
    val success = stringResource(R.string.texture_catalog_install_success)
    val failure = stringResource(R.string.texture_catalog_install_failed)

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(packRepository.games(), catalogRepository.loadTextureCatalog(), installState.installedTextures())
        }
        games = loaded.first
        packs = loaded.second.entries
        cached = loaded.second.fromCache
        failed = loaded.second.entries.isEmpty() && loaded.second.error != null
        installed = loaded.third
        selectedPath = initialGamePath?.takeIf { path -> games.any { it.path == path } }
            ?: selectedPath?.takeIf { path -> games.any { it.path == path } }
            ?: games.firstOrNull()?.path
        loading = false
        if (loaded.second.cacheHit) {
            val refreshed = withContext(Dispatchers.IO) { catalogRepository.loadTextureCatalog(forceRefresh = true) }
            if (refreshed.entries.isNotEmpty()) {
                packs = refreshed.entries
                cached = refreshed.fromCache
                failed = false
            }
        }
    }

    val selectedGame = games.firstOrNull { it.path == selectedPath }
    LaunchedEffect(selectedPath) {
        val game = selectedGame
        if (game == null) {
            target = null
            resolvingTarget = false
            return@LaunchedEffect
        }
        target = null
        resolvingTarget = true
        val resolved = withContext(Dispatchers.IO) { runCatching { packRepository.resolve(game) }.getOrNull() }
        if (selectedPath == game.path) {
            target = resolved
            resolvingTarget = false
        }
    }

    val selection = remember(packs, target, selectedGame?.title) {
        selectTextureOnlinePacks(packs, target?.id, selectedGame?.title.orEmpty())
    }
    val phase = when {
        loading || resolvingTarget -> TextureOnlinePhase.LOADING
        failed -> TextureOnlinePhase.ERROR
        selectedGame == null -> TextureOnlinePhase.EMPTY
        target == null -> TextureOnlinePhase.IDENTITY_ERROR
        selection.matchingPacks.isEmpty() && selection.otherVersionPacks.isEmpty() -> TextureOnlinePhase.EMPTY
        else -> TextureOnlinePhase.CONTENT
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.texture_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.texture_catalog_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ManagerGameSelector(
                games = games,
                selectedPath = selectedPath,
                onSelected = { game ->
                    if (game.path != selectedPath) {
                        target = null
                        resolvingTarget = true
                        selectedPath = game.path
                    }
                },
                horizontalContentPadding = 16.dp,
                fullBleedPadding = 16.dp
            )
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 10 }) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically(tween(140)) { height -> -height / 12 })
                },
                label = "onlineTextureContent"
            ) { state ->
                when (state) {
                    TextureOnlinePhase.LOADING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.content_catalog_loading))
                    }
                    TextureOnlinePhase.ERROR -> Text(
                        text = stringResource(R.string.content_catalog_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextureOnlinePhase.IDENTITY_ERROR -> Text(
                        text = stringResource(R.string.texture_manager_identity_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextureOnlinePhase.EMPTY -> TextureCatalogEmptyState()
                    TextureOnlinePhase.CONTENT -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (selection.matchingPacks.isNotEmpty()) {
                            TextureCatalogSectionTitle(
                                stringResource(R.string.content_compatible_game_version_section)
                            )
                            selection.matchingPacks.forEach { pack ->
                                val currentTarget = target
                                val currentGame = selectedGame
                                TextureCatalogCard(
                                    pack = pack,
                                    compatible = true,
                                    installed = installed.containsKey(pack.id),
                                    installing = installing == pack.id,
                                    onSource = { runCatching { links.openUri(pack.sourceUrl) } },
                                    onInstall = {
                                        if (currentTarget == null || currentGame == null) {
                                            return@TextureCatalogCard
                                        }
                                        installing = pack.id
                                        scope.launch {
                                            val installedOk = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    packRepository.installOnline(currentGame, currentTarget, pack)
                                                    installState.recordTexture(pack, currentTarget.id)
                                                    true
                                                }.getOrDefault(false)
                                            }
                                            installing = null
                                            if (installedOk) {
                                                installed = withContext(Dispatchers.IO) {
                                                    installState.installedTextures()
                                                }
                                                onInstalled()
                                            }
                                            Toast.makeText(
                                                context,
                                                if (installedOk) success else failure,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                        if (selection.otherVersionPacks.isNotEmpty()) {
                            TextureCatalogSectionTitle(
                                stringResource(R.string.content_other_game_versions_section)
                            )
                            selection.otherVersionPacks.forEach { pack ->
                                TextureCatalogCard(
                                    pack = pack,
                                    compatible = false,
                                    installed = false,
                                    installing = false,
                                    onSource = { runCatching { links.openUri(pack.sourceUrl) } },
                                    onInstall = {}
                                )
                            }
                        }
                    }
                }
            }
            if (cached) {
                Text(
                    text = stringResource(R.string.content_catalog_cached),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TextureCatalogCard(
    pack: RemoteTexturePack,
    compatible: Boolean,
    installed: Boolean,
    installing: Boolean,
    onSource: () -> Unit,
    onInstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(
                            R.string.texture_catalog_pack_meta,
                            pack.version,
                            formatTextureBytes(pack.sizeBytes),
                            pack.fileCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (installed) {
                    Text(
                        text = stringResource(R.string.content_installed),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            TextureCompatibilityBadge(compatible)
            if (!compatible && pack.serials.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.content_supported_serials, pack.serials.joinToString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (pack.description.isNotBlank()) {
                Text(
                    text = pack.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.content_authors, pack.authors.joinToString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (compatible) {
                Button(
                    shape = neonButtonShape(),
                    onClick = onInstall,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(
                            if (installed) R.string.content_reinstall else R.string.content_download_install
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                shape = neonButtonShape(),
                onClick = onSource,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.content_source),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TextureCatalogEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = neonShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp).size(22.dp)
                )
            }
            Text(
                text = stringResource(R.string.texture_catalog_no_packs),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextureCompatibilityBadge(compatible: Boolean) {
    Surface(
        shape = neonShape(10.dp),
        color = if (compatible) Color(0xFF1B6B3A) else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (compatible) Color.White else MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = stringResource(
                if (compatible) R.string.content_compatible else R.string.content_not_compatible
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TextureCatalogSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun formatTextureBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    return String.format(java.util.Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

private enum class TextureOnlinePhase { LOADING, EMPTY, ERROR, IDENTITY_ERROR, CONTENT }
