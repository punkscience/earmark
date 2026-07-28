package com.derpy.earmarks.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Persistent set of earmark `ts` values whose Blossom blobs have been deleted
 * (or are being deleted) but whose entry has not yet been confirmed removed
 * from the published Nostr earmark list.
 *
 * Crash-safety contract: a `ts` is added BEFORE any blob delete starts, and
 * is only removed AFTER `publishEarmarks` has been ack'd by ≥1 relay. On the
 * next launch, [MainViewModel.load] inspects this set and republishes the
 * earmark list with these entries filtered out — closing the window where
 * process death between blob delete and Nostr publish would otherwise leave
 * a permanently broken pointer.
 *
 * Stored in `filesDir` (not `cacheDir`) so it survives the OS clearing the
 * app cache. Format is a tiny JSON array of longs.
 */
class PendingPruneStore(context: Context) {
    private val file = File(context.filesDir, "pending_prune.json")

    @Synchronized
    fun load(): Set<Long> {
        if (!file.exists()) return emptySet()
        return try {
            val arr = JSONArray(file.readText())
            buildSet { for (i in 0 until arr.length()) add(arr.getLong(i)) }
        } catch (_: Exception) {
            // Corrupt file: drop it so we don't keep failing.
            file.delete()
            emptySet()
        }
    }

    @Synchronized
    fun add(ts: Long) {
        val current = load().toMutableSet()
        if (current.add(ts)) write(current)
    }

    @Synchronized
    fun remove(ts: Long) {
        val current = load().toMutableSet()
        if (current.remove(ts)) write(current)
    }

    @Synchronized
    fun removeAll(tsValues: Collection<Long>) {
        val current = load().toMutableSet()
        if (current.removeAll(tsValues.toSet())) write(current)
    }

    private fun write(values: Set<Long>) {
        if (values.isEmpty()) {
            file.delete()
            return
        }
        val arr = JSONArray()
        for (v in values) arr.put(v)
        // Atomic-ish replace via temp + rename so a crash mid-write can't
        // leave us with a half-truncated file.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
