// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import com.sbro.emucored.EmuCoreDApp
import java.io.File
import java.util.Date

/**
 * EmuCoreD's own bridge to the bundled Azahar core.
 *
 * The core ships its upstream Android frontend (compiled into
 * libemucore_core.so) with the JNI symbols renamed to this class, so the
 * app talks to the emulator directly: no libretro environment callbacks, no
 * generic option strings. Rendering is owned by the core and presented to the
 * [Surface] attached with [surfaceChanged].
 */
object EmuCoreNative {
    private const val TAG = "EmuCoreNative"

    const val SAVESTATE_SLOT_COUNT = 11
    const val QUICKSAVE_SLOT = 0

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = runCatching { System.loadLibrary("emucore_core") }
            .onFailure { Log.e(TAG, "Unable to load libemucore_core.so", it) }
            .isSuccess
    }

    // ---------------------------------------------------------------------
    // Emulation lifecycle. The core runs its own emulation thread.
    // ---------------------------------------------------------------------
    external fun run(path: String)
    external fun stopEmulation()
    external fun pauseEmulation()
    external fun unPauseEmulation()
    external fun isRunning(): Boolean
    external fun getRunningTitleId(): Long
    external fun getPerfStats(): DoubleArray
    external fun doFrame()
    external fun setUserDirectory(directory: String)
    external fun createConfigFile()
    external fun createLogFile()
    external fun logUserDirectory(directory: String)
    external fun reloadSettings()
    external fun logDeviceInfo()
    external fun updateFramebuffer(isPortrait: Boolean)
    external fun notifyOrientationChange(layoutOption: Int, rotation: Int, portrait: Boolean)
    external fun swapScreens(swapScreens: Boolean, rotation: Int)
    external fun initializeGpuDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    )

    // ---------------------------------------------------------------------
    // Presentation.
    // ---------------------------------------------------------------------
    external fun surfaceChanged(surface: Surface)
    external fun surfaceDestroyed()
    external fun secondarySurfaceChanged(surface: Surface)
    external fun secondarySurfaceDestroyed()

    // ---------------------------------------------------------------------
    // Input. Coordinates are the emulated framebuffer's 0..1 space.
    // ---------------------------------------------------------------------
    external fun onTouchEvent(x: Float, y: Float, pressed: Boolean): Boolean
    external fun onTouchMoved(x: Float, y: Float)
    external fun onSecondaryTouchEvent(x: Float, y: Float, pressed: Boolean): Boolean
    external fun onSecondaryTouchMoved(x: Float, y: Float)
    external fun onGamePadEvent(device: String, button: Int, action: Int): Boolean
    external fun onGamePadMoveEvent(device: String, axis: Int, x: Float, y: Float): Boolean
    external fun onGamePadAxisEvent(device: String, axisId: Int, axisVal: Float): Boolean

    // ---------------------------------------------------------------------
    // Save states.
    // ---------------------------------------------------------------------
    external fun getSavestateInfo(): Array<SaveStateInfo>?
    external fun saveState(slot: Int)
    external fun loadState(slot: Int)

    // ---------------------------------------------------------------------
    // Metadata, cartridges and system files.
    // ---------------------------------------------------------------------
    external fun getTitleId(filename: String): Long
    external fun getIsSystemTitle(path: String): Boolean
    external fun getInstalledGamePathsImpl(): Array<String?>
    external fun getHomeMenuPath(region: Int): String
    external fun getSystemTitleIds(systemType: Int, region: Int): LongArray
    external fun areSystemTitlesInstalled(): BooleanArray
    external fun uninstallSystemFiles(old3Ds: Boolean)
    external fun setInsertedCartridge(path: String)
    external fun areKeysAvailable(): Boolean
    external fun uninstallTitle(titleId: Long, mediaType: Int): Boolean
    external fun nativeFileExists(path: String): Boolean
    external fun deleteOpenGLShaderCache(titleId: Long)
    external fun deleteVulkanShaderCache(titleId: Long)

    // ---------------------------------------------------------------------
    // Play time.
    // ---------------------------------------------------------------------
    external fun playTimeManagerInit()
    external fun playTimeManagerStart(titleId: Long)
    external fun playTimeManagerStop()
    external fun playTimeManagerGetPlayTime(titleId: Long): Long
    external fun playTimeManagerGetCurrentTitleId(): Long

    // ---------------------------------------------------------------------
    // Camera, amiibo, artic console.
    // ---------------------------------------------------------------------
    external fun reloadCameraDevices()
    external fun loadAmiibo(path: String?): Boolean
    external fun removeAmiibo()
    external fun isFullConsoleLinked(): Boolean
    external fun unlinkConsole()

    // ---------------------------------------------------------------------
    // Frame limiting.
    // ---------------------------------------------------------------------
    external fun setTemporaryFrameLimit(speed: Double)
    external fun disableTemporaryFrameLimit()

    // ---------------------------------------------------------------------
    // Archive helpers.
    // ---------------------------------------------------------------------
    external fun compressFileNative(inputPath: String?, outputPath: String): Int
    external fun decompressFileNative(inputPath: String?, outputPath: String): Int
    external fun getRecommendedExtension(inputPath: String?, shouldCompress: Boolean): String
    external fun initMultiplayer()

    @Keep
    class SaveStateInfo {
        @JvmField
        var slot: Int = 0
        @JvmField
        var time: Date? = null
    }

    @Keep
    enum class CoreError(val value: Int) {
        Success(0),
        ErrorNotInitialized(1),
        ErrorGetLoader(2),
        ErrorSystemMode(3),
        ErrorLoader(4),
        ErrorLoaderErrorEncrypted(5),
        ErrorLoaderErrorInvalidFormat(6),
        ErrorLoaderErrorGBATitle(7),
        ErrorLoaderErrorPatches(8),
        ErrorLoaderErrorPatchesInvalidTitle(9),
        ErrorSystemFiles(10),
        ErrorSavestate(11),
        ErrorArticDisconnected(12),
        ErrorN3DSApplication(13),
        ErrorCoreExceptionRaised(14),
        ErrorSavestateBuildMismatch(15),
        ShutdownRequested(16),
        ErrorUnknown(17);

        companion object {
            fun fromInt(value: Int): CoreError = entries.firstOrNull { it.value == value } ?: ErrorUnknown
        }
    }

    @Keep
    enum class InstallStatus {
        Success,
        ErrorFailedToOpenFile,
        ErrorFileNotFound,
        ErrorAborted,
        ErrorInvalid,
        ErrorEncrypted,
        Cancelled
    }

    @Keep
    object ButtonType {
        const val BUTTON_A = 700
        const val BUTTON_B = 701
        const val BUTTON_X = 702
        const val BUTTON_Y = 703
        const val BUTTON_START = 704
        const val BUTTON_SELECT = 705
        const val BUTTON_HOME = 706
        const val BUTTON_ZL = 707
        const val BUTTON_ZR = 708
        const val DPAD_UP = 709
        const val DPAD_DOWN = 710
        const val DPAD_LEFT = 711
        const val DPAD_RIGHT = 712
        const val STICK_LEFT = 713
        const val STICK_LEFT_UP = 714
        const val STICK_LEFT_DOWN = 715
        const val STICK_LEFT_LEFT = 716
        const val STICK_LEFT_RIGHT = 717
        const val STICK_C = 718
        const val STICK_C_UP = 719
        const val STICK_C_DOWN = 720
        const val STICK_C_LEFT = 771
        const val STICK_C_RIGHT = 772
        const val TRIGGER_L = 773
        const val TRIGGER_R = 774
        const val DPAD = 780
        const val BUTTON_DEBUG = 781
        const val BUTTON_GPIO14 = 782
        const val BUTTON_SWAP = 800
        const val BUTTON_TURBO = 801
    }

    @Keep
    object ButtonState {
        const val RELEASED = 0
        const val PRESSED = 1
    }

    // ---------------------------------------------------------------------
    // Upcalls: the core calls back into EmuCoreD for platform services.
    // ---------------------------------------------------------------------

    /** Called by the core when emulation cannot continue or must abort. */
    @Keep
    @JvmStatic
    fun onCoreError(error: CoreError?, details: String): Boolean {
        Log.e(TAG, "Core error ${error?.name}: $details")
        com.sbro.emucored.core.CoreRuntime.reportNativeError(
            error?.name ?: "ErrorUnknown",
            details
        )
        return true
    }

    @Keep
    @JvmStatic
    fun exitEmulationActivity(resultCode: Int) {
        Log.i(TAG, "Core requested emulation exit: ${CoreError.fromInt(resultCode).name}")
        com.sbro.emucored.core.CoreRuntime.reportNativeExit(resultCode)
    }

    @Keep
    @JvmStatic
    fun requestCameraPermission(): Boolean =
        com.sbro.emucored.core.CoreRuntime.requestCameraPermission()

    @Keep
    @JvmStatic
    fun requestMicPermission(): Boolean =
        com.sbro.emucored.core.CoreRuntime.requestMicPermission()

    @Keep
    @JvmStatic
    fun addNetPlayMessage(type: Int, message: String) {
        com.sbro.emucored.core.CoreRuntime.onNetPlayMessage(type, message)
    }

    @Keep
    @JvmStatic
    fun clearChat() = Unit

    @Keep
    @JvmStatic
    fun onCompressProgress(total: Long, current: Long) = Unit

    @Keep
    @JvmStatic
    fun isPortraitMode(): Boolean {
        val context = EmuCoreDApp.instance ?: return false
        return context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    @Keep
    @JvmStatic
    fun isUsingAngleForOpenGL(): Boolean = false

    @Keep
    @JvmStatic
    fun getBuildFlavor(): String = "emucored"

    @Keep
    @JvmStatic
    fun getUserDirectory(): String =
        com.sbro.emucored.core.CoreRuntime.userDirectory()

    // --- File services (plain filesystem plus SAF fallback) ----------------

    @Keep
    @JvmStatic
    fun fileExists(path: String): Boolean = EmuCoreStorage.exists(path)

    @Keep
    @JvmStatic
    fun isDirectory(path: String): Boolean = EmuCoreStorage.isDirectory(path)

    @Keep
    @JvmStatic
    fun getSize(path: String): Long = EmuCoreStorage.size(path)

    @Keep
    @JvmStatic
    fun deleteDocument(path: String): Boolean = EmuCoreStorage.delete(path)

    @Keep
    @JvmStatic
    fun createFile(directory: String, filename: String): Boolean =
        EmuCoreStorage.createFile(directory, filename)

    @Keep
    @JvmStatic
    fun createDir(directory: String, directoryName: String): Boolean =
        EmuCoreStorage.createDir(directory, directoryName)

    @Keep
    @JvmStatic
    fun openContentUri(path: String, openMode: String): Int =
        EmuCoreStorage.openContentUri(path, openMode)

    @Keep
    @JvmStatic
    fun getFilesName(path: String): Array<String?> = EmuCoreStorage.listFiles(path)

    @Keep
    @JvmStatic
    fun copyFile(
        sourcePath: String,
        destinationParentPath: String,
        destinationFilename: String
    ): Boolean = EmuCoreStorage.copyFile(sourcePath, destinationParentPath, destinationFilename)

    @Keep
    @JvmStatic
    fun renameFile(path: String, destinationFilename: String): Boolean =
        EmuCoreStorage.renameFile(path, destinationFilename)

    @Keep
    @JvmStatic
    fun updateDocumentLocation(sourcePath: String, destinationPath: String): Boolean =
        EmuCoreStorage.moveFile(sourcePath, destinationPath)

    @Keep
    @JvmStatic
    fun moveFile(filename: String, sourceDirPath: String, destinationDirPath: String): Boolean =
        EmuCoreStorage.moveFile(File(sourceDirPath, filename).absolutePath, destinationDirPath)
}

internal object EmuCoreStorage {
    private const val TAG = "EmuCoreStorage"

    fun exists(path: String): Boolean = resolve(path)?.exists() ?: false

    fun isDirectory(path: String): Boolean = resolve(path)?.isDirectory ?: false

    fun size(path: String): Long = resolve(path)?.length() ?: -1L

    fun delete(path: String): Boolean = resolve(path)?.delete() ?: false

    fun createFile(directory: String, filename: String): Boolean {
        val dir = resolve(directory) ?: return false
        return runCatching { File(dir, filename).also { it.parentFile?.mkdirs() }.createNewFile() }
            .getOrDefault(false)
    }

    fun createDir(directory: String, directoryName: String): Boolean {
        val dir = resolve(directory) ?: return false
        return runCatching { File(dir, directoryName).mkdirs() }.getOrDefault(false)
    }

    fun openContentUri(path: String, openMode: String): Int {
        val file = resolve(path) ?: return -1
        val mode = translateOpenMode(openMode)
        return runCatching {
            ParcelFileDescriptor.open(file, mode).detachFd()
        }.getOrDefault(-1)
    }

    fun listFiles(path: String): Array<String?> {
        val dir = resolve(path) ?: return emptyArray()
        return dir.listFiles()?.map { it.name }?.toTypedArray() ?: emptyArray()
    }

    fun copyFile(
        sourcePath: String,
        destinationParentPath: String,
        destinationFilename: String
    ): Boolean {
        val source = resolve(sourcePath) ?: return false
        val destinationDir = resolve(destinationParentPath) ?: return false
        return runCatching {
            source.inputStream().use { input ->
                File(destinationDir, destinationFilename).outputStream().use(input::copyTo)
            }
            true
        }.getOrDefault(false)
    }

    fun renameFile(path: String, destinationFilename: String): Boolean {
        val source = resolve(path) ?: return false
        val target = File(source.parentFile ?: return false, destinationFilename)
        return runCatching { source.renameTo(target) }.getOrDefault(false)
    }

    fun moveFile(sourcePath: String, destinationPath: String): Boolean {
        val source = resolve(sourcePath) ?: return false
        val target = resolve(destinationPath) ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            source.renameTo(target) || source.inputStream().use { input ->
                target.outputStream().use(input::copyTo)
            }.let { source.delete(); true }
        }.getOrDefault(false)
    }

    private fun resolve(path: String): File? {
        if (path.isBlank()) return null
        return runCatching { File(path) }.getOrNull()
    }

    private fun translateOpenMode(mode: String): Int {
        var flags = when {
            mode.contains("r") -> ParcelFileDescriptor.MODE_READ_ONLY
            mode.contains("w") -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        }
        if (mode.contains("t")) {
            flags = flags or ParcelFileDescriptor.MODE_TRUNCATE
        }
        if (mode.contains("a")) {
            flags = flags or ParcelFileDescriptor.MODE_APPEND
        }
        return flags
    }
}
