package com.sbro.emucored.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cross-language names cannot be checked by Kotlin's refactoring or JNI symbol resolution. */
class JniShrinkerContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "app/proguard-rules.pro").isFile }
    private fun source(path: String) = File(root, path).readText()
    private val rules get() = source("app/proguard-rules.pro")
    private val jniSources get() = listOf(
        "core/src/android/app/src/main/jni", "coreDS/bridge", "app/src/main/cpp"
    ).flatMap { directory -> File(root, directory).walkTopDown().filter { it.extension in setOf("cpp", "h") }.toList() }

    @Test fun dsNativeSurfaceHasAnExplicitNonShrinkingNameContract() {
        assertTrue("DS JNI must remain explicit even when generic native rules or annotations change",
            Regex("""-keep,includedescriptorclasses\s+class\s+com\.sbro\.emucored\.core\.ds\.EmuCoreDsNative\s*\{\s*native\s+<methods>;\s*}""")
                .containsMatchIn(rules))
    }

    @Test fun dsKotlinNativeDeclarationsMatchExportedJniSymbols() {
        val declared = Regex("""external\s+fun\s+(\w+)\s*\(""").findAll(
            source("app/src/main/java/com/sbro/emucored/core/ds/EmuCoreDsNative.kt")
        ).map { it.groupValues[1] }.toSet()
        val exported = Regex("""Java_com_sbro_emucored_core_ds_EmuCoreDsNative_(\w+)\s*\(""").findAll(
            source("coreDS/bridge/ds_jni.cpp")
        ).map { it.groupValues[1] }.toSet()
        assertTrue("Expected the actual DS JNI surface", declared.isNotEmpty())
        assertEquals("A renamed/removed Kotlin method must update the native symbol too", declared, exported)
    }

    @Test fun appClassesResolvedByNativeFindClassKeepTheirFieldsConstructorsAndCallbacks() {
        val classNames = jniSources.flatMap { file ->
            Regex("""FindClass\(\s*"(com/sbro/emucored/[^\"]+)"\s*\)""")
                .findAll(file.readText()).map { it.groupValues[1].replace('/', '.') }.toList()
        }.toSet()
        val keptClasses = Regex("""-keep(?:,[^\s]+)?\s+class\s+([\w.$*]+)\s*\{\s*\*;\s*}""")
            .findAll(rules).map { match ->
                Regex(match.groupValues[1].split('*').joinToString(".*") { Regex.escape(it) })
            }.toList()
        assertTrue("Expected Azahar's actual FindClass calls", classNames.isNotEmpty())
        classNames.forEach { name ->
            assertTrue("Native FindClass type lacks a whole-surface keep: $name", keptClasses.any { it.matches(name) })
        }
    }

    @Test fun namedAzaharUpcallsStillHaveStaticKotlinMethods() {
        val kotlinClasses = mapOf(
            "s_native_library_class" to "EmuCoreNative",
            "s_disk_cache_progress_class" to "DiskShaderCacheProgress",
            "s_software_keyboard_class" to "SoftwareKeyboard",
            "s_mii_selector_class" to "MiiSelector",
            "s_still_image_camera_helper_class" to "StillImageCameraHelper"
        )
        val upcalls = Regex("""GetStaticMethodID\(\s*(\w+)\s*,\s*"(\w+)"""")
        val found = jniSources.flatMap { upcalls.findAll(it.readText()).toList() }
        assertTrue(found.isNotEmpty())
        fun requireStaticMethod(className: String, method: String) {
            val kotlin = source("app/src/main/java/com/sbro/emucored/core/$className.kt")
            assertTrue("Native upcall $className.$method must stay static and retain its declared name",
                Regex("""@JvmStatic\s+fun\s+${Regex.escape(method)}\s*\(""").containsMatchIn(kotlin))
        }
        found.forEach { match ->
            val className = kotlinClasses[match.groupValues[1]]
            assertNotNull("Map new native callback owner ${match.groupValues[1]} explicitly", className)
            requireStaticMethod(className!!, match.groupValues[2])
        }
        // AndroidUtils expands these macro-table names into GetStaticMethodID calls.
        val utilities = source("core/src/common/android_utils.h")
        val utilitiesMethods = Regex(""""(\w+)"\s*,\s*"\(""").findAll(utilities)
            .map { it.groupValues[1] }.toSet()
        assertTrue(utilitiesMethods.isNotEmpty())
        utilitiesMethods.forEach { requireStaticMethod("EmuCoreNative", it) }
    }
}
