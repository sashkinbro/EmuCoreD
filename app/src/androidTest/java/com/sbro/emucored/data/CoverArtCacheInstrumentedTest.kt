package com.sbro.emucored.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.testStorageContext
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverArtCacheInstrumentedTest {
    @Test
    fun twoDimensionalWebpIsFoundAndSameTitleUsesSeparateDsCache() {
        testStorageContext().use { context ->
            val cache = File(context.cacheDir, "game-covers/emucored-n3ds-v2").apply { check(mkdirs()) }
            val threeDsCover = File(cache, "shared_adventure.webp")
            val dsCover = File(cache, "nds_shared_adventure.webp")
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                @Suppress("DEPRECATION")
                threeDsCover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it)) }
                val repository = CoverArtRepository(context)
                assertEquals(threeDsCover.absolutePath, repository.findCachedCoverPath(
                    null, styleOverride = AppPreferences.COVER_ART_STYLE_DEFAULT,
                    title = "Shared Adventure", platform = GamePlatform.N3DS
                ))
                assertNull(repository.findCachedCoverPath(
                    null, styleOverride = AppPreferences.COVER_ART_STYLE_DEFAULT,
                    title = "Shared Adventure", platform = GamePlatform.NDS
                ))
                threeDsCover.copyTo(dsCover)
                assertEquals(dsCover.absolutePath, repository.findCachedCoverPath(
                    null, styleOverride = AppPreferences.COVER_ART_STYLE_DEFAULT,
                    title = "Shared Adventure", platform = GamePlatform.NDS
                ))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
