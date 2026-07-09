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

        // If we already have a populated timeline and the player is active (playing/ready/buffering),
        // we can preserve the active session's playlist so we don't interrupt active background/Auto playback.
        if (mc.mediaItemCount > 0 && (mc.isPlaying || mc.playbackState == Player.STATE_BUFFERING || mc.playbackState == Player.STATE_READY)) {
            shuffledEarmarks.clear()
            val timeline = mc.currentTimeline
            val window = Timeline.Window()
            for (i in 0 until mc.mediaItemCount) {
                val mediaItem = mc.getMediaItemAt(i)
                val ts = mediaItem.mediaId.toLongOrNull()
                val earmark = files.firstOrNull { it.second.ts == ts }?.second
                if (earmark != null) {
                    shuffledEarmarks.add(earmark)
                }
            }
            // If we successfully mapped the active session's playlist, sync our states and keep it running
            if (shuffledEarmarks.size == mc.mediaItemCount) {
                _state.value = _state.value.copy(totalTracks = shuffledEarmarks.size)
                updateState()
                return
            }
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
        // Load the playlist ready to play but DON'T start automatically.
        // The user controls when playback begins via the play button — autoplay
        // was startling, especially when coming back to the app in the car.
        mc.run {
            setMediaItems(items)
            prepare()
        }
        _state.value = _state.value.copy(totalTracks = shuffled.size)
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
