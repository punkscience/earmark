package com.derpy.earmarks

import android.app.Application
import com.derpy.earmarks.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EarmarksApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Register the unattended sync pass here rather than from the Activity
        // so it is re-armed whenever the process starts at all — including
        // headless starts from Android Auto, a media button, or WorkManager
        // itself after a reboot. Enqueueing is idempotent.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            SyncScheduler.ensurePeriodicSync(this@EarmarksApplication)
        }
    }
}
