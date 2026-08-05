package com.derpy.earmarks.sync

import android.content.Context
import com.derpy.earmarks.blossom.BlossomService
import com.derpy.earmarks.data.ChannelCache
import com.derpy.earmarks.data.ChannelRepository
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.data.EarmarkCache
import com.derpy.earmarks.data.EarmarkStore
import com.derpy.earmarks.data.KeyStore
import com.derpy.earmarks.data.OrphanStrikeStore
import com.derpy.earmarks.data.PendingPruneStore
import com.derpy.earmarks.net.Http
import com.derpy.earmarks.nostr.NostrService
import com.derpy.earmarks.nostr.publishWithRetry
import kotlinx.coroutines.sync.Mutex

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
    private val orphanStrikes = OrphanStrikeStore(appContext)
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

        /** Another sync already holds the lock. Not an error either. */
        object AlreadyRunning : Outcome

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
     *
     * At most one sync runs at a time, process-wide. The scheduled pass and
     * the run triggered by opening the app are separate WorkManager requests
     * and can otherwise overlap, and two syncs appending to the same `.part`
     * file would interleave chunks into a file that is the right length and
     * the wrong audio — which the assembled-length check in
     * [com.derpy.earmarks.blossom.BlossomService] cannot catch. A second
     * caller returns immediately rather than queueing, because the run already
     * in flight is doing its work for it.
     */
    suspend fun sync(onProgress: suspend (Progress) -> Unit = {}): Outcome {
        if (!syncLock.tryLock()) return Outcome.AlreadyRunning
        try {
            return syncLocked(onProgress)
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun syncLocked(onProgress: suspend (Progress) -> Unit): Outcome {
        val privKeyHex = keyStore.getKey() ?: return Outcome.NoKey

        onProgress(Progress.Connecting)
        var earmarks = nostrService.fetchEarmarks(privKeyHex)
        earmarks = reconcilePendingPrunes(privKeyHex, earmarks)

        // Channels are independent of the personal stash: an invited account
        // starts empty and channels are how music first arrives for it, so this
        // runs even when the stash has nothing in it.
        onProgress(Progress.Channels)
        val channelPosts = try {
            val sync = channelRepo.sync(privKeyHex)
            channelCache.save(sync)
            sync.posts.map { it.earmark }
        } catch (_: Exception) {
            // A relay hiccup on channels must not fail a run that still has
            // music to fetch. The next pass picks them up.
            emptyList()
        }

        if (earmarks.isNotEmpty()) {
            cache.pruneExpired(earmarks.map { it.ts }.toSet() + channelPosts.map { it.ts })
            // Evidence about earmarks that are no longer listed is dead weight.
            // Guarded on a non-empty list so a read that came back with nothing
            // can't wipe the counts for a stash that is still there.
            orphanStrikes.retainOnly(earmarks.map { it.ts })
        }

        // Personal earmarks first — your own stash is what the player falls
        // back to — then whatever has been shared with you. Both are fetched
        // here so that "open the app and the new tracks are there" holds for
        // music other people sent as well as your own.
        val wanted = (earmarks + channelPosts).filter { cache.getCachedFile(it) == null }
        val orphanedTs = mutableListOf<Long>()
        var downloaded = 0
        var unavailable = 0

        for ((i, earmark) in wanted.withIndex()) {
            onProgress(Progress.Downloading(i + 1, wanted.size, earmark.title))
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
                is BlossomService.DownloadResult.Success -> {
                    downloaded++
                    // The blobs are demonstrably there, which contradicts any
                    // earlier orphan report rather than merely not adding to it.
                    orphanStrikes.clear(earmark.ts)
                }
                // Only the personal list can be republished to drop an orphan.
                // A channel post that has gone is the sender's business.
                //
                // A 404 from every server is not proof on its own — a Blossom
                // server that has been reinstalled or put behind a broken proxy
                // says the same thing — so it buys a strike, and only a track
                // several independent syncs agree about gets swept.
                is BlossomService.DownloadResult.Orphaned ->
                    if (earmarks.any { it.ts == earmark.ts } &&
                        orphanStrikes.record(earmark.ts)
                    ) {
                        orphanedTs += earmark.ts
                    }
                is BlossomService.DownloadResult.Unavailable -> unavailable++
            }
        }

        if (orphanedTs.isNotEmpty()) {
            cleanupOrphans(privKeyHex, earmarks, orphanedTs)
            earmarks = earmarks.filterNot { it.ts in orphanedTs }
        }

        if (earmarks.isNotEmpty()) store.save(earmarks)

        return Outcome.Synced(earmarks, downloaded, unavailable)
    }

    private companion object {
        /**
         * Serialises syncs across every trigger. All workers share one process,
         * so a process-wide lock is enough to keep two of them off the same
         * `.part` file.
         */
        val syncLock = Mutex()
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
     * server for at least one chunk, seen by enough separate syncs to rule out
     * one server having a bad day (see [com.derpy.earmarks.data.OrphanStrikeStore]).
     * Best-effort sweeps any surviving chunks off other people's Blossom
     * servers, then republishes the list without these entries.
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
