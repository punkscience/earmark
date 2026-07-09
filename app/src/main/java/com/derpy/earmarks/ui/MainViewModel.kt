package com.derpy.earmarks.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.derpy.earmarks.blossom.BlossomService
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.data.earmarksToJson
import com.derpy.earmarks.data.EarmarkCache
import com.derpy.earmarks.data.KeyStore
import com.derpy.earmarks.data.PendingPruneStore
import com.derpy.earmarks.nostr.Bech32
import com.derpy.earmarks.nostr.NostrService
import com.derpy.earmarks.player.EarmarksMediaService
import com.derpy.earmarks.player.PlayerController
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AppState {
    object KeyMissing : AppState
    data class Loading(val message: String) : AppState
    data class Downloading(val done: Int, val total: Int) : AppState
    /**
     * [unavailable] is the number of earmarks whose download failed this
     * session for transient reasons (network, 5xx, SHA mismatch). They're
     * still in the published list; we just couldn't play them right now.
     * Orphaned earmarks (definitively gone from Blossom) are pruned silently
     * and do not contribute to this count.
     */
    data class Playing(val earmarks: List<Earmark>, val unavailable: Int = 0) : AppState
    data class Error(val message: String) : AppState
}

/** Aggregate Blossom storage stats derived from the current earmark list. */
data class BlossomStats(
    val totalEarmarks: Int,
    val totalParts: Int,
    val totalBytes: Long
) {
    val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val keyStore = KeyStore(app)
    private val nostrService = NostrService(httpClient)
    private val blossomService = BlossomService(httpClient)
    private val pendingPrune = PendingPruneStore(app)
    val cache = EarmarkCache(app)
    val player = PlayerController(app)

    private val _state = MutableStateFlow<AppState>(AppState.Loading("Starting…"))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(BlossomStats(0, 0, 0))
    val stats: StateFlow<BlossomStats> = _stats.asStateFlow()

    /**
     * Transient, dismissible message channel for background-op failures
     * (delete retries, Nostr relay failures, etc.) that shouldn't hijack
     * the page-level [AppState]. The UI renders these as a dialog with an
     * "OK" button; [dismissNotice] clears it.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    /** Active earmark list — kept in sync with what the player is playing. */
    private var currentEarmarks: List<Earmark> = emptyList()

    val playerState get() = player.state

    private fun recomputeStats() {
        var parts = 0
        var bytes = 0L
        for (e in currentEarmarks) {
            val b = e.blossom ?: continue
            parts += b.chunks.size
            bytes += b.chunks.sumOf { it.size.toLong() }
        }
        _stats.value = BlossomStats(currentEarmarks.size, parts, bytes)
    }

    init {
        player.connect { /* controller ready */ }
        EarmarksMediaService.onDeleteRequested = { deleteCurrent() }
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                _state.value = AppState.Loading("Loading…")

                val privKeyHex = keyStore.getKey()
                if (privKeyHex == null) {
                    _state.value = AppState.KeyMissing
                    return@launch
                }

                _state.value = AppState.Loading("Connecting to Nostr relays…")
                var earmarks = nostrService.fetchEarmarks(privKeyHex)

                // Reconcile any prior delete that crashed/cancelled between
                // blob deletion and Nostr publish. If the fetched list still
                // contains a `ts` we've recorded as pruned, the blobs are
                // already gone — republish the list without those entries
                // before we hand it to the player.
                val pending = pendingPrune.load()
                if (pending.isNotEmpty()) {
                    val stillPresent = earmarks.filter { it.ts in pending }.map { it.ts }
                    if (stillPresent.isNotEmpty()) {
                        _state.value = AppState.Loading(
                            "Reconciling ${stillPresent.size} pending deletion(s)…"
                        )
                        val pruned = earmarks.filterNot { it.ts in pending }
                        val acks = publishWithRetry(privKeyHex, pruned)
                        if (acks > 0) {
                            earmarks = pruned
                            pendingPrune.removeAll(stillPresent)
                        }
                        // If still 0 acks we leave the sentinel intact and
                        // proceed with the (broken) list — playback of those
                        // tracks will fail but the next launch will retry.
                    } else {
                        // The pruned entries are no longer in the published
                        // list (e.g. another client republished). Sentinel
                        // is stale; clear it.
                        pendingPrune.removeAll(pending)
                    }
                }

                if (earmarks.isEmpty()) {
                    _state.value = AppState.Error("No earmarks found")
                    return@launch
                }

                // Update stats as soon as we have the list — even if the download
                // loop below fails for some tracks, the storage panel should
                // reflect the actual list rather than reading "0 earmarks".
                currentEarmarks = earmarks
                recomputeStats()

                // Prune files for earmarks that are no longer in the list
                cache.pruneExpired(earmarks.map { it.ts }.toSet())

                // Download any earmarks not already cached. Each failure is
                // bucketed into orphans (every server 404'd a chunk → definitively
                // gone) or transient (network/5xx/SHA mismatch → retry later).
                // The loop never aborts on a single failure.
                val uncached = earmarks.filter { cache.getCachedFile(it) == null }
                val orphanedTs = mutableListOf<Long>()
                var unavailableCount = 0

                if (uncached.isNotEmpty()) {
                    for ((i, earmark) in uncached.withIndex()) {
                        _state.value = AppState.Downloading(i + 1, uncached.size)
                        val destFile = cache.targetFile(earmark)
                        val result = try {
                            blossomService.downloadAndDecrypt(earmark, destFile)
                        } catch (e: Exception) {
                            BlossomService.DownloadResult.Unavailable(e.message ?: "unknown")
                        }
                        when (result) {
                            is BlossomService.DownloadResult.Success -> {}
                            is BlossomService.DownloadResult.Orphaned -> orphanedTs += earmark.ts
                            is BlossomService.DownloadResult.Unavailable -> unavailableCount++
                        }
                    }
                }

                // Orphan cleanup: blobs are verified gone (404 from every server
                // on at least one chunk). Delete any surviving chunks from other
                // servers (good citizen) and republish the list without these
                // entries. Protected by the same sentinel contract as
                // deleteCurrent so a crash mid-cleanup is recoverable.
                if (orphanedTs.isNotEmpty()) {
                    _state.value = AppState.Loading(
                        "Cleaning up ${orphanedTs.size} orphaned earmark(s)…"
                    )
                    cleanupOrphans(privKeyHex, earmarks, orphanedTs)
                    earmarks = earmarks.filterNot { it.ts in orphanedTs }
                    currentEarmarks = earmarks
                    recomputeStats()
                }

                // Build playlist from all cached files (shuffle happens inside PlayerController)
                val playlist = earmarks.mapNotNull { earmark ->
                    cache.getCachedFile(earmark)?.let { it to earmark }
                }

                if (playlist.isEmpty()) {
                    _state.value = AppState.Error(
                        if (unavailableCount > 0)
                            "$unavailableCount earmark(s) couldn't be fetched — try again later."
                        else "No playable tracks available"
                    )
                    return@launch
                }

                player.setPlaylist(playlist)

                // Write active earmarks to disk for background/Auto offline recovery.
                // Use the shuffled order from the controller so cold starts (Android
                // Auto, background service) replay in the same shuffled sequence.
                try {
                    val file = File(getApplication<Application>().filesDir, "earmarks.json")
                    val json = earmarksToJson(player.getShuffledEarmarks())
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(file)) {
                        file.writeText(json)
                        tmp.delete()
                    }
                } catch (_: Exception) {}

                _state.value = AppState.Playing(earmarks, unavailableCount)

            } catch (e: Exception) {
                _state.value = AppState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Validates and stores a new key from the key entry screen. */
    fun saveKey(input: String): Result<Unit> = runCatching {
        val hex = when {
            input.startsWith("nsec1", ignoreCase = true) ->
                Bech32.decodeNsec(input).toHex()
            input.length == 64 && input.all { it.isHexDigit() } ->
                input.lowercase()
            else -> error("Invalid key — paste an nsec1 or 64-char hex private key")
        }
        viewModelScope.launch { keyStore.saveKey(hex); load() }
    }

    /**
     * Deletes the currently-playing earmark.
     *
     * UX contract: the UI advances IMMEDIATELY. All network work (Blossom
     * blob deletes, Nostr publish, cache cleanup) happens in a background
     * coroutine that never touches the page-level [AppState]. Background
     * failures surface via [_notice] as a dismissible dialog so the user
     * can get back to playback without restarting the app.
     *
     * Reliability contract: once blobs are deleted the Nostr list MUST
     * eventually lose the pointer. Three layered defenses:
     *
     *  1. **Sentinel first.** Before touching any blob we add the earmark's
     *     `ts` to [PendingPruneStore] (on-disk JSON in `filesDir`). If the
     *     process dies at any later step, [load] on next launch republishes
     *     the list with this entry removed.
     *  2. **Retry with backoff.** [publishWithRetry] calls `publishEarmarks`
     *     up to 5 times with exponential backoff (1s/2s/4s/8s/16s) and only
     *     considers publish successful on ≥1 relay ack.
     *  3. **NonCancellable / GlobalScope.** The post-blob phase runs under
     *     [NonCancellable] in [GlobalScope] so backgrounding the app can't
     *     tear down the publish coroutine before it succeeds or persists
     *     the sentinel for next-launch reconciliation.
     *
     * Ordering still matters for the contract: blobs first, then publish.
     * The UI change precedes both but is purely cosmetic — the sentinel is
     * what guarantees eventual convergence.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun deleteCurrent() {
        val earmark = player.currentEarmark() ?: return

        // ---- Immediate, synchronous UI update. ----
        player.removeCurrentItem()
        val updated = currentEarmarks.filterNot { it.ts == earmark.ts }
        currentEarmarks = updated
        recomputeStats()

        // Write active earmarks to disk for background/Auto offline recovery.
        // Use the shuffled order so the order survives a restart.
        try {
            val file = File(getApplication<Application>().filesDir, "earmarks.json")
            val json = earmarksToJson(player.getShuffledEarmarks())
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (_: Exception) {}

        _state.value = if (updated.isEmpty()) {
            AppState.Error("No earmarks left")
        } else {
            AppState.Playing(updated)
        }

        // ---- Background IO. Never writes to _state. ----
        GlobalScope.launch {
            withContext(NonCancellable) {
                try {
                    val privKeyHex = keyStore.getKey() ?: return@withContext
                    pendingPrune.add(earmark.ts)

                    val manifest = earmark.blossom
                    if (manifest != null) {
                        val result = blossomService.deleteManifest(manifest, privKeyHex)
                        if (!result.allSucceeded) {
                            // Sentinel stays put so next launch reconciles.
                            // UI already advanced; surface a dismissible notice.
                            _notice.value =
                                "${result.failed} Blossom blob(s) couldn't be deleted. " +
                                    "Will retry on next launch. " +
                                    "First error: ${result.firstError ?: "unknown"}"
                            return@withContext
                        }
                    }

                    val acks = publishWithRetry(privKeyHex, updated)
                    cache.getCachedFile(earmark)?.delete()

                    if (acks > 0) {
                        pendingPrune.remove(earmark.ts)
                    } else {
                        _notice.value =
                            "Deletion saved locally but couldn't reach any Nostr relay. " +
                                "Will retry on next launch."
                    }
                } catch (e: Exception) {
                    _notice.value =
                        "Delete error: ${e.message ?: "unknown"} — will retry on next launch."
                }
            }
        }
    }

    /**
     * Cleans up earmarks whose Blossom blobs have gone missing (verified by
     * a 404 from every server for at least one chunk). Best-effort deletes any
     * surviving chunks from other servers — we already know the earmark is
     * unplayable, so our only remaining obligation is to avoid leaving orphaned
     * blobs on other people's Blossom servers. Then republishes the earmark
     * list without these entries.
     *
     * Same sentinel discipline as [deleteCurrent]: a pending-prune marker is
     * written BEFORE we touch any blob so that a crash between delete and
     * publish is recoverable on next launch.
     */
    private suspend fun cleanupOrphans(
        privKeyHex: String,
        fullList: List<Earmark>,
        orphanedTs: List<Long>
    ) {
        orphanedTs.forEach { pendingPrune.add(it) }

        // Best-effort cleanup: 404s count as success inside deleteManifest, so
        // servers that already lost the blob are fine; we only care about
        // sweeping away any survivors. A failure here doesn't block republish
        // — we'd rather fix the pointer than get stuck retrying deletes.
        for (ts in orphanedTs) {
            val manifest = fullList.firstOrNull { it.ts == ts }?.blossom ?: continue
            try {
                blossomService.deleteManifest(manifest, privKeyHex)
            } catch (_: Exception) {
                // Intentionally swallowed: the earmark is already broken from the
                // user's perspective; a failed sweep just leaves a few orphans,
                // not a dangling pointer.
            }
        }

        val pruned = fullList.filterNot { it.ts in orphanedTs }
        val acks = publishWithRetry(privKeyHex, pruned)
        if (acks > 0) {
            pendingPrune.removeAll(orphanedTs)
            // Also drop any local cached files for these earmarks.
            orphanedTs.forEach { ts ->
                fullList.firstOrNull { it.ts == ts }?.let { cache.getCachedFile(it)?.delete() }
            }

            // Update saved earmarks list on disk
            try {
                val file = File(getApplication<Application>().filesDir, "earmarks.json")
                val json = earmarksToJson(pruned)
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            } catch (_: Exception) {}
        }
        // If every relay failed, leave the sentinel in place — next launch's
        // reconciliation will retry the publish.
    }

    /**
     * Publishes [earmarks] via [NostrService.publishEarmarks] with exponential
     * backoff. Returns the relay ack count from the first attempt that got
     * ≥1 ack, or 0 if every attempt failed. Catches exceptions per-attempt so
     * a transient signing/network failure doesn't break the loop.
     */
    private suspend fun publishWithRetry(
        privKeyHex: String,
        earmarks: List<Earmark>,
        maxAttempts: Int = 5
    ): Int {
        var delayMs = 1_000L
        repeat(maxAttempts) { attempt ->
            try {
                val acks = nostrService.publishEarmarks(privKeyHex, earmarks)
                if (acks > 0) return acks
            } catch (_: Exception) {
                // fall through to backoff
            }
            if (attempt < maxAttempts - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        return 0
    }

    fun clearKey() {
        viewModelScope.launch { keyStore.clearKey(); _state.value = AppState.KeyMissing }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
