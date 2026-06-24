package com.derpy.earmarks.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import com.derpy.earmarks.data.EarmarkCache
import com.derpy.earmarks.data.parseEarmarkList
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

private const val NOTIFICATION_CHANNEL_ID = "earmarks_playback"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service hosting the ExoPlayer instance.
 *
 * Extending MediaLibraryService provides:
 *  1. Background playback — foreground service keeps audio alive when switching apps.
 *  2. Android Auto — Auto connects via the MediaBrowserService protocol.
 *
 * DefaultMediaNotificationProvider posts a persistent media notification, which is
 * required for the foreground service to survive on Android 12+ and for Android Auto
 * to recognise this app as a media source.
 */
class EarmarksMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // handleAudioBecomingNoisy=true makes ExoPlayer auto-pause when the OS
        // broadcasts ACTION_AUDIO_BECOMING_NOISY — i.e. wired headphones
        // unplugged, Bluetooth A2DP disconnects, BT speaker out of range. Off
        // by default in Media3, so audio would otherwise route to the phone's
        // built-in speaker when the car/headphones drop.
        //
        // setAudioAttributes(..., handleAudioFocus = true) is what makes us a
        // good audio-focus citizen: requests AUDIOFOCUS_GAIN on play (so other
        // media apps pause), ducks during nav prompts (LOSS_TRANSIENT_CAN_DUCK),
        // pauses on phone calls (LOSS_TRANSIENT) and auto-resumes on regain,
        // pauses permanently when another app takes focus (LOSS).
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, SessionCallback()).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        // Auto-load cached playlist on cold start (e.g. when launched from Android Auto)
        try {
            val file = File(filesDir, "earmarks.json")
            if (file.exists()) {
                val json = file.readText()
                val earmarks = parseEarmarkList(json)
                val cache = EarmarkCache(this)
                val items = earmarks.mapNotNull { earmark ->
                    cache.getCachedFile(earmark)?.let { cacheFile ->
                        MediaItem.Builder()
                            .setMediaId(earmark.ts.toString())
                            .setUri(Uri.fromFile(cacheFile))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(earmark.title.ifBlank { cacheFile.name })
                                    .setArtist(earmark.artist.ifBlank { null })
                                    .setAlbumTitle(earmark.album.ifBlank { null })
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .build()
                            )
                            .build()
                    }
                }
                if (items.isNotEmpty()) {
                    player.setMediaItems(items)
                    player.prepare()
                }
            }
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession.release()
        player.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW  // silent but visible
        ).apply { description = "earmarks playback controls" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(ROOT_ITEM, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ITEM.mediaId) {
                // Return empty list if parentId is not root to prevent Android Auto
                // from trying to recursively browse tracks, causing infinite loops/crashes.
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            val timeline = session.player.currentTimeline
            val window = Timeline.Window()
            val items = ImmutableList.builder<MediaItem>()
            for (i in 0 until timeline.windowCount) {
                items.add(timeline.getWindow(i, window).mediaItem)
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items.build(), params))
        }
    }

    companion object {
        private val ROOT_ITEM = MediaItem.Builder()
            .setMediaId("earmarks_root")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }
}
