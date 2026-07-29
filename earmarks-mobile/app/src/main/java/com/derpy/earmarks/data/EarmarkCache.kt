package com.derpy.earmarks.data

import android.content.Context
import java.io.File

/**
 * On-disk store for downloaded+decrypted earmark audio.
 *
 * Lives in `context.filesDir` (not `context.cacheDir`) because Android may
 * wipe `cacheDir` at any time when storage runs low — and OEM "smart
 * cleaners" do so on a schedule — which made earmarks silently re-download
 * on every cellular launch. The contract per the spec is: files stay until
 * their `ts` falls off the published Nostr list, at which point
 * [pruneExpired] deletes them.
 */
class EarmarkCache(context: Context) {
    private val dir = File(context.filesDir, "earmarks").also { it.mkdirs() }

    /**
     * Staging area for downloads still in progress. Kept beside the cache
     * rather than inside it so nothing enumerating cached tracks ever has to
     * reason about partial files.
     */
    private val partDir = File(context.filesDir, "earmark-downloads").also { it.mkdirs() }

    init {
        // One-shot migration from the pre-fix cacheDir location. renameTo can
        // fail across filesystems on some devices, so fall back to copy+delete
        // rather than forcing a re-download.
        val legacy = File(context.cacheDir, "earmarks")
        if (legacy.isDirectory) {
            legacy.listFiles()?.forEach { src ->
                val dest = File(dir, src.name)
                if (dest.exists()) {
                    src.delete()
                } else if (!src.renameTo(dest)) {
                    try {
                        src.copyTo(dest, overwrite = false)
                        src.delete()
                    } catch (_: Exception) { /* leave for next launch */ }
                }
            }
            legacy.delete()
        }
    }

    fun listCachedTs(): Set<Long> =
        dir.listFiles()
            ?.mapNotNull { f -> f.nameWithoutExtension.removePrefix("earmark_").toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun getCachedFile(earmark: Earmark): File? =
        targetFile(earmark).takeIf { it.exists() && it.length() > 0 }

    fun targetFile(earmark: Earmark): File =
        File(dir, "earmark_${earmark.ts}${earmark.blossom!!.ext}")

    /**
     * Where an in-progress download accumulates. Chunks append here across
     * sessions and the file only moves to [targetFile] once it is whole, so an
     * interrupted download is never mistaken for a playable track.
     */
    fun partFile(earmark: Earmark): File =
        File(partDir, "earmark_${earmark.ts}${earmark.blossom!!.ext}.part")

    /**
     * Drops cached audio for earmarks that have fallen off the published list,
     * and sweeps away abandoned partial downloads.
     *
     * Partials are pruned by age rather than by list membership. Membership is
     * the wrong test for them: a partial is by definition a track that has not
     * finished arriving, and the caller cannot always name every list it might
     * belong to at the moment it prunes — sweeping on membership would delete a
     * download in progress and hand back exactly the restart-from-nothing this
     * cache exists to prevent. Age is unambiguous: nothing has appended to this
     * file in [PART_MAX_AGE_DAYS] days, so nothing is going to.
     */
    fun pruneExpired(activeTsList: Set<Long>, nowMs: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { file ->
            val ts = parseTs(file)
            if (ts != null && ts !in activeTsList) file.delete()
        }
        val cutoff = nowMs - PART_MAX_AGE_DAYS * 24 * 60 * 60 * 1000L
        partDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    /**
     * Reads the `ts` out of a cache filename. Parses the full name rather than
     * `nameWithoutExtension` so that "earmark_<ts>.mp3.part" — whose
     * `nameWithoutExtension` still carries the audio extension — resolves too.
     */
    private fun parseTs(file: File): Long? =
        file.name.removePrefix("earmark_").substringBefore('.').toLongOrNull()

    private companion object {
        /**
         * Long enough to outlast a phone that has been off Wi-Fi for a week,
         * short enough that a genuinely dead download does not squat on disk
         * until reinstall.
         */
        const val PART_MAX_AGE_DAYS = 14L
    }
}
