package com.sbro.emucored.navigation

import com.sbro.emucored.ui.saves.prepareSaveManager
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.navigation.NavOptionsBuilder
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sbro.emucored.R
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.GameLaunchShortcut
import com.sbro.emucored.core.SetupValidator
import com.sbro.emucored.core.StorageAccess
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.PerGameSettingsRepository
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.ui.catalog.CatalogSearchScreen
import com.sbro.emucored.ui.achievements.AchievementsScreen
import com.sbro.emucored.ui.cheats.CheatManagerScreen
import com.sbro.emucored.ui.textures.TextureManagerScreen
import com.sbro.emucored.ui.controls.ScopedTouchControlCreatorHostScreen
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.detail.GameDetailScreen
import com.sbro.emucored.ui.discord.DiscordScreen
import com.sbro.emucored.ui.emulation.EmulationScreen
import com.sbro.emucored.ui.formats.SupportedFormatsScreen
import com.sbro.emucored.ui.feedback.FeedbackScreen
import com.sbro.emucored.ui.home.HomeScreen
import com.sbro.emucored.ui.hub.HubScreen
import com.sbro.emucored.ui.hub.detail.HubDetailScreen
import com.sbro.emucored.ui.memorycards.N3dsSdCardScreen
import com.sbro.emucored.ui.onboarding.OnboardingScreen
import com.sbro.emucored.ui.saves.SaveManagerScreen
import com.sbro.emucored.ui.settings.LanguageSettingsScreen
import com.sbro.emucored.ui.settings.ControlsLayoutEditorHostScreen
import com.sbro.emucored.ui.settings.PerGameSettingsManagerScreen
import com.sbro.emucored.ui.settings.SettingsScreen
import com.sbro.emucored.ui.profile.ProfileScreen
import com.sbro.emucored.ui.settings.SettingsViewModel
import com.sbro.emucored.ui.theme.ThemeManagerScreen
import com.sbro.emucored.ui.common.EmuCoreDLoadingAnimation
import com.sbro.emucored.ui.common.ProvideGamepadUiNavigation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class GameDetailRoute(val catalogGameId: Long)

@Serializable
data class EmulationRoute(
    val gamePath: String? = null,
    val installedPath: String? = null,
    val saveSlot: Int? = null,
    val bootSmokeProbe: Boolean = false,
    val autotestMode: Boolean = false,
    val renderer: Int? = null,
    val exitAppOnExit: Boolean = false
)

internal fun EmulationRoute.isMeaningfulReviewSession(): Boolean =
    !bootSmokeProbe &&
        !autotestMode &&
        !exitAppOnExit &&
        !gamePath.isNullOrBlank()

@Serializable
data class SettingsRoute(val tab: String = "general")

@Serializable
object LanguageSettingsRoute

@Serializable
object ThemeManagerRoute

@Serializable
data class TouchControlCreatorRoute(val platformId: String = ConsolePlatform.N3DS.id)

@Serializable
object OnboardingRoute

@Serializable
object CatalogSearchRoute

@Serializable
object HubRoute

@Serializable
data class HubDetailRoute(val contentId: String)

@Serializable
object SupportedFormatsRoute

@Serializable
object DiscordRoute

@Serializable
data class SaveManagerRoute(
    val gamePath: String? = null,
    val gameTitle: String? = null,
    val gameSerial: String? = null
)

@Serializable
object MemoryCardManagerRoute

@Serializable
object AchievementsRoute

@Serializable
object ProfileRoute

@Serializable
data class TextureManagerRoute(val gamePath: String? = null)

@Serializable
object CheatManagerRoute

@Serializable
object FeedbackRoute

@Serializable
data class GameSettingsManagerRoute(val gamePath: String? = null)

@Serializable
data class ControlsLayoutEditorRoute(
    val gamePath: String? = null,
    val gameTitle: String? = null,
    val gameSerial: String? = null,
    val platformId: String? = null
)

internal enum class StartupDestination {
    HOME,
    ONBOARDING
}

internal fun shouldReleaseStartupSplash(destination: StartupDestination?): Boolean = destination != null

private const val TAG = "AppNavigation"

private fun appScreenEnterTransition(): EnterTransition = appScreenPopEnterTransition()

private fun appScreenExitTransition(): ExitTransition = ExitTransition.None

private fun appScreenPopEnterTransition(): EnterTransition {
    return fadeIn(animationSpec = tween(durationMillis = 260, delayMillis = 70, easing = EaseOut)) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(260, delayMillis = 70, easing = EaseOut))
}

private fun appScreenPopExitTransition(): ExitTransition {
    return fadeOut(animationSpec = tween(durationMillis = 110, easing = EaseIn)) +
        scaleOut(targetScale = 1.0f, animationSpec = tween(110, easing = EaseIn))
}

@Composable
fun AppNavigation(
    launchIntentVersion: Int = 0,
    restoredFromSavedState: Boolean = false,
    onStartupReady: () -> Unit = {},
    onEmulationSessionCompleted: (activePlayTimeMs: Long) -> Unit = {},
    onEmulationActiveChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val preferences = AppPreferences(context)
    val saveStateRepository = SaveStateRepository(context)
    val startupDestination by produceState<StartupDestination?>(
        initialValue = null,
        key1 = preferences,
        key2 = launchIntentVersion
    ) {
        val launchRequest = GameLaunchShortcut.parseLaunchRequest(activity?.intent)
        if (launchRequest?.autotestMode == true) {
            Log.i(TAG, "Startup destination forced HOME for autotest launch path=${launchRequest.gamePath}")
            value = StartupDestination.HOME
            return@produceState
        }
        value = combine(
            preferences.onboardingCompleted,
            preferences.gamePaths
        ) { onboardingCompleted, gamePaths ->
            val hasGameFolder = withContext(Dispatchers.IO) {
                SetupValidator.isAnyGameFolderPresentForStartup(context, gamePaths)
            }
            val shouldOpenHome = onboardingCompleted && hasGameFolder
            Log.i(
                TAG,
                "Startup destination onboarding=$onboardingCompleted gameFolder=$hasGameFolder launch=${launchRequest != null}"
            )
            if (shouldOpenHome) StartupDestination.HOME else StartupDestination.ONBOARDING
        }.first()
    }
    if (startupDestination == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        )
        {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                EmuCoreDLoadingAnimation(size = 80.dp)
            }
        }
        return
    }

    LaunchedEffect(startupDestination) {
        if (!shouldReleaseStartupSplash(startupDestination)) return@LaunchedEffect
        // Keep the platform splash above the first fully composed destination frame.
        withFrameNanos { }
        withFrameNanos { }
        onStartupReady()
    }

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry?.destination) {
        onEmulationActiveChanged(currentBackStackEntry?.destination?.hasRoute<EmulationRoute>() == true)
    }
    val settingsViewModel: SettingsViewModel = viewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var savePreparationJob by remember { mutableStateOf<Job?>(null) }
    var savePreparationOrigin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentBackStackEntry?.id) {
        if (savePreparationOrigin != null && savePreparationOrigin != currentBackStackEntry?.id) {
            savePreparationJob?.cancel()
            savePreparationOrigin = null
        }
    }
    fun navigatePreparedSaveManager(route: SaveManagerRoute, options: NavOptionsBuilder.() -> Unit) {
        savePreparationJob?.cancel()
        val origin = navController.currentBackStackEntry?.id
        savePreparationOrigin = origin
        savePreparationJob = scope.launch {
            try {
                prepareSaveManager(context, route.gamePath, route.gameTitle, route.gameSerial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The destination owns a real failure/retry state; no fabricated slot data.
                Log.w(TAG, "Save manager preparation failed", error)
            }
            ensureActive()
            if (navController.currentBackStackEntry?.id == origin) navController.navigate(route, options)
        }
    }
    var homeDrawerEnabled by remember { mutableStateOf(true) }
    var blockRestoredEmulationRoute by remember(restoredFromSavedState) {
        mutableStateOf(restoredFromSavedState)
    }
    val unsupportedGameImageMessage = stringResource(R.string.shell_launch_game_unsupported)
    val continueUnavailableMessage = stringResource(R.string.home_game_menu_continue_unavailable)
    LaunchedEffect(currentBackStackEntry?.destination, restoredFromSavedState) {
        if (!restoredFromSavedState) return@LaunchedEffect
        val destination = currentBackStackEntry?.destination ?: return@LaunchedEffect
        if (!destination.hasRoute<EmulationRoute>()) {
            blockRestoredEmulationRoute = false
        }
    }
    val launchGamePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val rawPath = uri.toString()
            val displayName = withContext(Dispatchers.IO) {
                StorageAccess.takePersistableReadPermission(context, uri)
                DocumentPathResolver.getDisplayName(context, rawPath)
            }
            if (!isSupportedGameImage(displayName)) {
                Toast.makeText(context, unsupportedGameImageMessage, Toast.LENGTH_SHORT).show()
                return@launch
            }
            navController.navigate(EmulationRoute(gamePath = rawPath)) {
                launchSingleTop = true
            }
        }
    }
    val startDestination = when (startupDestination) {
        StartupDestination.ONBOARDING -> OnboardingRoute
        StartupDestination.HOME -> HomeRoute
        else -> {}
    }
    val navigateGameSettingsManager: () -> Unit = {
        navController.navigate(GameSettingsManagerRoute()) {
            launchSingleTop = true
        }
    }
    val navigateDataTransfer: () -> Unit = {
        navController.navigate(SettingsRoute(tab = "data_transfer")) {
            launchSingleTop = true
        }
    }
    val navigateMemoryCardManager: () -> Unit = {
        navController.navigate(MemoryCardManagerRoute) {
            launchSingleTop = true
        }
    }
    val navigateTextureManager: () -> Unit = {
        navController.navigate(TextureManagerRoute()) { launchSingleTop = true }
    }
    val navigateCheatManager: () -> Unit = {
        navController.navigate(CheatManagerRoute) {
            launchSingleTop = true
        }
    }
    val navigateAchievements: () -> Unit = {
        navController.navigate(AchievementsRoute) {
            launchSingleTop = true
        }
    }
    val navigateProfile: () -> Unit = {
        navController.navigate(ProfileRoute) {
            launchSingleTop = true
        }
    }
    val navigateDiscord: () -> Unit = {
        navController.navigate(DiscordRoute) {
            launchSingleTop = true
        }
    }
    val navigateFeedback: () -> Unit = {
        navController.navigate(FeedbackRoute) {
            launchSingleTop = true
        }
    }
    val navigateHub: () -> Unit = {
        navController.navigate(HubRoute) {
            launchSingleTop = true
        }
    }
    val launchGamePickerAction: () -> Unit = {
        launchGamePicker.launch(arrayOf("*/*"))
    }
    val resetAllSettingsAndOpenOnboarding: () -> Unit = {
        scope.launch {
            preferences.resetAllSettings()
            navController.navigate(OnboardingRoute) {
                launchSingleTop = true
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProvideGamepadUiNavigation(
            enabled = true,
            onBack = {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    false
                }
            }
        )
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = { appScreenEnterTransition() },
            exitTransition = { appScreenExitTransition() },
            popEnterTransition = { appScreenPopEnterTransition() },
            popExitTransition = { appScreenPopExitTransition() },
            sizeTransform = { SizeTransform(clip = false) }
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(HomeRoute) {
                            popUpTo(OnboardingRoute) { inclusive = true }
                        }
                    }
                )
            }

            composable<HomeRoute> {
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Home,
                    onNavigateHub = navigateHub,
                    drawerEnabled = homeDrawerEnabled,
                    onNavigateHome = { },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) {
                            launchSingleTop = true
                        }
                    },
                        onNavigateDiscord = navigateDiscord,
                        onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onLaunchGame = launchGamePickerAction,
                    // Nintendo 3DS system files are optional and a game card
                    // cannot be launched from the drawer, so no BIOS action.
                ) { openDrawer ->
                    HomeScreen(
                        onGameClick = { game, installedPath ->
                            navController.navigate(
                                EmulationRoute(gamePath = game.path, installedPath = installedPath)
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onContinueGame = { game ->
                            val saveSlot = saveStateRepository.findLatestSlot(game.path, game.serial)
                            if (saveSlot == null) {
                                Toast.makeText(context, continueUnavailableMessage, Toast.LENGTH_SHORT).show()
                                return@HomeScreen
                            }
                            navController.navigate(EmulationRoute(gamePath = game.path, saveSlot = saveSlot)) {
                                launchSingleTop = true
                            }
                        },
                        onLoadSaveClick = { game ->
                            navigatePreparedSaveManager(
                                SaveManagerRoute(
                                    gamePath = game.path,
                                    gameTitle = game.title,
                                    gameSerial = game.serial
                                )
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onManageGameClick = { game ->
                            navController.navigate(GameSettingsManagerRoute(gamePath = game.path)) {
                                launchSingleTop = true
                            }
                        },
                        onCreateShortcutClick = { game ->
                            GameLaunchShortcut.requestPinnedShortcut(context, game)
                        },
                        onMenuClick = openDrawer,
                        onShelfModeChanged = { isShelfMode ->
                            homeDrawerEnabled = !isShelfMode
                        }
                    )
                }
            }

            composable<GameDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GameDetailRoute>()
                GameDetailScreen(
                    catalogGameId = route.catalogGameId,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<CatalogSearchRoute> {
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Search,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = { },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    CatalogSearchScreen(
                        onGameClick = { igdbId ->
                            navController.navigate(GameDetailRoute(catalogGameId = igdbId)) {
                                launchSingleTop = true
                            }
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }

            composable<HubRoute> {
                val detailBackStack = rememberSaveable(
                    saver = listSaver(
                        save = { it.toList() },
                        restore = { it.toMutableStateList() }
                    )
                ) {
                    mutableStateListOf<String>()
                }
                val closeDetail = {
                    if (detailBackStack.isNotEmpty()) detailBackStack.removeAt(detailBackStack.lastIndex)
                }
                val openDetail: (String) -> Unit = { contentId ->
                    if (detailBackStack.lastOrNull() != contentId) detailBackStack.add(contentId)
                }

                BackHandler(enabled = detailBackStack.isNotEmpty(), onBack = closeDetail)

                Box(modifier = Modifier.fillMaxSize()) {
                    AdaptiveShell(
                        isProUnlocked = settingsUiState.isProUnlocked,
                        selected = PrimaryDestination.Hub,
                        onNavigateHome = {
                            navController.navigate(HomeRoute) {
                                launchSingleTop = true
                                popUpTo(HomeRoute) { inclusive = false }
                            }
                        },
                        onNavigateSearch = {
                            navController.navigate(CatalogSearchRoute) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateHub = { },
                        onNavigateFormats = {
                            navController.navigate(SupportedFormatsRoute) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateSettings = {
                            navController.navigate(SettingsRoute()) {
                                launchSingleTop = true
                            }
                        },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                        onNavigateGameSettingsManager = navigateGameSettingsManager,
                        onNavigateDataTransfer = navigateDataTransfer,
                        onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                        onNavigateSaveManager = {
                            navigatePreparedSaveManager(SaveManagerRoute()) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateMemoryCardManager = navigateMemoryCardManager,
                                            onNavigateTextureManager = navigateTextureManager,
                                            onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                        onBackClick = { navController.popBackStack() },
                        onLaunchGame = launchGamePickerAction,
                        ) {
                        HubScreen(
                            onBackClick = { navController.popBackStack() },
                            onOpenArticle = openDetail
                        )
                    }

                    AnimatedVisibility(
                        visible = detailBackStack.isNotEmpty(),
                        enter = slideInHorizontally(
                            animationSpec = tween(durationMillis = 260, easing = EaseOut),
                            initialOffsetX = { it / 5 }
                        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                        exit = slideOutHorizontally(
                            animationSpec = tween(durationMillis = 220, easing = EaseIn),
                            targetOffsetX = { it / 5 }
                        ) + fadeOut(animationSpec = tween(durationMillis = 180))
                    ) {
                        detailBackStack.lastOrNull()?.let { contentId ->
                            HubDetailScreen(
                                contentId = contentId,
                                onBackClick = closeDetail,
                                onOpenArticle = openDetail
                            )
                        }
                    }
                }
            }

            composable<EmulationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<EmulationRoute>()
                EmulationScreen(
                    gamePath = route.gamePath,
                    installedPath = route.installedPath,
                    bootSmokeProbe = route.bootSmokeProbe,
                    saveSlot = route.saveSlot,
                    autotestMode = route.autotestMode,
                    rendererOverride = route.renderer,
                    restoredAfterProcessDeath = blockRestoredEmulationRoute,
                    onExit = { activePlayTimeMs ->
                        if (route.exitAppOnExit) {
                            activity?.finishAndRemoveTask()
                        } else {
                            if (!navController.popBackStack(HomeRoute, inclusive = false)) {
                                navController.navigate(HomeRoute) {
                                    launchSingleTop = true
                                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                                }
                            }
                            if (route.isMeaningfulReviewSession()) {
                                onEmulationSessionCompleted(activePlayTimeMs)
                            }
                        }
                    }
                )
            }

            composable<SupportedFormatsRoute> {
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Formats,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateFormats = { },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    SupportedFormatsScreen(
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }

            composable<DiscordRoute> {
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Discord,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) { launchSingleTop = true }
                    },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateDiscord = { },
                    onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    DiscordScreen(onBackClick = { navController.popBackStack() })
                }
            }

            composable<SettingsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SettingsRoute>()
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Settings,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateSettings = { },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    SettingsScreen(
                        initialTab = route.tab,
                        onBackClick = { navController.popBackStack() },
                        onOpenMemoryCardManager = navigateMemoryCardManager,
                        onOpenLanguageScreen = {
                            navController.navigate(LanguageSettingsRoute) {
                                launchSingleTop = true
                            }
                        },
                        onOpenControlsLayoutEditor = { platform ->
                            navController.navigate(ControlsLayoutEditorRoute(platformId = platform.id)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenThemeManager = {
                            navController.navigate(ThemeManagerRoute) {
                                launchSingleTop = true
                            }
                        },
                        onOpenTouchControlCreator = { platform ->
                            navController.navigate(TouchControlCreatorRoute(platform.id)) {
                                launchSingleTop = true
                            }
                        },
                        viewModel = settingsViewModel
                    )
                }
            }

            composable<LanguageSettingsRoute> {
                LanguageSettingsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<ThemeManagerRoute> {
                if (!settingsUiState.isLoaded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        EmuCoreDLoadingAnimation()
                    }
                } else {
                    ThemeManagerScreen(
                        initialLibrary = settingsUiState.customThemeLibrary,
                        isProUnlocked = settingsUiState.isProUnlocked,
                        onPurchasePro = { activity?.let(settingsViewModel::purchasePro) },
                        onSave = { library ->
                            settingsViewModel.saveCustomThemeLibrary(library, activate = false)
                        },
                        onApply = { library ->
                            settingsViewModel.saveCustomThemeLibrary(library, activate = true)
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }

            composable<TouchControlCreatorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TouchControlCreatorRoute>()
                if (!settingsUiState.isLoaded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        EmuCoreDLoadingAnimation()
                    }
                } else {
                    ScopedTouchControlCreatorHostScreen(
                        platformId = route.platformId,
                        isProUnlocked = settingsUiState.isProUnlocked,
                        onPurchasePro = { activity?.let(settingsViewModel::purchasePro) },
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }

            composable<GameSettingsManagerRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GameSettingsManagerRoute>()
                PerGameSettingsManagerScreen(
                    initialGamePath = route.gamePath,
                    onOpenTextureManager = { game ->
                        navController.navigate(TextureManagerRoute(game.path)) { launchSingleTop = true }
                    },
                    onOpenControlsLayoutEditor = { game ->
                        navController.navigate(
                            ControlsLayoutEditorRoute(
                                gamePath = game.path,
                                gameTitle = game.title,
                                gameSerial = game.serial,
                                platformId = game.platform.console.id
                            )
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<ControlsLayoutEditorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ControlsLayoutEditorRoute>()
                ControlsLayoutEditorHostScreen(
                    gamePath = route.gamePath,
                    gameTitle = route.gameTitle,
                    gameSerial = route.gameSerial,
                    platformId = route.platformId,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<FeedbackRoute> {
                AdaptiveShell(
                    isProUnlocked = settingsUiState.isProUnlocked,
                    selected = PrimaryDestination.Feedback,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = { },
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onNavigateAchievements = navigateAchievements,
                    onNavigateProfile = navigateProfile,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    FeedbackScreen(onBackClick = { navController.popBackStack() })
                }
            }

            composable<SaveManagerRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SaveManagerRoute>()
                SaveManagerScreen(
                    gamePath = route.gamePath,
                    gameTitle = route.gameTitle,
                    gameSerial = route.gameSerial,
                    onLoadClick = { path, slot ->
                        navController.navigate(EmulationRoute(gamePath = path, saveSlot = slot)) {
                            launchSingleTop = true
                        }
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<MemoryCardManagerRoute> {
                N3dsSdCardScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<AchievementsRoute> {
                AchievementsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<ProfileRoute> {
                AdaptiveShell(
                    selected = PrimaryDestination.Profile,
                    isProUnlocked = settingsUiState.isProUnlocked,
                    onNavigateHub = navigateHub,
                    onNavigateHome = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            popUpTo(HomeRoute) { inclusive = false }
                        }
                    },
                    onNavigateSearch = {
                        navController.navigate(CatalogSearchRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateFormats = {
                        navController.navigate(SupportedFormatsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateSettings = {
                        navController.navigate(SettingsRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateAchievements = {
                        navController.navigate(AchievementsRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateProfile = { },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onNavigateGameSettingsManager = navigateGameSettingsManager,
                    onNavigateDataTransfer = navigateDataTransfer,
                    onResetAllSettings = resetAllSettingsAndOpenOnboarding,
                    onNavigateSaveManager = {
                        navigatePreparedSaveManager(SaveManagerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateMemoryCardManager = navigateMemoryCardManager,
                    onNavigateTextureManager = navigateTextureManager,
                    onNavigateCheatManager = navigateCheatManager,
                    onBackClick = { navController.popBackStack() },
                    onLaunchGame = launchGamePickerAction
                ) {
                    ProfileScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenGameDetails = { catalogGameId ->
                            navController.navigate(GameDetailRoute(catalogGameId = catalogGameId)) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }

            composable<TextureManagerRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TextureManagerRoute>()
                TextureManagerScreen(
                    onBackClick = { navController.popBackStack() },
                    initialGamePath = route.gamePath
                )
            }

            composable<CheatManagerRoute> {
                CheatManagerScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        LaunchedEffect(navController, startupDestination, launchIntentVersion) {
            val launchRequest = GameLaunchShortcut.parseLaunchRequest(activity?.intent) ?: return@LaunchedEffect
            Log.i(
                TAG,
                "Handling launch request destination=$startupDestination path=${launchRequest.gamePath} autotest=${launchRequest.autotestMode}"
            )
            if (startupDestination == null) return@LaunchedEffect
            // Shortcuts and external intents carry an explicit game; boot them
            // even before onboarding has been finished.
            if (startupDestination != StartupDestination.HOME && launchRequest.gamePath.isNullOrBlank()) {
                return@LaunchedEffect
            }
            navController.navigate(
                EmulationRoute(
                    gamePath = launchRequest.gamePath,
                    saveSlot = launchRequest.saveSlot,
                    bootSmokeProbe = launchRequest.bootSmokeProbe,
                    autotestMode = launchRequest.autotestMode,
                    renderer = launchRequest.renderer,
                    exitAppOnExit = true
                )
            ) {
                launchSingleTop = true
            }
            GameLaunchShortcut.clearLaunchRequest(activity?.intent)
        }

    }
}

internal fun isSupportedGameImage(fileName: String): Boolean {
    return com.sbro.emucored.core.ConsoleGameFormats.isSupportedName(fileName)
}
