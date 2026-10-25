package com.sbro.emucored.ui.cheats

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.sbro.emucored.data.CheatRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.RemoteCatalogResult
import com.sbro.emucored.data.SelectedGameIdentity
import com.sbro.emucored.data.cheats.CheatGameIdentity
import com.sbro.emucored.data.cheats.CheatRuntimeUpdates
import com.sbro.emucored.data.cheats.TypedCheatCatalogRepository
import com.sbro.emucored.data.cheats.TypedCheatPack
import com.sbro.emucored.ui.common.AppAlertDialog
import com.sbro.emucored.ui.common.cheatCatalogGameTitleKey
import com.sbro.emucored.ui.common.rememberRetainedState
import com.sbro.emucored.ui.theme.neon.neonButtonShape
import com.sbro.emucored.ui.theme.neon.neonShape
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TypedCheatSelection(
    val matchingPacks: List<TypedCheatPack>,
    val otherVersionPacks: List<TypedCheatPack>
)

internal fun selectTypedCheatPacks(
    packs: List<TypedCheatPack>,
    identity: CheatGameIdentity?,
    gameTitle: String
): TypedCheatSelection {
    val ordered = compareBy<TypedCheatPack>({ it.title.lowercase(Locale.US) }, { it.id })
    val matching = if (identity == null) {
        emptyList()
    } else {
        packs.filter { it.matches(identity) }.sortedWith(ordered)
    }
    val matchingIds = matching.mapTo(HashSet()) { it.id }
    val titleKey = gameTitle.cheatCatalogGameTitleKey()
    val otherVersions = if (titleKey.isBlank()) {
        emptyList()
    } else {
        packs.filter { pack ->
            pack.id !in matchingIds && pack.title.cheatCatalogGameTitleKey() == titleKey
        }.sortedWith(ordered)
    }
    return TypedCheatSelection(matching, otherVersions)
}

/** Matched recommendations plus an explicit manual catalog for everything else. */
@Composable
internal fun TypedCheatOnlineCatalogSection(
    game: GameItem,
    identity: SelectedGameIdentity?,
    resolving: Boolean,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { TypedCheatCatalogRepository(context) }
    val cheats = remember(context) { CheatRepository(context) }
    val success = stringResource(R.string.cheat_catalog_install_success)
    val failure = stringResource(R.string.cheat_catalog_install_failed)

    // Catalogs are per console. Keep them alive across game switches so moving
    // between two games on the same console never downloads or parses the index
    // again; scrolling the section out of view must not lose it either.
    var cachedCatalogs by rememberRetainedState(
        "typed_cheat_catalog.cache",
        emptyMap<GamePlatform, RemoteCatalogResult<TypedCheatPack>>()
    )
    var result by remember(game.platform) { mutableStateOf(cachedCatalogs[game.platform]) }
    var loading by remember(game.platform) { mutableStateOf(result == null) }
    var refresh by remember(game.platform) { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf<String?>(null) }
    var pendingManual by remember { mutableStateOf<TypedCheatPack?>(null) }
    var browseAll by remember(game.platform) { mutableStateOf(false) }
    var manualQuery by remember(game.platform) { mutableStateOf("") }
    var manualCount by remember(game.platform) { mutableIntStateOf(ManualPageSize) }
    var manualPacks by remember(game.platform) { mutableStateOf(emptyList<TypedCheatPack>()) }
    var selection by remember(game.platform) {
        mutableStateOf(TypedCheatSelection(emptyList(), emptyList()))
    }
    var selectionGamePath by remember(game.platform) { mutableStateOf<String?>(null) }
    var selecting by remember(game.platform) { mutableStateOf(false) }

    LaunchedEffect(game.platform, refresh) {
        val cached = cachedCatalogs[game.platform]
        if (refresh == 0 && cached != null) {
            result = cached
            loading = false
            return@LaunchedEffect
        }
        if (result == null) loading = true
        val loaded = withContext(Dispatchers.IO) {
            repository.load(game.platform, forceRefresh = refresh > 0)
        }
        val previous = result
        if (loaded.entries.isNotEmpty() || previous == null || previous.entries.isEmpty()) {
            result = loaded
        }
        result?.let { cachedCatalogs = cachedCatalogs + (game.platform to it) }
        loading = false
    }

    val packs = result?.entries.orEmpty()
    val failed = result?.entries?.isEmpty() == true && result?.error != null
    val typedIdentity = identity?.typed

    LaunchedEffect(packs, typedIdentity, game.path, game.title) {
        if (selectionGamePath != game.path) {
            selection = TypedCheatSelection(emptyList(), emptyList())
            selectionGamePath = game.path
        }
        selecting = true
        selection = withContext(Dispatchers.Default) {
            selectTypedCheatPacks(packs, typedIdentity, game.title)
        }
        selecting = false
    }

    LaunchedEffect(browseAll, packs, manualQuery) {
        if (!browseAll) {
            manualPacks = emptyList()
            return@LaunchedEffect
        }
        manualPacks = withContext(Dispatchers.Default) {
            val query = manualQuery.trim()
            packs
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                .sortedWith(compareBy<TypedCheatPack>({ it.title.lowercase(Locale.US) }, { it.id }))
        }
    }

    LaunchedEffect(browseAll, manualQuery) { manualCount = ManualPageSize }

    fun installPack(pack: TypedCheatPack, manual: Boolean) {
        if (installing != null) return
        installing = pack.id
        scope.launch {
            val gameIdentity = typedIdentity ?: CheatGameIdentity(game.platform)
            val installed = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = repository.download(pack)
                    cheats.importTypedPack(
                        game.path,
                        gameIdentity,
                        pack,
                        raw,
                        manual = manual
                    ) > 0
                }.getOrDefault(false)
            }
            installing = null
            if (installed) {
                CheatRuntimeUpdates.notifyChanged(game.path)
                onInstalled()
            }
            Toast.makeText(
                context,
                if (installed) success else failure,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val phase = when {
        loading || (result == null && resolving) -> TypedCheatPhase.LOADING
        failed -> TypedCheatPhase.ERROR
        selection.matchingPacks.isEmpty() && selection.otherVersionPacks.isEmpty() &&
            (resolving || selecting) && packs.isNotEmpty() -> TypedCheatPhase.LOADING
        selection.matchingPacks.isEmpty() && selection.otherVersionPacks.isEmpty() && !browseAll ->
            TypedCheatPhase.EMPTY
        else -> TypedCheatPhase.CONTENT
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
                text = stringResource(R.string.cheat_catalog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.cheat_catalog_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                shape = neonButtonShape(),
                onClick = { browseAll = !browseAll },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (browseAll) Icons.Rounded.Check else Icons.Rounded.Search,
                    contentDescription = null
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.cheat_catalog_browse_all))
            }
            if (browseAll) {
                Text(
                    text = stringResource(R.string.cheat_catalog_manual_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = manualQuery,
                    onValueChange = { manualQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(stringResource(R.string.cheat_manager_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 10 }) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically(tween(140)) { height -> -height / 12 })
                },
                label = "typedOnlineCheatContent"
            ) { state ->
                when (state) {
                    TypedCheatPhase.LOADING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.content_catalog_loading))
                    }
                    TypedCheatPhase.EMPTY -> TypedCheatEmptyState()
                    TypedCheatPhase.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.content_catalog_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(shape = neonButtonShape(), onClick = { refresh++ }) {
                            Text(stringResource(R.string.tv_storage_picker_install_retry))
                        }
                    }
                    TypedCheatPhase.CONTENT -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (browseAll) {
                            if (manualPacks.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.cheat_manager_search_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            manualPacks.take(manualCount).forEach { pack ->
                                val matching = typedIdentity?.let(pack::matches) == true
                                TypedCheatCatalogCard(
                                    pack = pack,
                                    compatible = matching,
                                    installing = installing == pack.id,
                                    onSource = { runCatching { links.openUri(pack.sourceUrl) } },
                                    onInstall = if (matching) {
                                        { installPack(pack, manual = false) }
                                    } else null,
                                    onManualInstall = if (matching) null else {
                                        { pendingManual = pack }
                                    }
                                )
                            }
                            if (manualPacks.size > manualCount) {
                                TextButton(onClick = { manualCount += ManualPageSize }) {
                                    Text(stringResource(R.string.detail_show_more))
                                }
                            }
                        } else {
                            if (selection.matchingPacks.isNotEmpty()) {
                                TypedCheatSectionTitle(
                                    stringResource(R.string.content_compatible_game_version_section)
                                )
                                selection.matchingPacks.forEach { pack ->
                                    TypedCheatCatalogCard(
                                        pack = pack,
                                        compatible = true,
                                        installing = installing == pack.id,
                                        onSource = { runCatching { links.openUri(pack.sourceUrl) } },
                                        onInstall = { installPack(pack, manual = false) }
                                    )
                                }
                            }
                            if (selection.otherVersionPacks.isNotEmpty()) {
                                TypedCheatSectionTitle(
                                    stringResource(R.string.content_other_game_versions_section)
                                )
                                Text(
                                    text = stringResource(R.string.cheat_catalog_manual_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                selection.otherVersionPacks.forEach { pack ->
                                    TypedCheatCatalogCard(
                                        pack = pack,
                                        compatible = false,
                                        installing = installing == pack.id,
                                        onSource = { runCatching { links.openUri(pack.sourceUrl) } },
                                        onManualInstall = { pendingManual = pack }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingManual?.let { pack ->
        AppAlertDialog(
            onDismissRequest = { pendingManual = null },
            title = { Text(stringResource(R.string.cheat_catalog_manual_attach)) },
            text = { Text(stringResource(R.string.cheat_catalog_manual_warning)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingManual = null
                        installPack(pack, manual = true)
                    },
                    shape = neonButtonShape()
                ) {
                    Text(stringResource(R.string.cheat_catalog_manual_attach))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingManual = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TypedCheatCatalogCard(
    pack: TypedCheatPack,
    compatible: Boolean,
    installing: Boolean,
    onSource: () -> Unit,
    onInstall: (() -> Unit)? = null,
    onManualInstall: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pack.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(
                    R.string.cheat_catalog_pack_meta,
                    pack.blockCount,
                    pack.formats.sorted().joinToString(", ")
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TypedCheatCompatibilityBadge(compatible)
            if (compatible && onInstall != null) {
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
                        text = stringResource(R.string.content_download_install),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!compatible && onManualInstall != null) {
                Button(
                    shape = neonButtonShape(),
                    onClick = onManualInstall,
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
                        text = stringResource(R.string.cheat_catalog_manual_attach),
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
private fun TypedCheatCompatibilityBadge(compatible: Boolean) {
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
private fun TypedCheatSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun TypedCheatEmptyState() {
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
                text = stringResource(R.string.cheat_catalog_no_packs),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private enum class TypedCheatPhase { LOADING, EMPTY, ERROR, CONTENT }

private const val ManualPageSize = 30
