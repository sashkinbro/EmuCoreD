// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Frontend log sink for the bundled core. The core calls these methods from
 * its own logging backend so every emulator log line lands in logcat under
 * the EmuCoreD tag.
 */
@Keep
object Log {
    external fun debug(message: String)
    external fun warning(message: String)
    external fun info(message: String)
    external fun error(message: String)
    external fun critical(message: String)
}
