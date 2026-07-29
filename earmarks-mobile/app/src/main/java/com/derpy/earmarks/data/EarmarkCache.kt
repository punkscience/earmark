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
     * Drops cached audio and abandoned partial downloads for earmarks that have
     * fallen off the published list. Partials are swept on the same rule as
     * finished files — a download for a track you no longer hold is dead
     * weight, and without this it would sit there until reinstall.
     */
    fun pruneExpired(activeTsList: Set<Long>) {
        sweep(dir, activeTsList)
        sweep(partDir, activeTsList)
    }

    private fun sweep(target: File, activeTsList: Set<Long>) {
        target.listFiles()?.forEach { file ->
            // Parse off the full name rather than nameWithoutExtension: a
            // partial is "earmark_<ts>.mp3.part", whose nameWithoutExtension
            // still carries the audio extension and would never parse.
            val ts = file.name.removePrefix("earmark_").substringBefore('.').toLongOrNull()
            if (ts != null && ts !in activeTsList) file.delete()
        }
    }
}
