// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Isolated real storage owned by the target UID used by the instrumentation process. */
internal class TestStorageContext :
    ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext),
    Closeable {
    private val token = "instrumentation_${UUID.randomUUID()}"
    private val root = File(baseContext.cacheDir, token).apply {
        check(mkdirs() && isDirectory) { "Cannot create isolated test directory: $this" }
    }
    private val testFiles = File(root, "files").apply {
        check(mkdirs() && isDirectory) { "Cannot create isolated test files: $this" }
    }
    private val testCache = File(root, "cache").apply {
        check(mkdirs() && isDirectory) { "Cannot create isolated test cache: $this" }
    }
    private val preferenceNames = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = testFiles
    override fun getCacheDir(): File = testCache

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = "${token}_$name"
        preferenceNames += isolatedName
        return baseContext.getSharedPreferences(isolatedName, mode)
    }

    override fun close() {
        preferenceNames.forEach { check(baseContext.deleteSharedPreferences(it)) }
        check(root.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile)
        check(root.name == token)
        check(root.deleteRecursively()) { "Cannot remove isolated test storage: $root" }
    }
}

internal fun testStorageContext(): TestStorageContext = TestStorageContext()
