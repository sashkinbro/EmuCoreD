package com.sbro.emucored

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import com.sbro.emucored.core.AppAnalytics
import com.sbro.emucored.core.AppIconManager
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.core.CrashLogger
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.drive.DriveBackupArchive
import com.sbro.emucored.data.drive.DriveBackupException
import com.sbro.emucored.data.drive.DriveBackupWork
import com.sbro.emucored.discord.DiscordIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EmuCoreDApp : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @Volatile
        internal var instance: EmuCoreDApp? = null
            private set
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate() {
        super.onCreate()
        instance = this
        // The Discord SDK helper must not initialize the emulator-side application graph.
        if (getProcessName().endsWith(":discord")) return
        // CrashLogger must be the very first thing — it catches crashes in all subsequent init steps
        CrashLogger.init(this)
        if (DriveBackupArchive.hasPendingRecovery(this)) applicationScope.launch {
            try { BackupSessionGate.whileStopped { DriveBackupArchive(this@EmuCoreDApp).recoverPending() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // VM startup performs the same recovery under the gate before it can open cards.
                if ((error as? DriveBackupException)?.reason != "busy") {
                    android.util.Log.w("DriveBackup", "Pending restore recovery will be retried before VM startup")
                }
            }
        }
        AppAnalytics.initialize(this)
        AppIconManager.applyProIcon(this, AppPreferences(this).getProUnlockedSync())
        DriveBackupWork.resumePending(this)
        EmulatorBridge.initializeOnce(this)
        DiscordIntegration.initialize(this)
    }
}
