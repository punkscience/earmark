package com.derpy.earmarks.data

import android.content.Context
import java.io.File

/**
 * On-disk snapshot of the active earmark list.
 *
 * Two readers depend on this file rather than the relays: [
 * com.derpy.earmarks.player.EarmarksMediaService] rebuilds a playlist from it
 * when Android Auto or a media button starts playback with no UI alive, and the
 * player screen renders it immediately at launch instead of waiting out a relay
 * round-trip.
 *
 * Written atomically — tmp then rename — because a torn write would strand
 * both of those readers on a truncated list until the next successful sync.
 */
class EarmarkStore(context: Context) {
    private val file = File(context.filesDir, "earmarks.json")

    fun load(): List<Earmark> =
        try {
            if (file.exists()) parseEarmarkList(file.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun save(earmarks: List<Earmark>) {
        try {
            val json = earmarksToJson(earmarks)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (_: Exception) {
            // A failed snapshot costs a slower next launch, nothing more. The
            // relays remain the source of truth.
        }
    }
}
