package com.derpy.earmarks.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * How many separate syncs must independently report a track orphaned before it
 * is deleted, and how far apart those reports have to be.
 *
 * The interval is the important half. A count on its own is trivially defeated:
 * opening the app runs a sync, so someone who notices a broken track and
 * reopens the app three times would burn all three strikes in a minute against
 * the same momentarily-dead server. Requiring an hour between counted strikes
 * makes them independent observations rather than three reads of one outage.
 *
 * Three strikes an hour apart, against a three-hour scheduled sync, puts a real
 * orphan's deletion somewhere between two and nine hours after it is first
 * suspected. Nothing depends on that being prompt.
 */
private const val STRIKES_BEFORE_PRUNE = 3
private const val MIN_STRIKE_INTERVAL_MS = 60L * 60L * 1000L

/**
 * Persistent record of how much evidence there is that an earmark's blobs are
 * really gone.
 *
 * Orphan cleanup deletes blobs off Blossom and republishes the earmark list
 * without the entry; both halves are irreversible. The evidence it acts on —
 * every server the client asked answered 404 for at least one chunk — is much
 * weaker than that, because a server that has been reinstalled, restored from
 * backup, or put behind a misconfigured proxy also answers 404 for blobs it
 * still holds. This store is what stops one such answer from being final.
 *
 * Stored in `filesDir` (not `cacheDir`) so it survives the OS clearing the app
 * cache — losing it would reset every count to zero and, more to the point,
 * would do so silently.
 */
class OrphanStrikeStore(
    dir: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(dir, "orphan_strikes.json")

    /**
     * @property count counted strikes so far.
     * @property lastSeen epoch millis of the most recent counted strike.
     */
    data class Strike(val count: Int, val lastSeen: Long)

    @Synchronized
    fun load(): Map<Long, Strike> {
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next().toString()
                    val entry = obj.getJSONObject(key)
                    put(key.toLong(), Strike(entry.getInt("n"), entry.getLong("t")))
                }
            }
        } catch (_: Exception) {
            // Corrupt file: drop it so we don't keep failing. Losing the counts
            // only delays a deletion, which is the safe direction to fail in.
            file.delete()
            emptyMap()
        }
    }

    /**
     * Records that this sync found [ts] orphaned, and reports whether there is
     * now enough evidence to delete it.
     *
     * A report that arrives less than [MIN_STRIKE_INTERVAL_MS] after the last
     * counted one is not counted — it is the same observation again, not a
     * second one — but it does not reset anything either. An entry already at
     * the threshold stays there, so a prune whose Nostr publish failed can be
     * retried on the very next run.
     */
    @Synchronized
    fun record(ts: Long): Boolean {
        val current = load().toMutableMap()
        val existing = current[ts]
        val at = now()

        val updated = when {
            existing == null -> Strike(1, at)
            at - existing.lastSeen < MIN_STRIKE_INTERVAL_MS -> return existing.count >= STRIKES_BEFORE_PRUNE
            else -> Strike(existing.count + 1, at)
        }

        current[ts] = updated
        write(current)
        return updated.count >= STRIKES_BEFORE_PRUNE
    }

    /**
     * Forgets everything recorded about [ts]. Called when a download succeeds:
     * the blobs are demonstrably there, which does not merely fail to add
     * evidence, it contradicts what was recorded.
     */
    @Synchronized
    fun clear(ts: Long) {
        val current = load()
        if (!current.containsKey(ts)) return
        write(current - ts)
    }

    /** Drops entries for earmarks no longer in the list, so the file stays small. */
    @Synchronized
    fun retainOnly(tsValues: Collection<Long>) {
        val current = load()
        val keep = tsValues.toSet()
        val retained = current.filterKeys { it in keep }
        if (retained.size != current.size) write(retained)
    }

    private fun write(values: Map<Long, Strike>) {
        if (values.isEmpty()) {
            file.delete()
            return
        }
        val obj = JSONObject()
        for ((ts, strike) in values) {
            obj.put(ts.toString(), JSONObject().put("n", strike.count).put("t", strike.lastSeen))
        }
        // Atomic-ish replace via temp + rename so a crash mid-write can't leave
        // us with a half-truncated file. Same discipline as PendingPruneStore.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(obj.toString())
            tmp.delete()
        }
    }
}
