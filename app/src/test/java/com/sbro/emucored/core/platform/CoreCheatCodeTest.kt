package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.CoreCheatCode
import com.sbro.emucored.core.platform.session.CoreCheatFormats
import org.junit.Assert.*
import org.junit.Test

class CoreCheatCodeTest {
    @Test fun unsupportedFormatsCannotReachAnotherConsole() {
        assertTrue(CoreCheatFormats.accepts(CoreId.SAMEBOY, CoreCheatCode("gb-gameshark", "010315DA")))
        assertFalse(CoreCheatFormats.accepts(CoreId.SAMEBOY, CoreCheatCode("nds-action-replay", "02000000 00000063")))
        assertFalse(CoreCheatFormats.accepts(CoreId.MGBA, CoreCheatCode("gba-device-unspecified", "DEADBEEF CAFEBABE")))
        assertFalse(CoreCheatFormats.accepts(CoreId.NESTOPIA, CoreCheatCode("retroarch-memory", "00AE:41")))
    }

    @Test fun malformedOrPartialCodesAreRejectedBeforeNativeReplacement() {
        assertFalse(CoreCheatFormats.accepts(CoreId.MGBA, CoreCheatCode("gba-codebreaker", "8201227C FFF")))
        assertFalse(CoreCheatFormats.accepts(CoreId.SAMEBOY, CoreCheatCode("gb-gameshark", "01ZZ15DA")))
        assertFalse(CoreCheatFormats.accepts(CoreId.BSNES, CoreCheatCode("snes-game-genie", "C2B7-6D0Z")))
        assertFalse(CoreCheatFormats.accepts(CoreId.MELONDS, CoreCheatCode("nds-action-replay", "02000000")))
        assertTrue(CoreCheatFormats.accepts(CoreId.NESTOPIA, CoreCheatCode("nes-raw-address-value", "0033:09")))
    }
}
