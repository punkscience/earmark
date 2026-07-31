package com.derpy.earmarks.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.derpy.earmarks.data.Earmark
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlayerState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val currentIndex: Int = 0,
    val totalTracks: Int = 0,
    /**
     * Embedded album artwork from the audio file's tags (ID3 APIC, Vorbis
     * METADATA_BLOCK_PICTURE, etc.). ExoPlayer's extractor reads these into
     * [MediaMetadata.artworkData]; we surface them verbatim so the UI can
     * decode them with BitmapFactory. Null when the track has no embedded art.
     */
    val artworkData: ByteArray? = null
)

/**
 * Whether the media IDs already loaded in the player's timeline are exactly the
 * tracks in the incoming playlist. Order is irrelevant — the timeline keeps its
 * own shuffle — only membership matters.
 */
internal fun sameTrackSet(timelineIds: List<String>, newTs: List<Long>): Boolean =
    timelineIds.toSet() == newTs.map { it.toString() }.toSet()

/**
 * Where the currently-loaded track lands in a rebuilt playlist, or -1 when it
 * is not in it.
 *
 * Handing this index and the current playback position back to the player is
 * what lets the playlist grow — as background downloads complete — without
 * interrupting what is already playing.
 */
internal fun resumeIndex(currentMediaId: String?, newTs: List<Long>): Int {
    if (currentMediaId == null) return -1
    return newTs.indexOfFirst { it.toString() == currentMediaId }
}

class PlayerController(private val context: Context) {

    private var controller: MediaController? = null

    /** Shuffled earmark list that mirrors the MediaItems given to the controller. */
    private val shuffledEarmarks = mutableListOf<Earmark>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Returns the shuffled list that matches the player's current timeline order. */
    fun getShuffledEarmarks(): List<Earmark> = shuffledEarmarks.toList()

    /** Returns the earmark currently playing, or null if nothing is loaded. */
    fun currentEarmark(): Earmark? {
        val mc = controller ?: return null
        val idx = mc.currentMediaItemIndex
        return shuffledEarmarks.getOrNull(idx)
    }

    /**
     * Removes the currently-playing item from the playlist and advances to
     * the next track. Returns the removed earmark, or null if nothing was
     * playing.
     *
     * Order matters: we seek to the next item FIRST (which produces a clean
     * onMediaItemTransition event with the next item's metadata), then
     * remove the now-unselected item from the timeline. Going the other
     * direction (removeMediaItem on the currently-playing item) sometimes
     * fails to propagate a fresh metadata snapshot to the MediaController
     * proxy, leaving the UI stuck on the deleted title even after audio has
     * moved on. seekToNext + remove avoids that path entirely.
     */
    fun removeCurrentItem(): Earmark? {
        val mc = controller ?: return null
        val idx = mc.currentMediaItemIndex
        if (idx < 0 || idx >= shuffledEarmarks.size) return null
        val removed = shuffledEarmarks.removeAt(idx)

        if (shuffledEarmarks.isNotEmpty()) {
            // With REPEAT_MODE_ALL, seekToNextMediaItem wraps from last→first.
            mc.seekToNextMediaItem()
            // Timeline still contains the deleted item; it's at `idx` because
            // we haven't removed anything yet from the controller's view.
            mc.removeMediaItem(idx)
        } else {
            // Last item: no next to seek to. Just remove; player enters ENDED.
            mc.removeMediaItem(idx)
        }
        return removed
    }

    fun connect(onConnected: () -> Unit) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, EarmarksMediaService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            controller = future.get().also { mc ->
                mc.repeatMode = Player.REPEAT_MODE_ALL
                mc.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) = updateState()
                    override fun onPlaybackStateChanged(state: Int) = updateState()
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) = updateState()
                })
                onConnected()
            }
        }, MoreExecutors.directExecutor())
    }

    fun setPlaylist(files: List<Pair<File, Earmark>>) {
        val mc = controller ?: return

        val timelineIds = (0 until mc.mediaItemCount).map { mc.getMediaItemAt(it).mediaId }
        val active = mc.isPlaying ||
            mc.playbackState == Player.STATE_BUFFERING ||
            mc.playbackState == Player.STATE_READY

        // An active session (background/Auto playback) already holding exactly
        // this set of tracks keeps its playlist and order untouched. The set
        // comparison is load-bearing: preserving on "active" alone froze the
        // timeline at whatever partial playlist first reached READY, so tracks
        // downloaded later never became playable until the process died.
        if (active && timelineIds.isNotEmpty() && sameTrackSet(timelineIds, files.map { it.second.ts })) {
            shuffledEarmarks.clear()
            for (id in timelineIds) {
                val ts = id.toLongOrNull()
                files.firstOrNull { it.second.ts == ts }?.let { shuffledEarmarks.add(it.second) }
            }
            _state.value = _state.value.copy(totalTracks = shuffledEarmarks.size)
            updateState()
            return
        }

        val shuffled = files.shuffled()
        shuffledEarmarks.clear()
        shuffledEarmarks.addAll(shuffled.map { it.second })
        val items = shuffled.map { (file, earmark) ->
            MediaItem.Builder()
                .setMediaId(earmark.ts.toString())
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(earmark.title.ifBlank { file.name })
                        .setArtist(earmark.artist.ifBlank { null })
                        .setAlbumTitle(earmark.album.ifBlank { null })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }
        // When the loaded track survives into the new playlist, hand over
        // mid-note: same item, same position, playback never stops.
        //
        // Deliberately not conditioned on isPlaying. The playlist now grows
        // underneath the user as background downloads land, and a paused
        // listener is still a listener — restarting them at a random track
        // because they happened to be between songs would be its own bug.
        val currentId = mc.currentMediaItem?.mediaId
        val keepIdx = resumeIndex(currentId, shuffled.map { it.second.ts })
        // Load the playlist ready to play but DON'T start automatically.
        // The user controls when playback begins via the play button — autoplay
        // was startling, especially when coming back to the app in the car.
        mc.run {
            if (keepIdx >= 0) setMediaItems(items, keepIdx, mc.currentPosition)
            else setMediaItems(items)
            prepare()
        }
        _state.value = _state.value.copy(totalTracks = shuffled.size)
    }

    /**
     * Drops every loaded track. Called when the selected source has nothing
     * playable, so the player never keeps holding the previous source's
     * tracks while the chip row names a different one.
     */
    fun clearPlaylist() {
        val mc = controller ?: return
        if (mc.mediaItemCount == 0) return
        shuffledEarmarks.clear()
        mc.clearMediaItems()
    }

    fun playPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun skipNext() { controller?.seekToNextMediaItem() }
    fun skipPrevious() { controller?.seekToPreviousMediaItem() }

    fun release() { controller?.release(); controller = null }

    private fun updateState() {
        val mc = controller ?: return
        // Title/artist/album come from the MediaItem's static metadata (what
        // we built in setPlaylist) because mc.mediaMetadata sometimes lags
        // during timeline edits and would briefly show the deleted item's
        // text. artworkData has to come from mc.mediaMetadata though — it's
        // populated by ExoPlayer's extractor after the file is decoded,
        // never by us at MediaItem-build time.
        val staticMeta = mc.currentMediaItem?.mediaMetadata
        val liveMeta = mc.mediaMetadata
        val textMeta = staticMeta ?: liveMeta
        _state.value = PlayerState(
            isPlaying = mc.isPlaying,
            title = textMeta.title?.toString() ?: "",
            artist = textMeta.artist?.toString() ?: "",
            album = textMeta.albumTitle?.toString() ?: "",
            currentIndex = mc.currentMediaItemIndex,
            totalTracks = mc.mediaItemCount,
            artworkData = liveMeta.artworkData
        )
    }
}
