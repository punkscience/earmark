package com.derpy.earmarks.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.derpy.earmarks.R

/**
 * Whether a finished sync deserves a backed-off retry.
 *
 * Only tracks that failed for possibly-transient reasons — a dropped
 * connection, a 5xx, a SHA mismatch — earn one. Orphans deliberately do not:
 * their blobs are provably gone and the run already resolved them by
 * republishing the list without them, so retrying would just repeat a fetch
 * that can never succeed. Nothing to sync is a success, not a failure.
 *
 * Retrying is close to free now that partial downloads survive: the next
 * attempt resumes from the last verified chunk rather than starting over.
 */
internal fun shouldRetry(outcome: EarmarkSyncer.Outcome): Boolean =
    outcome is EarmarkSyncer.Outcome.Synced && outcome.unavailable > 0

/**
 * Runs [EarmarkSyncer] as background work.
 *
 * Foreground `dataSync` type, so the download keeps its network with the screen
 * off and the app swiped away — the thing the old `viewModelScope` loop could
 * not do. WorkManager handles what a bare service would not: the run is
 * persisted, so it survives the process being reclaimed and is rescheduled
 * after a reboot, and a transient failure backs off exponentially instead of
 * hammering a relay that is already struggling.
 *
 * A run that is killed part way is not wasted. Every chunk already verified
 * stays in its `.part` file and the retry resumes from there.
 */
class EarmarkSyncWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * Required for expedited work below API 31, where the platform has no
     * expedited job concept and WorkManager instead runs the request as a
     * foreground service — asking the worker for its notification *before*
     * [doWork] is entered. [CoroutineWorker]'s default implementation throws,
     * which WorkManager converts into an immediate failure, so without this
     * override every foreground sync would fail on Android 8 through 11
     * without ever running.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notification(context.getString(R.string.sync_notification_connecting))

    override suspend fun doWork(): Result {
        setForegroundSafely(notification(context.getString(R.string.sync_notification_connecting)))

        return try {
            val outcome = EarmarkSyncer(context).sync { progress -> report(progress) }
            if (shouldRetry(outcome)) Result.retry() else Result.success()
        } catch (e: Exception) {
            // Could not reach the relays at all. Back off and try again rather
            // than reporting a failure the user can do nothing about.
            Result.retry()
        }
    }

    private suspend fun report(progress: EarmarkSyncer.Progress) {
        val text = when (progress) {
            is EarmarkSyncer.Progress.Connecting ->
                context.getString(R.string.sync_notification_connecting)
            is EarmarkSyncer.Progress.Downloading -> {
                setProgress(
                    workDataOf(
                        KEY_DONE to progress.done,
                        KEY_TOTAL to progress.total,
                        KEY_TITLE to progress.title
                    )
                )
                context.getString(
                    R.string.sync_notification_downloading,
                    progress.done,
                    progress.total,
                    progress.title
                )
            }
            is EarmarkSyncer.Progress.Channels ->
                context.getString(R.string.sync_notification_channels)
        }
        setForegroundSafely(notification(text))
    }

    /**
     * Foreground promotion is best-effort. It is refused when the app is in the
     * background on Android 12+ without an exemption, and on Android 13+ when
     * the notification permission has been denied. Neither is a reason to
     * abandon the sync — it simply runs as ordinary background work, with less
     * protection from being killed, and resumption covers the difference.
     */
    private suspend fun setForegroundSafely(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: Exception) {
        }
    }

    private fun notification(text: String): ForegroundInfo {
        createChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Low importance and silent: this is ambient progress, not something to
        // interrupt anyone for, and it sits alongside the playback notification.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_TITLE = "title"

        private const val CHANNEL_ID = "earmark_downloads"
        private const val NOTIFICATION_ID = 4242
    }
}
