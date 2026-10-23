package com.sbro.emucored.core

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.data.SaveStateRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveStatePreviewTest {
    @Test
    fun previewUsesAnExistingSidecarAndDoesNotInventAnImageForAMissingState() {
        testStorageContext().use { context ->
            val repository = SaveStateRepository(context)
            val state = File.createTempFile("preview-owned-", ".dss", context.cacheDir)
            val sidecar = File("${state.absolutePath}.png")
            val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
            try {
                state.writeBytes(byteArrayOf(1, 2, 3))
                assertNull(repository.getPreviewImagePath(state))
                sidecar.writeBytes(byteArrayOf())
                assertNull(repository.getPreviewImagePath(state))
                bitmap.eraseColor(Color.rgb(100, 50, 25))
                sidecar.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertEquals(sidecar.absolutePath, repository.getPreviewImagePath(state))
                state.delete()
                assertNull(repository.getPreviewImagePath(state))
            } finally {
                bitmap.recycle()
                sidecar.delete()
                state.delete()
            }
        }
    }
}
