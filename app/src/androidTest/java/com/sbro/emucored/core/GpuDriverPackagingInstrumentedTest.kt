package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverPackagingInstrumentedTest {
    @Test fun customVulkanLoaderHasExtractedHookLibraries() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir)
        for (name in listOf("libmain_hook.so", "libhook_impl.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")) {
            assertTrue("Missing extracted GPU driver hook $name", File(directory, name).isFile)
        }
    }
}
