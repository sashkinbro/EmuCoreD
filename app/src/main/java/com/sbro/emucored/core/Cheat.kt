// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * One Gateway cheat owned by the core's cheat engine.
 *
 * The native pointer is created by [createGatewayCode] / the cheat engine and
 * released by [finalize].
 */
@Keep
class Cheat {
    @Keep
    private var mPointer: Long = 0L

    constructor(pointer: Long) {
        mPointer = pointer
    }

    protected external fun finalize()

    external fun getName(): String
    external fun getNotes(): String
    external fun getCode(): String
    external fun getEnabled(): Boolean
    external fun setEnabledImpl(enabled: Boolean)
    external fun isValidGatewayCode(code: String): Int

    companion object {
        @JvmStatic
        external fun createGatewayCode(name: String, notes: String, code: String): Cheat
    }
}
