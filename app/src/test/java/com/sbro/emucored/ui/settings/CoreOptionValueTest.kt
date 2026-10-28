package com.sbro.emucored.ui.settings

import com.sbro.emucored.core.UPSCALE_DEFAULT
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.data.DsSettingsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreOptionValueTest {
    @Test fun n64ScreenSizesMapToTheSameMultiplierStepsAsOtherConsoles() {
        assertEquals(1, n64ResolutionMultiplier("320x240"))
        assertEquals(2, n64ResolutionMultiplier("640x480"))
        assertEquals(3, n64ResolutionMultiplier("960x720"))
        assertEquals(4, n64ResolutionMultiplier("1280x960"))
        assertNull(n64ResolutionMultiplier("1920x1440"))
    }

    @Test fun everyOfferedN64ResolutionHasAMultiplierLabel() {
        val option = CoreOptionSchema.options(ConsolePlatform.N64).first { it.key == "n64_resolution" }
        option.values.forEach { value ->
            assertNotNull(value, n64ResolutionMultiplier(value))
        }
    }

    @Test fun freshInstallsRenderAtNativeResolution() {
        assertEquals(1f, UPSCALE_DEFAULT, 0f)
        assertEquals(1f, DsSettingsPolicy.DEFAULT_UPSCALE_MULTIPLIER, 0f)
    }
}
