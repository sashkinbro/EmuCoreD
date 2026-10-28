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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sbro.emucored.data.AppPreferencesDataStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/** Isolated real storage owned by the target UID used by the instrumentation process. */
internal class TestStorageContext :
    ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext),
    Closeable, AppPreferencesDataStoreOwner {
    private val storeJob = SupervisorJob()
    override val appPreferencesDataStore by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO),
            produceFile = { File(testFiles, "datastore/settings.preferences_pb") })
    }
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
    private val testExternal = File(root, "external").apply {
        check(mkdirs() && isDirectory) { "Cannot create isolated external files: $this" }
    }
    private val preferenceNames = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = testFiles
    override fun getCacheDir(): File = testCache
    override fun getExternalFilesDir(type: String?): File =
        if (type == null) testExternal else File(testExternal, type).apply { mkdirs() }
    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = "${token}_$name"
        preferenceNames += isolatedName
        return baseContext.getSharedPreferences(isolatedName, mode)
    }

    override fun close() {
        runBlocking { storeJob.cancelAndJoin() }
        preferenceNames.forEach { check(baseContext.deleteSharedPreferences(it)) }
        check(root.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile)
        check(root.name == token)
        check(root.deleteRecursively()) { "Cannot remove isolated test storage: $root" }
    }
}

internal fun testStorageContext(): TestStorageContext = TestStorageContext()
