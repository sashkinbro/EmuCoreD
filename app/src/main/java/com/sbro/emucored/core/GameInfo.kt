// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Title metadata produced by the core while scanning a 3DS image.
 *
 * The native side owns the loader state and addresses it through [pointer];
 * the handle is released by [finalize] when the object is collected.
 */
@Keep
class GameInfo(path: String) {
    @Keep
    private val pointer: Long = initialize(path)

    protected external fun finalize()

    external fun getTitle(): String
    external fun getCompany(): String
    external fun getRegions(): String
    external fun getFileType(): String
    external fun getIcon(): IntArray?
    external fun getTitleID(): Long
    external fun isValid(): Boolean
    external fun isEncrypted(): Boolean
    external fun isSystemTitle(): Boolean
    external fun getIsVisibleSystemTitle(): Boolean
    external fun getIsInsertable(): Boolean

    companion object {
        @JvmStatic
        private external fun initialize(path: String): Long
    }
}
