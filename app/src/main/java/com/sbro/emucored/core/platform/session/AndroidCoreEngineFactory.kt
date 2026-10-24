// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.platform.session

import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.gb.SameBoySessionEngine
import com.sbro.emucored.core.gba.MgbaSessionEngine
import com.sbro.emucored.core.nes.NestopiaSessionEngine
import com.sbro.emucored.core.snes.BsnesSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreId

/** Native runtime selection is separate from product/UI acceptance of a console. */
object AndroidCoreEngineFactory : CoreEngineFactory {
    override fun create(platform: ConsolePlatform): CoreSessionEngine? = when (platform.core) {
        CoreId.DOLPHIN -> DolphinSessionEngine()
        CoreId.MGBA -> MgbaSessionEngine()
        CoreId.SAMEBOY -> SameBoySessionEngine()
        CoreId.NESTOPIA -> NestopiaSessionEngine()
        CoreId.BSNES -> BsnesSessionEngine()
        // The tested DS/3DS legacy bridge remains in use until its contract adapters are ready.
        CoreId.AZAHAR, CoreId.MELONDS -> null
    }
}
