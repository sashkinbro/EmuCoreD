package com.sbro.emucored.ui.cheats

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.CheatBlock
import com.sbro.emucored.data.CheatGameConfig
import com.sbro.emucored.data.CheatRepository
import com.sbro.emucored.data.ContentLibraryRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.cheats.CheatRuntimeUpdates
import com.sbro.emucored.data.cheats.TypedCheatCatalog
import com.sbro.emucored.core.platform.session.CoreCheatFormats
import com.sbro.emucored.core.platform.session.CoreCheatCode
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SelectedGameIdentity
import com.sbro.emucored.ui.common.AppAlertDialog
import com.sbro.emucored.ui.common.LibraryGamePicker
import com.sbro.emucored.ui.common.ScreenTopBar
import com.sbro.emucored.ui.common.appScreenTopPadding
import com.sbro.emucored.ui.common.horizontalViewportBleed
import com.sbro.emucored.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucored.ui.theme.ScreenHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sbro.emucored.ui.theme.neon.neonShape
import com.sbro.emucored.ui.theme.neon.neonButtonShape
import java.net.URLDecoder

private data class InstalledCheatPack(
    val id: String,
    val title: String,
    val blockCount: Int
)

private fun cheatPackDisplayTitle(id: String, blocks: List<CheatBlock>): String {
    val hashedId = id.split('-').any { part ->
        part.length >= 16 && part.all { it.isDigit() || it in 'a'..'f' }
    }
    if (hashedId) {
        val fromSource = blocks.asSequence()
            .mapNotNull { it.sourceUrl }
            .mapNotNull { url ->
                runCatching {
                    URLDecoder.decode(url.substringAfterLast('/'), Charsets.UTF_8.name())
                }.getOrNull()
            }
            .map { it.substringBeforeLast('.') }
            .firstOrNull { it.isNotBlank() }
        if (fromSource != null) return fromSource
    }
    return id.replace('-', ' ').replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheatManagerScreen(onBackClick: () -> Unit) = CheatManagerContent(onBackClick)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheatManagerContent(
    onBackClick: () -> Unit,
    identityResolver: (suspend (GameItem) -> SelectedGameIdentity)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { AppPreferences(context) }
    val cheatRepository = remember(context) { CheatRepository(context) }
    val libraryRepository = remember(context) { ContentLibraryRepository(context) }
    val cheatWriteMutex = remember { Mutex() }
    val cheatsEnabled by preferences.enableCheats.collectAsState(initial = false)
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()

    var games by remember { mutableStateOf<List<GameItem>>(emptyList()) }
    var gamesLoading by remember { mutableStateOf(true) }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionGuard = rememberSaveable(saver = CheatSelectionGuard.Saver) { CheatSelectionGuard() }
    var importSelection by rememberSaveable(stateSaver = PendingCheatImport.NullableSaver) { mutableStateOf<PendingCheatImport?>(null) }
    var identity by remember { mutableStateOf<SelectedGameIdentity?>(null) }
    var config by remember { mutableStateOf<CheatGameConfig?>(null) }
    var resolvingIdentity by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CheatGameConfig?>(null) }
    var pendingDeletePath by remember { mutableStateOf<String?>(null) }
    var pendingPackDelete by remember { mutableStateOf<InstalledCheatPack?>(null) }
    var selectedCheatCategory by remember { mutableStateOf<CheatCategory?>(null) }
    var cheatSearchVisible by remember { mutableStateOf(false) }
    var cheatSearchQuery by remember { mutableStateOf("") }
    // Metadata resolution reads the disc header, which is too slow to repeat
    // every time the user flips back and forth between games. Disc images are
    // immutable here, so cache the resolved identity per library path.
    val identityCache = remember { mutableMapOf<String, SelectedGameIdentity>() }

    val importSuccess = stringResource(R.string.cheat_manager_import_success)
    val importFailure = stringResource(R.string.cheat_manager_import_failed)
    val deleteSuccess = stringResource(R.string.cheat_manager_delete_success)
    val deletePackFailure = stringResource(R.string.cheat_manager_delete_pack_failed)

    suspend fun loadConfig(game: GameItem?, selectedIdentity: SelectedGameIdentity?): CheatGameConfig? = withContext(Dispatchers.IO) {
        if (game != null && game.platform != GamePlatform.N3DS) return@withContext cheatRepository.typedGameConfig(game.platform,game.path)
        val serial = selectedIdentity?.serial.orEmpty()
        val crc = selectedIdentity?.crc
        val keys = buildList {
            if (serial.isNotBlank() && !crc.isNullOrBlank()) add("${serial}_$crc")
            if (!crc.isNullOrBlank()) add(crc)
            if (serial.isNotBlank()) add(serial)
        }
        cheatRepository.getGameConfig(keys, serial, crc)
    }

    fun syncSelectedGame(game: GameItem, resolvedIdentity: SelectedGameIdentity?, request: CheatSelectionGuard.Request) {
        scope.launch {
            val loaded = loadConfig(game, resolvedIdentity) ?: return@launch
            selectionGuard.publish(request) { config = loaded }
            withContext(Dispatchers.IO) {
                cheatWriteMutex.withLock {
                    cheatRepository.syncActiveCheats(
                        loaded.gameKey,
                        loaded.serial,
                        loaded.crc,
                        includeCheats = true
                    )
                    CheatRuntimeUpdates.notifyChanged(game.path)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { libraryRepository.loadGames() }
        selectedPath = selectedPath?.takeIf { path -> games.any { it.path == path } } ?: games.firstOrNull()?.path
        selectedPath?.let { path ->
            if (selectionGuard.current?.path != path) selectionGuard.select(path)
        }
        // The identity effect below only starts after this composition, so mark
        // the resolution as in progress now. Otherwise the catalog section can
        // flash its "no packs for this game" card for a frame while the
        // selected game is known but its identity is not resolved yet.
        resolvingIdentity = games.isNotEmpty()
        gamesLoading = false
    }

    val selectedGame = games.firstOrNull { it.path == selectedPath }
    LaunchedEffect(selectedPath, selectedGame) {
        val game = selectedGame
        if (game == null) {
            identity = null
            config = null
            resolvingIdentity = false
            return@LaunchedEffect
        }
        val request = selectionGuard.current?.takeIf { it.path == game.path }
            ?: selectionGuard.select(game.path)
        identity = null
        config = null
        resolvingIdentity = true
        val cachedIdentity = identityCache[game.path]
        val resolved = cachedIdentity ?: identityResolver?.invoke(game) ?: withContext(Dispatchers.IO) {
            val probeContext = currentCoroutineContext()
            libraryRepository.resolveIdentity(game, checkActive = { probeContext.ensureActive() })
        }
            .also { identityCache[game.path] = it }
        val loaded = loadConfig(game, resolved)
        selectionGuard.publish(request) {
            identity = resolved
            config = loaded
            resolvingIdentity = false
        }
    }

    LaunchedEffect(config?.gameKey) {
        selectedCheatCategory = null
        cheatSearchVisible = false
        cheatSearchQuery = ""
    }

    val installedBlocks = config?.blocks.orEmpty()
    val installedPacks = remember(installedBlocks) {
        installedBlocks
            .mapNotNull { block -> block.sourcePackId?.let { it to block } }
            .groupBy({ it.first }, { it.second })
            .map { (id, blocks) ->
                InstalledCheatPack(
                    id = id,
                    title = cheatPackDisplayTitle(id, blocks),
                    blockCount = blocks.size
                )
            }
            .sortedBy { it.title.lowercase() }
    }
    val categoryGroups = remember(installedBlocks) { groupCheatBlocks(installedBlocks) }
    val visibleInstalledBlocks = remember(installedBlocks, categoryGroups, selectedCheatCategory, cheatSearchQuery) {
        when {
            cheatSearchQuery.isNotBlank() -> installedBlocks.filter { block ->
                block.title.contains(cheatSearchQuery.trim(), ignoreCase = true) ||
                    block.author?.contains(cheatSearchQuery.trim(), ignoreCase = true) == true
            }
            selectedCheatCategory != null -> categoryGroups
                .firstOrNull { it.first == selectedCheatCategory }
                ?.second
                .orEmpty()
            else -> emptyList()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = importSelection.also { importSelection = null }
        if (uri == null || requested == null) return@rememberLauncherForActivityResult
        val importGame = requested.game
        val importIdentity = requested.identity
        val importRequest = requested.request
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(16*1024)
                        while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=4*1024*1024);bytes.write(buffer,0,n)}
                        bytes.toByteArray().decodeToString()
                    }
                        ?: return@runCatching 0
                    val fallbackName = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                    }.getOrNull()?.substringBeforeLast('.')
                    val typedGame=importGame.takeIf{it.platform!=GamePlatform.N3DS}
                    val gameKey = typedGame?.let{TypedCheatCatalog.gameKey(it.platform,it.path)} ?: importIdentity?.let { resolved ->
                        if (!resolved.serial.isNullOrBlank() && !resolved.crc.isNullOrBlank()) {
                            "${resolved.serial}_${resolved.crc}"
                        } else resolved.crc ?: resolved.serial
                    } ?: importGame.serial?.takeIf { it.isNotBlank() }
                        ?: fallbackName
                        ?: "cheat_${System.currentTimeMillis()}"
                    val contents=if(typedGame==null)text else {
                        val blocks=com.sbro.emucored.data.parseCheatBlocks(text).map{block ->
                            if(block.format=="3ds-gateway")block.copy(format=if(typedGame.platform==GamePlatform.NDS)"nds-action-replay" else "unclassified") else block
                        }
                        TypedCheatCatalog.serialize(blocks)
                    }
                    cheatRepository.importCheatFile(gameKey, contents, enableAllByDefault = false)
                }.getOrDefault(0)
            }
            if (result > 0) {
                preferences.setEnableCheats(true)
                CheatRuntimeUpdates.notifyChanged(importGame.path)
                val loaded = loadConfig(importGame, importIdentity)
                selectionGuard.publish(importRequest) { config = loaded }
            }
            Toast.makeText(context, if (result > 0) importSuccess else importFailure, Toast.LENGTH_LONG).show()
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
                .testTag("cheat-manager-list")
                .padding(horizontalSystemBarPadding),
            contentPadding = PaddingValues(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                bottom = 24.dp + bottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ScreenTopBar(
                    title = stringResource(R.string.cheat_manager_title),
                    onBackClick = onBackClick,
                    modifier = Modifier.padding(top = topInset)
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = neonShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.cheat_manager_enable),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.cheat_manager_enable_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cheatsEnabled,
                                onCheckedChange = { enabled ->
                                    val changedPath=selectedPath
                                    val currentConfig=config
                                    scope.launch {
                                        preferences.setEnableCheats(enabled)
                                        withContext(Dispatchers.IO) { if (currentConfig != null) {
                                            cheatRepository.syncActiveCheats(
                                                currentConfig.gameKey,
                                                currentConfig.serial,
                                                currentConfig.crc,
                                                includeCheats = enabled
                                            )
                                        } }
                                        changedPath?.let(CheatRuntimeUpdates::notifyChanged)
                                    }
                                }
                            )
                        }
                        OutlinedButton(
                            enabled = selectedGame != null && !resolvingIdentity,
                            shape = neonButtonShape(),onClick = {
                                val game = selectedGame
                                val request = selectionGuard.current
                                if (game != null && request != null && request.path == game.path && !resolvingIdentity) {
                                    importSelection = PendingCheatImport.capture(game, identity, request)
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            }) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cheat_manager_import_pnach))
                        }
                    }
                }
            }
            item {
                LibraryGamePicker(
                    games = games,
                    selectedPath = selectedPath,
                    onSelected = { game ->
                        if (game.path != selectedPath) {
                            selectionGuard.select(game.path)
                            identity = null
                            config = null
                            resolvingIdentity = true
                            selectedPath = game.path
                        }
                    },
                    horizontalContentPadding = ScreenHorizontalPadding,
                    fullBleedPadding = ScreenHorizontalPadding
                )
            }
            if (selectedGame != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neonShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        val serial = identity?.serial ?: selectedGame.serial
                        val crc = identity?.crc
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (resolvingIdentity) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                text = when {
                                    resolvingIdentity -> serial ?: stringResource(R.string.content_serial_unknown)
                                    crc != null -> stringResource(
                                        R.string.cheat_manager_detected_identity,
                                        serial.orEmpty(),
                                        crc
                                    )
                                    else -> stringResource(
                                        R.string.cheat_manager_crc_unavailable,
                                        serial.orEmpty()
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            item(key = "cheat-online-catalog") {
                val catalogGame = selectedGame
                val catalogIdentity = identity
                val catalogRequest = selectionGuard.current
                CheatOnlineCatalogSection(
                    selectedGame = selectedGame,
                    identity = identity,
                    resolvingIdentity = resolvingIdentity,
                    gamesLoading = gamesLoading,
                    onInstalled = {
                        if (catalogGame != null && catalogRequest != null)
                            syncSelectedGame(catalogGame, catalogIdentity, catalogRequest)
                    }
                )
            }
            config?.takeUnless { resolvingIdentity }?.let { current ->
                item(key = "installed-cheats-header") {
                    Row(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(220),
                                fadeOutSpec = tween(140)
                            )
                            .testTag("cheat-config-${current.gameKey}")
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cheat_manager_installed),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                cheatSearchVisible = !cheatSearchVisible
                                selectedCheatCategory = null
                                if (!cheatSearchVisible) cheatSearchQuery = ""
                            }
                        ) {
                            Icon(
                                imageVector = if (cheatSearchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.cheat_manager_search)
                            )
                        }
                    }
                }
                if (installedPacks.isNotEmpty()) {
                    item(key = "cheat-pack-list") {
                        Column(
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = tween(220),
                                    fadeOutSpec = tween(140)
                                )
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.cheat_manager_installed_packs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalViewportBleed(ScreenHorizontalPadding),
                                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(installedPacks, key = { "cheat-pack-${it.id}" }) { pack ->
                                    AssistChip(
                                        onClick = { pendingPackDelete = pack },
                                        label = {
                                            Text(
                                                text = pack.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.delete),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        shape = neonShape(12.dp),
                                        modifier = Modifier.widthIn(max = 240.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (cheatSearchVisible) {
                    item(key = "cheat-search-field") {
                        OutlinedTextField(
                            value = cheatSearchQuery,
                            onValueChange = { cheatSearchQuery = it },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = tween(220),
                                    fadeOutSpec = tween(140)
                                )
                                .fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.cheat_manager_search)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            trailingIcon = if (cheatSearchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { cheatSearchQuery = "" }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.home_search_clear)
                                        )
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = neonShape(18.dp)
                        )
                    }
                }
                if (!cheatSearchVisible) {
                    item(key = "cheat-folder-header") {
                        CheatCategoryBrowserHeader(
                            selectedCategory = selectedCheatCategory,
                            count = if (selectedCheatCategory == null) {
                                categoryGroups.sumOf { it.second.size }
                            } else {
                                visibleInstalledBlocks.size
                            },
                            onBack = { selectedCheatCategory = null },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(220),
                                fadeOutSpec = tween(140)
                            )
                        )
                    }
                } else {
                    item(key = "cheat-search-header") {
                        Row(
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = tween(220),
                                    fadeOutSpec = tween(140)
                                )
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.cheat_manager_search),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(
                                    R.string.cheat_manager_category_count,
                                    visibleInstalledBlocks.size
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (!cheatSearchVisible && selectedCheatCategory == null) {
                    items(categoryGroups, key = { item -> "cheat-category-${item.first.name}" }) { (category, blocks) ->
                        CheatCategoryCard(
                            category = category,
                            count = blocks.size,
                            onClick = { selectedCheatCategory = category },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = tween(220),
                                fadeOutSpec = tween(140)
                            )
                        )
                    }
                } else {
                    if (visibleInstalledBlocks.isEmpty()) {
                        item(key = "cheat-search-empty") {
                            CheatSearchEmptyState(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = tween(220),
                                    fadeOutSpec = tween(140)
                                )
                            )
                        }
                    } else {
                        items(visibleInstalledBlocks, key = { block -> "cheat-block-${block.id}" }) { block ->
                            val supported=remember(block.format,block.lines,selectedGame?.platform) {
                                selectedGame?.platform?.let { CoreCheatFormats.accepts(it.console.core,
                                    CoreCheatCode(block.format,block.lines.joinToString("\n"))) } ?: false
                            }
                            CheatToggleCard(
                                block = block,
                                supported = supported,
                                onEnabledChange = { enabled ->
                                    if (resolvingIdentity || selectedPath != selectedGame?.path) return@CheatToggleCard
                                    val changedPath=selectedPath
                                    val latest = current
                                    val updatedBlocks = latest.blocks.map { item ->
                                        if (item.id == block.id) item.copy(enabled = enabled) else item
                                    }
                                    config = latest.copy(blocks = updatedBlocks)
                                    scope.launch(Dispatchers.IO) {
                                        cheatWriteMutex.withLock {
                                            // Toggle a single ID so changes made in the
                                            // in-game menu cannot be overwritten by a
                                            // stale copy of the enabled set.
                                            cheatRepository.setBlockEnabled(latest.gameKey, block.id, enabled)
                                            cheatRepository.syncActiveCheats(
                                                latest.gameKey,
                                                latest.serial,
                                                latest.crc
                                            )
                                            changedPath?.let(CheatRuntimeUpdates::notifyChanged)
                                        }
                                    }
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = tween(220),
                                    fadeOutSpec = tween(140)
                                )
                            )
                        }
                    }
                }
                item(key = "cheat-delete") {
                    OutlinedButton(
                        shape = neonButtonShape(),
                        onClick = { pendingDelete = current;pendingDeletePath=selectedPath },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = tween(220),
                            fadeOutSpec = tween(140)
                        )
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cheat_manager_delete))
                    }
                }
            }
        }
    }

    pendingPackDelete?.let { pack ->
        val current = config ?: return@let
        AppAlertDialog(
            onDismissRequest = { pendingPackDelete = null },
            title = { Text(stringResource(R.string.cheat_manager_delete_pack_title)) },
            text = {
                Text(stringResource(R.string.cheat_manager_delete_pack_confirm, pack.title))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val game = selectedGame
                        val resolvedIdentity = identity
                        pendingPackDelete = null
                        if (game == null) return@Button
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                cheatRepository.deleteCheatPack(
                                    gameKey = current.gameKey,
                                    sourcePackId = pack.id,
                                    serial = current.serial,
                                    crc = current.crc
                                )
                            }
                            if (deleted) {
                                CheatRuntimeUpdates.notifyChanged(game.path)
                                Toast.makeText(context, deleteSuccess, Toast.LENGTH_SHORT).show()
                                val latest = loadConfig(game, resolvedIdentity)
                                if (selectedPath == game.path) config = latest
                            } else {
                                Toast.makeText(
                                    context,
                                    deletePackFailure,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    shape = neonButtonShape()
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPackDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingDelete?.let { current ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.cheat_manager_delete_title)) },
            text = { Text(stringResource(R.string.cheat_manager_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    cheatRepository.deleteImportedCheats(current.gameKey, current.serial, current.crc)
                    pendingDeletePath?.let(CheatRuntimeUpdates::notifyChanged)
                    if (selectedPath == pendingDeletePath && config?.gameKey == current.gameKey) config = null
                    pendingDelete = null
                    Toast.makeText(context, deleteSuccess, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun CheatCategoryBrowserHeader(
    selectedCategory: CheatCategory?,
    count: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedCategory == null) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.cheat_manager_categories),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(selectedCategory.titleRes),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = stringResource(R.string.cheat_manager_category_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CheatCategoryCard(
    category: CheatCategory,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = neonShape(13.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(21.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.cheat_manager_category_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CheatToggleCard(
    block: CheatBlock,
    supported: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val links=LocalUriHandler.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = neonShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.bodyLarge
                )
                block.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if(!supported)Text(stringResource(R.string.cheat_format_unsupported),style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                block.sourceUrl?.takeIf{it.startsWith("https://")}?.let { source ->
                    TextButton(onClick={runCatching{links.openUri(source)}}){Text(stringResource(R.string.content_source))}
                }
            }
            Switch(
                checked = block.enabled,
                enabled = supported || block.enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun CheatSearchEmptyState(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = stringResource(R.string.cheat_manager_search_empty),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
