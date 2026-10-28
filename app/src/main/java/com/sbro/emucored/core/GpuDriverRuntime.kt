package com.sbro.emucored.core

import android.content.Context
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.core.dolphin.DolphinNative
import java.io.File
import java.nio.file.Files

/** Call only after the previous session has released its Vulkan instance. */
internal object GpuDriverRuntime {
    @Synchronized
    fun configureDolphin(context: Context, platform: ConsolePlatform, identity: String, usesVulkan: Boolean): Boolean {
        val manager = GpuDriverManager(context)
        val name = if (GpuDriverSupport.canUse(platform, usesVulkan, GpuDriverSupport.deviceSupported()))
            GpuDriverPreferences(context).effective(platform, identity) else null
        val path = name?.let(manager::readMainLibraryPath)?.let(manager::resolveUsableDriverPath)
        val library = path?.let(::File)
        val root = File(context.filesDir, "gpu/dolphin").apply { mkdirs() }
        File(root, "Tmp").mkdirs()
        File(root, "FileRedirect").mkdirs()
        val extracted = File(root, "Extracted").toPath()
        if (Files.isSymbolicLink(extracted)) Files.delete(extracted)
        else if (Files.exists(extracted)) return false
        if (library != null) Files.createSymbolicLink(extracted, library.parentFile.toPath())
        return DolphinNative.configureGpuDriver(root.absolutePath, context.applicationInfo.nativeLibraryDir + "/",
            library?.name.orEmpty())
    }

    fun configureDs(context: Context, identity: String, usesVulkan: Boolean) {
        val manager = GpuDriverManager(context)
        val driverName = if (GpuDriverSupport.canUse(ConsolePlatform.NDS, usesVulkan, GpuDriverSupport.deviceSupported()))
            GpuDriverPreferences(context).effective(ConsolePlatform.NDS, identity) else null
        val path = driverName?.let(manager::readMainLibraryPath)?.let(manager::resolveUsableDriverPath)
        val library = path?.let(::File)
        val tmp = File(context.cacheDir, "gpu-driver-tmp").apply { mkdirs() }
        EmuCoreDsNative.nativeConfigureGpuDriver(tmp.absolutePath + "/", context.applicationInfo.nativeLibraryDir + "/",
            library?.parentFile?.absolutePath?.plus("/"), library?.name)
    }

    fun configureAzahar(context: Context, identity: String, renderer: Int) {
        if (!GpuDriverSupport.deviceSupported()) return
        val manager = GpuDriverManager(context)
        val driverName = if (renderer == RendererDefaults.VULKAN)
            GpuDriverPreferences(context).effective(ConsolePlatform.N3DS, identity) else null
        val path = driverName?.let(manager::readMainLibraryPath)?.let(manager::resolveUsableDriverPath)
        val library = path?.let(::File)
        val redirect = File(context.filesDir, "gpu/vk_file_redirect").apply { mkdirs() }
        EmuCoreNative.initializeGpuDriver(
            context.applicationInfo.nativeLibraryDir + "/",
            library?.parentFile?.absolutePath?.plus("/"),
            library?.name,
            redirect.absolutePath + "/"
        )
    }
}
