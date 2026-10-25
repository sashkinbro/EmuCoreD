package com.sbro.emucored.ui.textures

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucored.R
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.RemoteTexturePack
import com.sbro.emucored.data.drive.DriveBackupException
import com.sbro.emucored.data.textures.AndroidTexturePackRepository
import com.sbro.emucored.data.textures.InstalledTexturePack
import com.sbro.emucored.data.textures.TexturePackTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class TextureGameSelection(val game: GameItem, val target: TexturePackTarget)
internal data class TextureManagerState(
    val games: List<GameItem> = emptyList(), val selectedPath: String? = null,
    val selection: TextureGameSelection? = null, val installed: InstalledTexturePack? = null,
    val loading: Boolean = true, val busy: Boolean = false, val gameBusy: Boolean = BackupSessionGate.gameBusy,
    val message: Int? = null, val identityFailed: Boolean = false,
    val online: List<RemoteTexturePack> = emptyList(), val onlineLoaded: Boolean = false
)

class TextureManagerViewModel private constructor(application: Application,
    private val repository: AndroidTexturePackRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AndroidTexturePackRepository(application))
    internal constructor(application: Application, context: Context) : this(application, AndroidTexturePackRepository(context))
    private val mutable = MutableStateFlow(TextureManagerState())
    internal val state = mutable.asStateFlow()
    private var initialized = false
    private var selectionJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                val busy = BackupSessionGate.gameBusy
                val changed = mutable.value.gameBusy != busy
                mutable.update { it.copy(gameBusy = busy) }
                if (changed && !busy && !mutable.value.busy) mutable.value.selectedPath?.let(::select)
                delay(500)
            }
        }
    }

    internal fun initialize(initialGamePath: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val games = repository.games()
                mutable.update { it.copy(games = games, loading = false) }
                (if (initialGamePath == null) games.firstOrNull() else games.firstOrNull { it.path == initialGamePath })
                    ?.let { select(it.path) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, message = R.string.texture_manager_failed) } }
        }
    }

    internal fun select(path: String) {
        if (mutable.value.busy) return
        val game = mutable.value.games.firstOrNull { it.path == path } ?: return
        selectionJob?.cancel()
        mutable.update { it.copy(selectedPath = path, selection = null, installed = null,
            identityFailed = false, loading = true, online = emptyList(), onlineLoaded = false) }
        selectionJob = viewModelScope.launch {
            try {
                val target = repository.resolve(game)
                if (target == null) {
                    mutable.update { it.copy(loading = false, identityFailed = true) }
                } else {
                    val selection = TextureGameSelection(game, target)
                    val installed = if (BackupSessionGate.gameBusy) null else repository.inspect(game, target)
                    mutable.update { it.copy(selection = selection, installed = installed, loading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, message = errorMessage(error)) } }
        }
    }

    internal fun consumeMessage() { mutable.update { it.copy(message = null) } }
    internal fun importZip(uri: Uri, selected: TextureGameSelection) = action(selected) { repository.importZip(selected.game, selected.target, uri) }
    internal fun export(uri: Uri, selected: TextureGameSelection) = action(selected) { repository.export(selected.game, selected.target, uri) }
    internal fun delete(selected: TextureGameSelection) = action(selected) { repository.delete(selected.game, selected.target) }
    internal fun setEnabled(selected: TextureGameSelection, enabled: Boolean) = action(selected) {
        repository.setEnabled(selected.game, selected.target, enabled)
    }
    internal fun installOnline(selected: TextureGameSelection, pack: RemoteTexturePack) = action(selected) {
        repository.installOnline(selected.game, selected.target, pack)
    }

    internal fun loadOnline() {
        val selected = mutable.value.selection ?: return
        action(selected, showSuccess = false) {
            val packs = repository.onlinePacks(selected.target)
            if (mutable.value.selectedPath == selected.game.path)
                mutable.update { it.copy(online = packs, onlineLoaded = true) }
        }
    }

    private fun action(selected: TextureGameSelection, showSuccess: Boolean = true, work: suspend () -> Unit) {
        if (mutable.value.busy) return
        if (BackupSessionGate.gameBusy) {
            mutable.update { it.copy(message = R.string.texture_manager_stop) }; return
        }
        selectionJob?.cancel()
        mutable.update { current ->
            val retargeted = current.selectedPath != selected.game.path
            current.copy(busy = true, loading = false, selectedPath = selected.game.path, selection = selected,
                installed = if (retargeted) null else current.installed,
                online = if (retargeted) emptyList() else current.online,
                onlineLoaded = if (retargeted) false else current.onlineLoaded,
                identityFailed = false)
        }
        viewModelScope.launch {
            try {
                work()
                val installed = repository.inspect(selected.game, selected.target)
                if (mutable.value.selectedPath == selected.game.path)
                    mutable.update { it.copy(installed = installed, selection = selected,
                        message = if (showSuccess) R.string.texture_manager_done else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = errorMessage(error)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun errorMessage(error: Exception) = if (error is DriveBackupException && error.reason == "busy")
        R.string.texture_manager_stop else R.string.texture_manager_failed
}
