package com.derpy.earmarks.sync

import android.content.Context
import com.derpy.earmarks.blossom.BlossomService
import com.derpy.earmarks.data.ChannelCache
import com.derpy.earmarks.data.ChannelRepository
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.data.EarmarkCache
import com.derpy.earmarks.data.EarmarkStore
import com.derpy.earmarks.data.KeyStore
import com.derpy.earmarks.data.PendingPruneStore
import com.derpy.earmarks.net.Http
import com.derpy.earmarks.nostr.NostrService
import com.derpy.earmarks.nostr.publishWithRetry

/**
 * Brings local state in line with the relays and Blossom: fetch the earmark
 * list, download whatever is missing, clean up what has gone away, and refresh
 * channels.
 *
 * This is the whole of the app's network pipeline and it deliberately knows
 * nothing about the player, the UI, or Android lifecycle. It used to live in
 * `MainViewModel.load()`, which meant downloads ran in `viewModelScope` and
 * were torn down the moment the app left the foreground — the reason a
 * download restarted from nothing every time the screen locked. It now runs
 * inside [EarmarkSyncWorker] instead, where it survives backgrounding, process
 * death and reboot.
 *
 * Progress is reported through a callback rather than a state flow so the
 * caller decides how to render it: the worker turns it into a notification,
 * and the UI reads it back off WorkManager.
 */
class EarmarkSyncer(context: Context) {

    private val appContext = context.applicationContext
    private val nostrService = NostrService(Http.client)
    private val blossomService = BlossomService(Http.client)
    private val keyStore = KeyStore(appContext)
    private val cache = EarmarkCache(appContext)
    private val store = EarmarkStore(appContext)
    private val pendingPrune = PendingPruneStore(appContext)
    private val channelCache = ChannelCache(appContext)
    private val channelRepo = ChannelRepository(nostrService)

    /** What the sync is doing right now, for the notification and the UI banner. */
    sealed interface Progress {
        object Connecting : Progress
        data class Downloading(val done: Int, val total: Int, val title: String) : Progress
        object Channels : Progress
    }

    sealed interface Outcome {
        /** No key stored yet — nothing to sync, and not an error. */
        object NoKey : Outcome

        /**
         * @property downloaded tracks fetched this run.
         * @property unavailable tracks that failed for reasons that may be
         *   transient, so the run should be retried later.
         */
        data class Synced(
            val earmarks: List<Earmark>,
            val downloaded: Int,
            val unavailable: Int
        ) : Outcome
    }

    /**
     * Runs the full pipeline. Never throws for an individual track — a single
     * dead server must not strand the other eleven — but does propagate a
     * failure to reach the relays at all, which the worker turns into a retry.
     */
    suspend fun sync(onProgress: suspend (Progress) -> Unit = {}): Outcome {
        val privKeyHex = keyStore.getKey() ?: return Outcome.NoKey

        onProgress(Progress.Connecting)
        var earmarks = nostrService.fetchEarmarks(privKeyHex)
        earmarks = reconcilePendingPrunes(privKeyHex, earmarks)

        var downloaded = 0
        var unavailable = 0

        if (earmarks.isNotEmpty()) {
            cache.pruneExpired(earmarks.map { it.ts }.toSet())

            val uncached = earmarks.filter { cache.getCachedFile(it) == null }
            val orphanedTs = mutableListOf<Long>()

            for ((i, earmark) in uncached.withIndex()) {
                onProgress(Progress.Downloading(i + 1, uncached.size, earmark.title))
                val result = try {
                    blossomService.downloadAndDecrypt(
                        earmark,
                        cache.targetFile(earmark),
                        cache.partFile(earmark),
                        privKeyHex
                    )
                } catch (e: Exception) {
                    BlossomService.DownloadResult.Unavailable(e.message ?: "unknown")
                }
                when (result) {
                    is BlossomService.DownloadResult.Success -> downloaded++
                    is BlossomService.DownloadResult.Orphaned -> orphanedTs += earmark.ts
                    is BlossomService.DownloadResult.Unavailable -> unavailable++
                }
            }

            if (orphanedTs.isNotEmpty()) {
                cleanupOrphans(privKeyHex, earmarks, orphanedTs)
                earmarks = earmarks.filterNot { it.ts in orphanedTs }
            }

            store.save(earmarks)
        }

        // Channels are independent of the personal stash: an invited account
        // starts empty and channels are how music first arrives for it, so this
        // runs even when there was nothing to download.
        onProgress(Progress.Channels)
        try {
            val sync = channelRepo.sync(privKeyHex)
            channelCache.save(sync)
        } catch (_: Exception) {
            // A relay hiccup on channels must not fail a run that already
            // downloaded music. The next pass picks them up.
        }

        return Outcome.Synced(earmarks, downloaded, unavailable)
    }

    /**
     * Reconciles a prior delete that died between blob deletion and the Nostr
     * publish. If the fetched list still contains a `ts` recorded as pruned,
     * the blobs are already gone — republish without those entries before
     * anything tries to play them.
     */
    private suspend fun reconcilePendingPrunes(
        privKeyHex: String,
        earmarks: List<Earmark>
    ): List<Earmark> {
        val pending = pendingPrune.load()
        if (pending.isEmpty()) return earmarks

        val stillPresent = earmarks.filter { it.ts in pending }.map { it.ts }
        if (stillPresent.isEmpty()) {
            // Another client already republished. The sentinel is stale.
            pendingPrune.removeAll(pending)
            return earmarks
        }

        val pruned = earmarks.filterNot { it.ts in pending }
        return if (nostrService.publishWithRetry(privKeyHex, pruned) > 0) {
            pendingPrune.removeAll(stillPresent)
            pruned
        } else {
            // Leave the sentinel intact and carry on with the broken list;
            // those tracks will fail to play but the next run retries.
            earmarks
        }
    }

    /**
     * Cleans up earmarks whose blobs are verifiably gone — a 404 from every
     * server for at least one chunk. Best-effort sweeps any surviving chunks
     * off other people's Blossom servers, then republishes the list without
     * these entries.
     *
     * Same sentinel discipline as a user-initiated delete: the pending-prune
     * marker is written BEFORE any blob is touched, so a crash between the
     * delete and the publish is recoverable on the next run.
     */
    private suspend fun cleanupOrphans(
        privKeyHex: String,
        fullList: List<Earmark>,
        orphanedTs: List<Long>
    ) {
        orphanedTs.forEach { pendingPrune.add(it) }

        for (ts in orphanedTs) {
            val manifest = fullList.firstOrNull { it.ts == ts }?.blossom ?: continue
            try {
                blossomService.deleteManifest(manifest, privKeyHex)
            } catch (_: Exception) {
                // The earmark is already broken from the user's point of view;
                // a failed sweep leaves a few orphaned blobs, not a dangling
                // pointer, so it must not block the republish below.
            }
        }

        val pruned = fullList.filterNot { it.ts in orphanedTs }
        if (nostrService.publishWithRetry(privKeyHex, pruned) > 0) {
            pendingPrune.removeAll(orphanedTs)
            orphanedTs.forEach { ts ->
                fullList.firstOrNull { it.ts == ts }?.let { cache.getCachedFile(it)?.delete() }
            }
            store.save(pruned)
        }
        // If every relay failed, the sentinel stays put and the next run retries.
    }
}
