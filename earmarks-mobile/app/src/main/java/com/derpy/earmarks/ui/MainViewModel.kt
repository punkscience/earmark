package com.derpy.earmarks.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.derpy.earmarks.blossom.BlossomService
import com.derpy.earmarks.blossom.DEFAULT_BLOSSOM_SERVERS
import com.derpy.earmarks.data.ChannelCache
import com.derpy.earmarks.data.ChannelInvite
import com.derpy.earmarks.data.ChannelPost
import com.derpy.earmarks.data.ChannelRepository
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.data.EarmarkCache
import com.derpy.earmarks.data.EarmarkStore
import com.derpy.earmarks.data.KeyStore
import com.derpy.earmarks.data.PendingPruneStore
import com.derpy.earmarks.data.SettingsStore
import com.derpy.earmarks.net.Http
import com.derpy.earmarks.nostr.Bech32
import com.derpy.earmarks.nostr.NostrService
import com.derpy.earmarks.nostr.publishWithRetry
import com.derpy.earmarks.player.EarmarksMediaService
import com.derpy.earmarks.player.PlayerController
import com.derpy.earmarks.sync.EarmarkSyncWorker
import com.derpy.earmarks.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppState {
    object KeyMissing : AppState
    data class Loading(val message: String) : AppState
    /**
     * [unavailable] is the number of earmarks in the list that have no playable
     * file on disk yet — still downloading, or failed for transient reasons
     * (network, 5xx, SHA mismatch). They're still in the published list; we
     * just can't play them right now. Once an earmark has been confirmed gone
     * from Blossom by enough separate syncs it is pruned silently and stops
     * contributing; while it is still only suspected it counts here, which is
     * the honest reading — it is in your list and it will not play.
     */
    data class Playing(val earmarks: List<Earmark>, val unavailable: Int = 0) : AppState
    data class Error(val message: String) : AppState
}

/**
 * Progress of the background sync, for the non-blocking banner.
 *
 * Deliberately separate from [AppState]: downloading used to be a page-level
 * state that hid the player until every track had landed, so a twelve-track
 * sync meant twelve tracks of silence. Now whatever is already cached plays
 * immediately and this reports what is still arriving.
 */
data class SyncStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val title: String = ""
)

/**
 * What the player is currently drawing its playlist from. Channels do not merge
 * into the personal list — you are always listening to exactly one source, and
 * the chip row says which.
 */
sealed interface PlayerSource {
    object MyEarmarks : PlayerSource
    data class Channel(val id: String, val name: String) : PlayerSource
}

/** Channel-related UI state, kept separate from the player's own state. */
data class ChannelUiState(
    val channels: List<com.derpy.earmarks.data.Channel> = emptyList(),
    val invites: List<ChannelInvite> = emptyList(),
    val postsByChannel: Map<String, List<ChannelPost>> = emptyMap(),
    val source: PlayerSource = PlayerSource.MyEarmarks,
    val syncing: Boolean = false
) {
    val pendingInviteCount: Int get() = invites.size
    fun postsFor(chanId: String): List<ChannelPost> = postsByChannel[chanId] ?: emptyList()
}

/** Aggregate Blossom storage stats derived from the current earmark list. */
data class BlossomStats(
    val totalEarmarks: Int,
    val totalParts: Int,
    val totalBytes: Long
) {
    val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
}

/**
 * The least time between two foreground-triggered channel syncs. Long enough
 * that flipping between apps costs no relay traffic, short enough that "make a
 * channel on the desktop, pick up the phone" just works.
 */
internal const val CHANNEL_RESYNC_MIN_INTERVAL_MS = 60_000L

/** Whether enough time has passed since [lastSyncMs] to warrant another sync. */
internal fun shouldResyncChannels(lastSyncMs: Long, nowMs: Long): Boolean =
    lastSyncMs == 0L || nowMs - lastSyncMs >= CHANNEL_RESYNC_MIN_INTERVAL_MS

/**
 * App states a foreground channel re-sync may run in: the player is up, or
 * startup settled on an error screen. Never mid-startup — Loading runs its own
 * sync when it finishes — and never before a key exists.
 */
internal fun canSyncChannels(state: AppState): Boolean =
    state is AppState.Playing || state is AppState.Error

/**
 * The earmarks a source draws its playlist from.
 *
 * The one place the two stashes are told apart. Sources never merge: the
 * personal list yields only what you published, a channel yields only what was
 * posted to that channel, and a channel with no posts yields nothing rather
 * than falling back to anything.
 */
internal fun earmarksForSource(
    source: PlayerSource,
    personal: List<Earmark>,
    postsByChannel: Map<String, List<ChannelPost>>
): List<Earmark> = when (source) {
    is PlayerSource.MyEarmarks -> personal
    is PlayerSource.Channel -> postsByChannel[source.id]?.map { it.earmark } ?: emptyList()
}

/**
 * Whether deleting is meaningful for what [source] is playing.
 *
 * Only your own stash. A channel track belongs to whoever posted it: deleting
 * one would republish your list from a playlist that was never yours and sweep
 * another member's blobs off Blossom.
 */
internal fun canDeleteFrom(source: PlayerSource): Boolean = source is PlayerSource.MyEarmarks

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val keyStore = KeyStore(app)
    private val nostrService = NostrService(Http.client)
    private val blossomService = BlossomService(Http.client)
    private val pendingPrune = PendingPruneStore(app)
    private val channelCache = ChannelCache(app)
    private val store = EarmarkStore(app)
    private val settings = SettingsStore(app)
    val cache = EarmarkCache(app)
    val player = PlayerController(app)

    private val _state = MutableStateFlow<AppState>(AppState.Loading("Starting…"))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** Whether background downloads may run on a metered connection. */
    val downloadOnMobileData: StateFlow<Boolean> = settings.downloadOnMobileData
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setDownloadOnMobileData(enabled: Boolean) {
        viewModelScope.launch {
            settings.setDownloadOnMobileData(enabled)
            // Re-register so the periodic pass picks up the new network
            // constraint instead of waiting for the next reinstall.
            SyncScheduler.ensurePeriodicSync(getApplication())
        }
    }

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
        restoreChannelSnapshot()
        observeSync()
        load()
    }

    /**
     * Renders the last synced channel state immediately, before any network.
     * Without this, every cold start hides the chip row until a full relay
     * round-trip completes — one slow relay stalls it for its whole timeout.
     * The live sync that follows replaces the snapshot.
     */
    private fun restoreChannelSnapshot(force: Boolean = false) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { channelCache.load() } ?: return@launch
            // A live sync may already have landed; never clobber fresher data.
            // [force] is for the background worker's snapshot, which by
            // definition is the freshest thing there is.
            if (!force && lastChannelSyncMs != 0L) return@launch
            val now = System.currentTimeMillis() / 1000
            _channelState.value = _channelState.value.copy(
                channels = cached.state.channels,
                invites = cached.state.invites,
                postsByChannel = cached.posts.filterNot { it.expired(now) }.groupBy { it.chan }
            )
            // Posts the snapshot brought back are playlist input for whichever
            // channel is selected.
            refreshPlaylist()
        }
    }

    /**
     * Brings the screen up from what is already on disk, then asks for a sync.
     *
     * No network happens here any more. The relay fetch and the downloads live
     * in [EarmarkSyncWorker], which survives the app being backgrounded — the
     * whole point of the exercise. This method's job is to get sound out of the
     * speaker as fast as possible from the last snapshot, and then let the
     * worker's results arrive underneath it through [observeSync].
     */
    fun load() {
        viewModelScope.launch {
            if (keyStore.getKey() == null) {
                _state.value = AppState.KeyMissing
                return@launch
            }
            refreshPlaylist()
            SyncScheduler.syncNow(getApplication())
        }
    }

    /**
     * Rebuilds the playlist for whichever source is selected, from what is
     * already on disk. Called at launch, on every sync emission, on every
     * channel sync and whenever the chip changes, so tracks appear as they land
     * rather than after the whole batch.
     *
     * The single writer of the player's playlist, deliberately. It used to be
     * two — the personal stash here and channel feeds in [selectSource] — with
     * this one guarded by an early return on the selected source. That guard
     * was read before the snapshot was loaded off disk and never re-read after,
     * so a sync tick that began while the personal stash was selected could
     * finish after the user had tapped a channel chip and quietly replace that
     * channel's playlist with the personal one. Flipping between chips during a
     * sync hit it repeatedly.
     */
    private suspend fun refreshPlaylist() {
        val source = _channelState.value.source
        val personal = withContext(Dispatchers.IO) { store.load() }

        // Both always describe the personal stash whatever is playing: the
        // storage panel measures your own Blossom usage, and [keepPost]
        // appends to your own list.
        currentEarmarks = personal
        recomputeStats()

        // The chip can change while the snapshot is read. Whichever source was
        // tapped last owns the playlist; this run's result is stale.
        if (_channelState.value.source != source) return

        val earmarks = earmarksForSource(source, personal, _channelState.value.postsByChannel)
        val playlist = earmarks.mapNotNull { earmark ->
            cache.getCachedFile(earmark)?.let { it to earmark }
        }

        if (playlist.isEmpty()) {
            // Nothing of this source is playable, so the player must not keep
            // holding the last one's tracks — the chip would name a source and
            // the speaker would play another.
            player.clearPlaylist()
            _state.value = emptyStateFor(source)
            return
        }

        player.setPlaylist(playlist)
        _state.value = AppState.Playing(earmarks, earmarks.size - playlist.size)
    }

    /**
     * What to say when the selected source has nothing playable on disk.
     *
     * For the personal stash this means one of three different things. On a
     * fresh install there is no snapshot at all, so announcing an empty stash
     * before the first sync has even started would be wrong every time. A sync
     * that is scheduled but not running is waiting on its constraints — say so,
     * rather than leaving a spinner that never resolves. Only once a run has
     * actually finished is an empty stash the real answer, and then it is worth
     * saying, because for an invited account channels are how music first
     * arrives.
     *
     * For a channel there are two: an empty channel is correct, not broken —
     * there is no backfill, so a channel you just joined has nothing in it —
     * and a channel with posts whose audio has not landed yet is downloading.
     */
    private fun emptyStateFor(source: PlayerSource): AppState = when (source) {
        is PlayerSource.MyEarmarks -> when {
            syncCompletedOnce -> AppState.Error(
                "No earmarks in your stash yet. Channel invites and " +
                    "shared tracks still appear in the row above."
            )
            _syncStatus.value.running -> AppState.Loading("Checking for new tracks…")
            else -> AppState.Loading("Waiting for a connection…")
        }
        is PlayerSource.Channel ->
            if (_channelState.value.postsFor(source.id).isEmpty()) AppState.Error(
                "Nothing posted to ${source.name} yet. " +
                    "Tracks shared from now on appear here."
            ) else AppState.Loading("Fetching these tracks…")
    }

    /**
     * Mirrors the background sync into [syncStatus], and refreshes the playlist
     * whenever a run finishes so newly downloaded tracks become playable
     * without reopening the app.
     */
    /**
     * Whether a sync has finished at least once since launch. Guards the
     * difference between "your stash is empty" and "we haven't looked yet".
     */
    private var syncCompletedOnce = false

    private fun observeSync() {
        viewModelScope.launch {
            var wasRunning = false
            SyncScheduler.syncState(getApplication()).collect { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                _syncStatus.value = if (running != null) {
                    SyncStatus(
                        running = true,
                        done = running.progress.getInt(EarmarkSyncWorker.KEY_DONE, 0),
                        total = running.progress.getInt(EarmarkSyncWorker.KEY_TOTAL, 0),
                        title = running.progress.getString(EarmarkSyncWorker.KEY_TITLE) ?: ""
                    )
                } else {
                    SyncStatus()
                }

                // A run that reached a terminal state counts as having looked,
                // even if it never got as far as RUNNING — otherwise a sync
                // that fails outright leaves the screen waiting forever.
                if (infos.any { it.state.isFinished }) syncCompletedOnce = true

                if (wasRunning && running == null) {
                    syncCompletedOnce = true
                    restoreChannelSnapshot(force = true)
                }
                // Refresh on every emission, not only at the end of a run:
                // progress ticks between tracks, so each track that finishes
                // downloading joins the playlist as it lands. setPlaylist keeps
                // the loaded track and position, so this never interrupts what
                // is already playing.
                refreshPlaylist()
                wasRunning = running != null
            }
        }
    }

    // --- Channels -----------------------------------------------------------

    private val channelRepo = ChannelRepository(nostrService)

    private val _channelState = MutableStateFlow(ChannelUiState())
    val channelState: StateFlow<ChannelUiState> = _channelState.asStateFlow()

    /** When the last channel sync started, for [syncChannelsIfStale]'s throttle. */
    private var lastChannelSyncMs = 0L

    /**
     * Re-syncs channels when the last sync is old enough to matter.
     *
     * Called on every return to the foreground. Channel state changes on other
     * devices while this process sits cached in the background — without this,
     * a channel created on the desktop never appears until Android happens to
     * kill the process. Skipped while startup is still in flight, because
     * startup runs its own sync and channels stay secondary to first sound —
     * but allowed in Error: an invited account with an empty stash lives on
     * that screen, and channels are how music first arrives for it.
     */
    fun syncChannelsIfStale(nowMs: Long = System.currentTimeMillis()) {
        if (!canSyncChannels(_state.value)) return
        if (_channelState.value.syncing) return
        if (!shouldResyncChannels(lastChannelSyncMs, nowMs)) return
        syncChannels()
    }

    /**
     * Pulls channel messages, applies roster changes, and refreshes the feed.
     *
     * A relay hiccup while syncing channels must not take the player down, so
     * the failure path deliberately never touches [_state] — it lands in
     * [_notice] instead. The success path does, through [refreshPlaylist],
     * because posts that just arrived are what a selected channel plays.
     */
    fun syncChannels() {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            lastChannelSyncMs = System.currentTimeMillis()
            _channelState.value = _channelState.value.copy(syncing = true)
            try {
                val sync = channelRepo.sync(privKeyHex)
                _channelState.value = _channelState.value.copy(
                    channels = sync.state.channels,
                    invites = sync.state.invites,
                    postsByChannel = sync.posts.groupBy { it.chan },
                    syncing = false
                )
                withContext(Dispatchers.IO) { channelCache.save(sync) }
                // New posts are playlist input for whichever channel is
                // selected; without this they wait for the next chip tap.
                refreshPlaylist()
            } catch (e: Exception) {
                _channelState.value = _channelState.value.copy(syncing = false)
                _notice.value = "Could not sync channels: ${e.message ?: "unknown error"}"
            }
        }
    }

    /**
     * Switches the player between the personal stash and a channel feed.
     *
     * Channel tracks are downloaded and decrypted the same way personal ones
     * are — the file key travels inside the post, so from here on there is no
     * difference between a track you uploaded and one someone sent you.
     *
     * Deliberately does not download. The sync worker is the only downloader in
     * the app, which is what guarantees that nothing else is appending to the
     * same `.part` file — two writers would interleave chunks into a file of
     * exactly the right length holding the wrong audio, which no length check
     * can catch. It also means backgrounding mid-fetch keeps its progress,
     * where a download started from here would not.
     */
    fun selectSource(source: PlayerSource) {
        viewModelScope.launch {
            _channelState.value = _channelState.value.copy(source = source)
            refreshPlaylist()
            SyncScheduler.syncNow(getApplication())
        }
    }

    /**
     * Adopts a channel post into the user's own stash: the chunks are mirrored
     * onto their own Blossom servers and the earmark is added to their personal
     * list with a fresh 30-day clock they control.
     *
     * Nothing is re-encrypted and no audio moves through the phone — the
     * destination servers fetch the blobs themselves.
     */
    fun keepPost(post: ChannelPost) {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            val manifest = post.earmark.blossom ?: return@launch
            try {
                val mirrored = manifest.chunks.map { chunk ->
                    val hosted =
                        blossomService.mirrorChunk(chunk, DEFAULT_BLOSSOM_SERVERS, privKeyHex)
                    chunk.copy(servers = (hosted + chunk.servers).distinct())
                }
                val adopted = post.earmark.copy(
                    ts = System.currentTimeMillis() / 1000,
                    blossom = manifest.copy(chunks = mirrored)
                )
                val updated = currentEarmarks + adopted
                if (nostrService.publishWithRetry(privKeyHex, updated) == 0) {
                    _notice.value = "Could not save to your list — no relay accepted it."
                    return@launch
                }
                currentEarmarks = updated
                recomputeStats()
                _notice.value = "Kept \"${post.earmark.title}\". It is yours now, " +
                    "with a fresh 30-day clock."
            } catch (e: Exception) {
                _notice.value = "Could not keep that track: ${e.message ?: "unknown error"}"
            }
        }
    }

    /**
     * Posts a track already held — one of your own, or one received from
     * another channel — into [chanId]. Zero bytes move: the post hands over the
     * existing chunk hashes and file key.
     */
    fun shareToChannel(earmark: Earmark, chanId: String) {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            val name = _channelState.value.channels
                .firstOrNull { it.descriptor.id == chanId }?.descriptor?.name ?: "channel"
            channelRepo.post(privKeyHex, chanId, earmark)
                .onSuccess { _notice.value = "Shared \"${earmark.title}\" to $name." }
                .onFailure {
                    _notice.value = "Could not share to $name: ${it.message ?: "unknown error"}"
                }
        }
    }

    /**
     * Keeps whatever is playing, when it came from a channel. Resolves the
     * playing earmark back to its post so the original chunk servers are known
     * — those are what the mirror copies from.
     */
    fun keepCurrentChannelTrack() {
        val playing = player.currentEarmark() ?: return
        val source = _channelState.value.source as? PlayerSource.Channel ?: return
        val post = _channelState.value.postsFor(source.id).firstOrNull { it.earmark.ts == playing.ts }
        if (post == null) {
            _notice.value = "That track is no longer in the channel feed."
            return
        }
        keepPost(post)
    }

    /** Shares whatever is playing into [chanId], from either source. */
    fun shareCurrentTrack(chanId: String) {
        val playing = player.currentEarmark() ?: return
        shareToChannel(playing, chanId)
    }

    fun acceptInvite(chanId: String) {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            channelRepo.acceptInvite(privKeyHex, chanId)
                .onSuccess { channel ->
                    _notice.value = "Joined \"${channel.descriptor.name}\". " +
                        "Tracks posted from now on will appear here — channels do not backfill."
                    syncChannels()
                }
                .onFailure { _notice.value = it.message ?: "Could not accept that invite." }
        }
    }

    fun declineInvite(chanId: String) {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            channelRepo.declineInvite(privKeyHex, chanId)
            syncChannels()
        }
    }

    fun leaveChannel(chanId: String) {
        viewModelScope.launch {
            val privKeyHex = keyStore.getKey() ?: return@launch
            channelRepo.leave(privKeyHex, chanId)
            if ((_channelState.value.source as? PlayerSource.Channel)?.id == chanId) {
                selectSource(PlayerSource.MyEarmarks)
            }
            syncChannels()
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

        // Deleting from a channel feed would republish the personal list from a
        // playlist that was never in it and sweep the poster's blobs off
        // Blossom. The UI disables the button; Android Auto's custom action
        // routes here too, so the guard lives at the source.
        if (!canDeleteFrom(_channelState.value.source)) {
            _notice.value = "Delete only works on your own earmarks. " +
                "Switch to My Earmarks first."
            return
        }

        // ---- Immediate, synchronous UI update. ----
        player.removeCurrentItem()
        val updated = currentEarmarks.filterNot { it.ts == earmark.ts }
        currentEarmarks = updated
        recomputeStats()

        // Write active earmarks to disk for background/Auto offline recovery.
        // Use the shuffled order so the order survives a restart.
        store.save(player.getShuffledEarmarks())

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

                    val acks = nostrService.publishWithRetry(privKeyHex, updated)
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
