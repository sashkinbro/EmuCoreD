package com.sbro.emucored.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucored.core.GpuDriverCatalogRepository
import com.sbro.emucored.core.GpuDriverManager
import com.sbro.emucored.core.GpuDriverPreferences
import com.sbro.emucored.core.InstalledGpuDriver
import com.sbro.emucored.core.RemoteGpuDriver
import com.sbro.emucored.core.RendererDefaults
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GpuDriverUiState(
    val renderer: Int = RendererDefaults.VULKAN,
    val gpuDriverType: Int = 0,
    val customDriverPath: String? = null,
    val installedGpuDrivers: List<InstalledGpuDriver> = emptyList(),
    val remoteGpuDrivers: List<RemoteGpuDriver> = emptyList(),
    val gpuDriverCatalogLoading: Boolean = false,
    val gpuDriverCatalogError: String? = null,
    val gpuDriverDownloads: Map<String, Float> = emptyMap()
)

class GpuDriverViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = GpuDriverManager(application)
    private val catalog = GpuDriverCatalogRepository(application)
    private val preferences = GpuDriverPreferences(application)
    private val state = MutableStateFlow(GpuDriverUiState())
    val uiState = state.asStateFlow()
    private var platform = ConsolePlatform.N3DS

    fun configure(value: ConsolePlatform) {
        platform = value
        refreshInstalledGpuDrivers()
    }

    private fun refreshInstalledGpuDrivers() {
        val selected = preferences.global(platform)?.let(manager::readMainLibraryPath)
        state.update { it.copy(installedGpuDrivers = manager.listInstalledDrivers(),
            customDriverPath = selected, gpuDriverType = if (selected == null) 0 else 1) }
    }

    fun refreshGpuDriverCatalog() {
        if (state.value.gpuDriverCatalogLoading) return
        state.update { it.copy(gpuDriverCatalogLoading = true, gpuDriverCatalogError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { catalog.loadCatalog() }
                .onSuccess { list -> state.update { it.copy(remoteGpuDrivers = list, gpuDriverCatalogLoading = false) } }
                .onFailure { state.update { it.copy(gpuDriverCatalogLoading = false, gpuDriverCatalogError = "failed") } }
        }
    }

    fun installGpuDriver(uri: Uri, activate: Boolean = true, onComplete: (Result<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { manager.installFromArchive(uri).also { if (activate) selectInternal(it) } }
            refreshInstalledGpuDrivers()
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    fun installRemoteGpuDriver(driver: RemoteGpuDriver, activate: Boolean = true, onComplete: (Result<String>) -> Unit) {
        if (driver.id in state.value.gpuDriverDownloads) return
        state.update { it.copy(gpuDriverDownloads = it.gpuDriverDownloads + (driver.id to 0f)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val archive = catalog.downloadDriver(driver) { progress ->
                    state.update { it.copy(gpuDriverDownloads = it.gpuDriverDownloads + (driver.id to progress)) }
                }
                try { manager.installFromArchive(archive, driver.vendor).also { if (activate) selectInternal(it) } }
                finally { archive.delete() }
            }
            state.update { it.copy(gpuDriverDownloads = it.gpuDriverDownloads - driver.id) }
            refreshInstalledGpuDrivers()
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    fun useSystemGpuDriver() { preferences.setGlobal(platform, null); refreshInstalledGpuDrivers() }
    fun selectGpuDriver(driverName: String) { selectInternal(driverName); refreshInstalledGpuDrivers() }
    fun removeGpuDriver(driverName: String) {
        manager.remove(driverName)
        if (preferences.global(platform) == driverName) preferences.setGlobal(platform, null)
        refreshInstalledGpuDrivers()
    }

    private fun selectInternal(driverName: String) {
        val selected = manager.readMainLibraryPath(driverName) ?: error("Driver is not installed")
        check(manager.isVendorCompatible(selected)) { "Driver is incompatible" }
        preferences.setGlobal(platform, driverName)
    }
}
