package com.derpy.earmarks.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.derpy.earmarks.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Owns when a sync runs and under what conditions.
 *
 * Two entry points with deliberately different network policy:
 *
 * - [syncNow] is what the app asks for when you open it. You are present and
 *   waiting, so it runs on whatever connection is available.
 * - [ensurePeriodicSync] is the unattended pass that makes new tracks simply
 *   be there when you next open the app. It waits for an unmetered connection
 *   unless you have said otherwise, and for the battery not to be low.
 *
 * The two are separate WorkManager requests under separate names, and must be:
 * sharing one name would make [ExistingWorkPolicy.KEEP] treat the always-
 * scheduled periodic work as the incumbent and silently drop every [syncNow].
 * They can therefore overlap, so the guarantee that only one sync touches the
 * download staging area at a time lives in [EarmarkSyncer] itself rather than
 * in the choice of work names.
 */
object SyncScheduler {

    const val UNIQUE_WORK = "earmark-sync"
    private const val PERIODIC_WORK = "earmark-sync-periodic"

    /**
     * How often the unattended pass runs. Frequent enough that a track shared
     * to a channel this morning is on the phone by lunchtime; rare enough that
     * it costs nothing noticeable in battery. WorkManager batches it with other
     * pending work rather than waking the device on a strict schedule.
     */
    private const val PERIODIC_HOURS = 3L

    /**
     * Runs a sync as soon as constraints allow, joining one already running.
     *
     * [ExistingWorkPolicy.KEEP] rather than REPLACE: replacing would cancel a
     * download mid-chunk every time the app was reopened, which is close to the
     * behaviour this whole change exists to remove.
     */
    suspend fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EarmarkSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // The user is looking at the app waiting for their music; ask to
            // skip the queue. Falls back to a normal run when the app's
            // expedited quota is spent.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Registers the unattended pass. Safe to call on every launch —
     * [ExistingPeriodicWorkPolicy.UPDATE] refreshes the constraints of the
     * already-scheduled work rather than resetting its interval, so toggling
     * mobile data takes effect without losing the schedule.
     */
    suspend fun ensurePeriodicSync(context: Context) {
        val allowMetered = SettingsStore(context).downloadOnMobileDataNow()
        val request = PeriodicWorkRequestBuilder<EarmarkSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
                    )
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Live state of the sync, for the UI's progress banner. */
    fun syncState(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_WORK)
}
