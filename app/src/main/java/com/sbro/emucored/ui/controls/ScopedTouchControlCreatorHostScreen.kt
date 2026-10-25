package com.sbro.emucored.ui.controls

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.ConsoleControlsProfile
import com.sbro.emucored.data.ConsoleControlsScope
import com.sbro.emucored.data.CustomTouchControlLibrary
import com.sbro.emucored.data.forConsoleControls
import com.sbro.emucored.data.toTouchControlsLayoutProfile
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Global creator edits the selected console's library, leaving legacy and other scopes intact. */
@Composable
fun ScopedTouchControlCreatorHostScreen(
    platformId: String,
    isProUnlocked: Boolean,
    onPurchasePro: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val controlsScope = remember(context, platformId) {
        val platform = ConsolePlatform.fromId(platformId) ?: ConsolePlatform.N3DS
        // This route edits defaults, so pending global attachment settings define the Wii scope.
        val profile = CoreOptionPreferences(context).effectiveOptions(platform)["wii_profile"] ?: "nunchuk"
        ConsoleControlsScope(platform, profile)
    }
    val jobs = rememberCoroutineScope()
    val saveMutex = remember(controlsScope.key) { Mutex() }
    val currentOnBack by rememberUpdatedState(onBackClick)
    var initialLibrary by remember(controlsScope.key) { mutableStateOf<CustomTouchControlLibrary?>(null) }
    var failedSave by remember(controlsScope.key) { mutableStateOf(false) }
    var finishing by remember(controlsScope.key) { mutableStateOf(false) }

    fun reportFailure() {
        Toast.makeText(context, R.string.controls_editor_save_failed, Toast.LENGTH_LONG).show()
    }
    fun finish() {
        if (finishing) return
        finishing = true
        jobs.launch {
            // Await queued autosaves before disposal cancels this composable's coroutine scope.
            saveMutex.withLock {
                if (!failedSave) currentOnBack() else finishing = false
            }
        }
    }
    BackHandler(onBack = ::finish)
    LaunchedEffect(controlsScope.key) {
        try {
            initialLibrary = preferences.overlayLayoutSnapshot.first().forConsoleControls(controlsScope).customTouchControls
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reportFailure(); currentOnBack() }
    }
    val library = initialLibrary
    if (library == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        TouchControlCreatorScreen(
            initialLibrary = library,
            isProUnlocked = isProUnlocked,
            onPurchasePro = onPurchasePro,
            onSave = { changed ->
                if (isProUnlocked && !finishing) jobs.launch {
                    saveMutex.withLock {
                        try {
                            // Re-read current geometry: a library edit must not restore an old layout.
                            val latest = preferences.overlayLayoutSnapshot.first().forConsoleControls(controlsScope)
                            preferences.saveConsoleControls(controlsScope,
                                ConsoleControlsProfile(latest.toTouchControlsLayoutProfile(), changed.sanitized()))
                            failedSave = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failedSave = true; reportFailure() }
                    }
                }
            },
            onBackClick = ::finish,
            consolePolicy = ConsoleOverlayPolicy.forPlatform(controlsScope.platform, controlsScope.normalizedWiiProfile)
        )
    }
}
