package com.sbro.emucored.core

import org.junit.Assert.*
import org.junit.Test

class GpuDriverCatalogIdentityTest {
    private fun driver(id: String, url: String) = RemoteGpuDriver(
        id, id, "", "Adreno", "", false, url, "", "", null
    )

    @Test fun releasesWithTheSameArchiveNameHaveDistinctStableNames() {
        val first = driver("release-1", "https://example.org/a/driver.zip")
        val second = driver("release-2", "https://example.org/b/driver.zip")
        assertNotEquals(first.safeArchiveName(), second.safeArchiveName())
        assertEquals(first.safeArchiveName(), first.safeArchiveName())
        assertTrue(first.safeArchiveName().length < 124)
    }
}
