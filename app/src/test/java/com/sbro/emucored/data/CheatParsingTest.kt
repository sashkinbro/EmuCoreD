package com.sbro.emucored.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatParsingTest {

    @Test
    fun parsesGatewaySections() {
        val raw = """
            [Infinite Health]
            *citra_enabled
            0x00123456 0x00000063

            [Max Money]
            0x00AABBCC 0xFFFFFFFF
        """.trimIndent()

        val blocks = parseCheatBlocks(raw)

        assertEquals(2, blocks.size)
        assertEquals("Infinite Health", blocks[0].title)
        assertTrue(blocks[0].enabled)
        assertEquals(listOf("0x00123456 0x00000063"), blocks[0].lines)
        assertEquals("Max Money", blocks[1].title)
        assertFalse(blocks[1].enabled)
        assertEquals(listOf("0x00AABBCC 0xFFFFFFFF"), blocks[1].lines)
    }

    @Test
    fun stripsBomAndIgnoresComments() {
        val raw = "\uFEFF[Debug Menu]\n// a comment\n; another comment\n0x08000000 0x00000001\n"

        val blocks = parseCheatBlocks(raw)

        assertEquals(1, blocks.size)
        assertEquals("Debug Menu", blocks[0].title)
        assertEquals(listOf("0x08000000 0x00000001"), blocks[0].lines)
        assertFalse(blocks[0].enabled)
    }

    @Test
    fun emptyFilesProduceNoBlocks() {
        assertEquals(emptyList<CheatBlock>(), parseCheatBlocks(""))
        assertEquals(emptyList<CheatBlock>(), parseCheatBlocks("// only a comment\n"))
    }

    @Test
    fun sectionWithoutCodeIsSkipped() {
        val raw = """
            [Empty]
            [Real]
            0x00112233 0x00000001
        """.trimIndent()

        val blocks = parseCheatBlocks(raw)

        assertEquals(1, blocks.size)
        assertEquals("Real", blocks[0].title)
    }
}
