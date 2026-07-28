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

    fun pruneExpired(activeTsList: Set<Long>) {
        dir.listFiles()?.forEach { file ->
            val ts = file.nameWithoutExtension.removePrefix("earmark_").toLongOrNull()
            if (ts != null && ts !in activeTsList) file.delete()
        }
    }
}
