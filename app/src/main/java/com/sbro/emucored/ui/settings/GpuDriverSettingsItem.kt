package com.sbro.emucored.ui.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucored.R
import com.sbro.emucored.core.GpuDriverManager
import com.sbro.emucored.core.GpuDriverPreferences
import com.sbro.emucored.core.platform.ConsolePlatform

/** Match EmuCoreX: show the active choice directly beneath the renderer. */
@Composable
internal fun GpuDriverSettingsItem(platform: ConsolePlatform, onClick: () -> Unit) {
    val context = LocalContext.current
    val storage = remember(context) { context.getSharedPreferences("gpu_driver_selection", Context.MODE_PRIVATE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(storage) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        storage.registerOnSharedPreferenceChangeListener(listener)
        onDispose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val selected = remember(context, platform, revision) {
        val manager = GpuDriverManager(context)
        GpuDriverPreferences(context).global(platform)?.takeIf { name ->
            manager.resolveUsableDriverPath(manager.readMainLibraryPath(name)) != null
        }
    }
    SettingsItem(
        icon = Icons.Rounded.Tune,
        label = stringResource(R.string.settings_gpu_driver_manager_title),
        value = selected ?: stringResource(R.string.settings_gpu_driver_system),
        onClick = onClick
    )
}
