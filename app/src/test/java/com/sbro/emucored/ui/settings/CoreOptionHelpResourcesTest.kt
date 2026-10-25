package com.sbro.emucored.ui.settings

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionHelp
import org.junit.Assert.*
import org.junit.Test

class CoreOptionHelpResourcesTest {
    @Test fun everyDeclaredHelpHasAStaticResourceReferenceForShrinking() {
        val names = ConsolePlatform.entries.flatMap { CoreOptionHelp.entries(it).values }.toSet()
        assertEquals(names, coreOptionHelpResources.keys)
        assertEquals(names.size, coreOptionHelpResources.values.toSet().size)
        assertTrue(coreOptionHelpResources.values.all { it != 0 })
        ConsolePlatform.entries.forEach { platform ->
            CoreOptionHelp.entries(platform).keys.forEach { key ->
                assertNotNull("$platform/$key is missing its help button resource", coreOptionHelpResource(platform, key))
            }
        }
        assertNull(coreOptionHelpResource(ConsolePlatform.GB, "graphics_backend"))
    }
}
