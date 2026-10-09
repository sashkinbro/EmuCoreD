// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Installs a CIA through the core. The core reports progress through
 * [setProgressCallback] while it writes the title into the emulated SD card.
 */
@Keep
class CiaInstallWorker {
    private var progressListener: ((Int, Int) -> Unit)? = null

    fun setOnProgressListener(listener: (Int, Int) -> Unit) {
        progressListener = listener
    }

    fun setProgressCallback(total: Int, current: Int) {
        progressListener?.invoke(total, current)
    }

    /** Installs the CIA at [path] and returns the resulting install status. */
    fun install(path: String): EmuCoreNative.InstallStatus = installCia(path)

    private external fun installCia(path: String): EmuCoreNative.InstallStatus
}
