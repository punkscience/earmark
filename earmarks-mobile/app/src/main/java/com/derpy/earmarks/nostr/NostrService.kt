package com.derpy.earmarks.nostr

import android.util.Log
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.data.earmarksToJson
import com.derpy.earmarks.data.parseEarmarkList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * What one relay said when asked for a single event. [Empty] is a relay that
 * answered EOSE holding nothing; [Unreachable] is a relay that never answered
 * at all. The two used to collapse into one `null`, and that ambiguity is how
 * an offline launch read as "your channel state does not exist" — which the
 * sync then cached, and could even publish, over the real thing.
 */
internal sealed interface QueryOutcome {
    data class Found(val event: JSONObject) : QueryOutcome
    object Empty : QueryOutcome
    object Unreachable : QueryOutcome
}

/**
 * The newest event across per-relay outcomes, or null when the event genuinely
 * does not exist.
 *
 * Absence is only trusted when every relay actually answered. Addressable
 * events live on a subset of the relays — the channel state exists on one of
 * the two defaults today — so "the reachable relay had nothing" must not be
 * read as "it's gone" while another relay is down.
 *
 * @throws IOException when nothing was found and at least one relay was
 *   unreachable — the caller cannot tell absence from outage, so it must not
 *   act on either.
 */
internal fun resolveNewest(outcomes: List<QueryOutcome>): JSONObject? {
    val found = outcomes.filterIsInstance<QueryOutcome.Found>().map { it.event }
    found.maxByOrNull { it.getLong("created_at") }?.let { return it }
    if (outcomes.any { it is QueryOutcome.Unreachable }) {
        throw IOException("no relay answered; cannot tell a missing event from a dead relay")
    }
    return null
}

private const val TAG = "NostrService"

// Deliberately short. relay.damus.io has been serving intermittent 503s and
// nostr.wine is pay-to-write, so it silently holds none of this user's events —
// a relay that stores nothing still costs every query a connection.
private val DEFAULT_RELAYS = listOf(
    "wss://relay.towerofsong.ca",
    "wss://relay.primal.net"
)

/**
 * The NIP-51 `d` tag the desktop app publishes earmark lists under. The
 * desktop project was previously called "dirplay" and used a different tag,
 * but a one-shot migration on the desktop side republishes existing lists
 * under this name and issues a NIP-09 deletion for the legacy event, so the
 * Android app only ever needs to know about the current tag.
 */
private const val EARMARK_D_TAG = "derpy-earmarks"

/**
 * Read-only indexer relays that aggregate kind-10002/10050 relay lists. Queried
 * alongside the defaults when looking one up, because the user probably
 * published it from a different client.
 */
private val NIP65_INDEX_RELAYS = listOf(
    "wss://purplepag.es",
    "wss://relay.nostr.band"
)

class NostrService(private val httpClient: OkHttpClient) {

    /**
     * Fetches and decrypts the derpy earmark list for [privKeyHex].
     * Queries all default relays in parallel and keeps the most recent event.
     */
    suspend fun fetchEarmarks(privKeyHex: String): List<Earmark> = withContext(Dispatchers.IO) {
        val pubKeyHex = Nip44.derivePubKeyHex(privKeyHex)
        val subscriptionId = "earmarks-${System.currentTimeMillis()}"

        val filterJson = JSONObject().apply {
            put("kinds", JSONArray().put(30001))
            put("authors", JSONArray().put(pubKeyHex))
            put("#d", JSONArray().put(EARMARK_D_TAG))
            put("limit", 1)
        }
        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filterJson)
        }.toString()

        // Query all relays in parallel; absence is only believed when every
        // relay answered, so an offline launch throws (and the sync worker
        // retries) instead of reporting an empty stash.
        val outcomes = coroutineScope {
            DEFAULT_RELAYS.map { relay ->
                async { queryRelay(relay, reqMessage, subscriptionId) }
            }.awaitAll()
        }
        val bestEvent = resolveNewest(outcomes) ?: return@withContext emptyList()

        val encryptedContent = bestEvent.getString("content")
        val plaintext = Nip44.decrypt(privKeyHex, encryptedContent)
        parseEarmarkList(plaintext)
    }

    /**
     * Fetches and decrypts an addressable kind-30001 event under an arbitrary
     * `d` tag, self-encrypted to [privKeyHex]. Returns null only when the
     * relays affirmatively hold no such event; throws [IOException] when that
     * cannot be established because a relay was unreachable.
     *
     * Used for channel state (`earmark-channels`), which is stored exactly like
     * the earmark list but under a different tag.
     */
    suspend fun fetchSelfEncrypted(privKeyHex: String, dTag: String): String? =
        withContext(Dispatchers.IO) {
            val pubKeyHex = Nip44.derivePubKeyHex(privKeyHex)
            val subscriptionId = "self-$dTag-${System.currentTimeMillis()}"
            val reqMessage = JSONArray().apply {
                put("REQ")
                put(subscriptionId)
                put(JSONObject().apply {
                    put("kinds", JSONArray().put(30001))
                    put("authors", JSONArray().put(pubKeyHex))
                    put("#d", JSONArray().put(dTag))
                    put("limit", 1)
                })
            }.toString()

            val outcomes = coroutineScope {
                DEFAULT_RELAYS.map { async { queryRelay(it, reqMessage, subscriptionId) } }.awaitAll()
            }
            val best = resolveNewest(outcomes) ?: return@withContext null
            try {
                Nip44.decrypt(privKeyHex, best.getString("content"))
            } catch (e: Exception) {
                Log.w(TAG, "could not decrypt $dTag: ${e.message}")
                null
            }
        }

    /** Encrypts [plaintext] to the user's own key and publishes it under [dTag]. */
    suspend fun publishSelfEncrypted(privKeyHex: String, dTag: String, plaintext: String): Int =
        withContext(Dispatchers.IO) {
            publishEvent(
                NostrEvent.build(
                    privKeyHex = privKeyHex,
                    kind = 30001,
                    content = Nip44.encrypt(privKeyHex, plaintext),
                    tags = listOf(listOf("d", dTag))
                )
            )
        }

    /**
     * Fetches the user's NIP-17 DM relay list (kind 10050) — the relays other
     * clients were told to send them gift wraps on. Returns an empty list only
     * when the relays affirmatively hold none, in which case callers may fall
     * back to the defaults; throws [IOException] when absence cannot be
     * established, because publishing a default list over a real one that was
     * merely unreachable would redirect this user's DMs.
     */
    suspend fun fetchInboxRelays(pubKeyHex: String): List<String> = withContext(Dispatchers.IO) {
        val subscriptionId = "inbox-${System.currentTimeMillis()}"
        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(JSONObject().apply {
                put("kinds", JSONArray().put(10050))
                put("authors", JSONArray().put(pubKeyHex))
                put("limit", 1)
            })
        }.toString()

        val outcomes = coroutineScope {
            (DEFAULT_RELAYS + NIP65_INDEX_RELAYS).map {
                async { queryRelay(it, reqMessage, subscriptionId) }
            }.awaitAll()
        }
        val best = resolveNewest(outcomes) ?: return@withContext emptyList()

        val tags = best.optJSONArray("tags") ?: return@withContext emptyList()
        (0 until tags.length()).mapNotNull { i ->
            val t = tags.optJSONArray(i) ?: return@mapNotNull null
            // NIP-17 uses bare `relay` tags — no read/write markers.
            if (t.length() >= 2 && t.optString(0) == "relay") t.optString(1) else null
        }
    }

    /**
     * Publishes a NIP-17 DM relay list (kind 10050) naming [relays] as the
     * places others should send this user gift wraps.
     */
    suspend fun publishInboxRelays(privKeyHex: String, relays: List<String>): Int =
        withContext(Dispatchers.IO) {
            if (relays.isEmpty()) return@withContext 0
            publishEvent(
                NostrEvent.build(
                    privKeyHex = privKeyHex,
                    kind = 10050,
                    content = "",
                    // NIP-17 uses bare `relay` tags — no read/write markers.
                    tags = relays.map { listOf("relay", it) }
                )
            )
        }

    /**
     * Fetches every kind-1059 gift wrap addressed to [pubKeyHex] since
     * [sinceEpochSeconds], deduplicated by event id.
     *
     * Unlike the earmark list this cannot collapse to a single newest event —
     * gift wraps are not addressable, every one of them is a distinct message,
     * and each relay may hold a different subset.
     *
     * Reads from [inboxRelays] when the user has published a NIP-17 list, since
     * that is where senders were told to deliver. Falling back to the defaults
     * means messages sent to a relay we do not read are simply never seen.
     *
     * A partial answer is fine — the next sync re-reads the whole window — but
     * total silence throws [IOException]: zero wraps because nobody posted and
     * zero wraps because the network was down must not look alike, or an
     * offline sync would cache "no posts anywhere" over the real feed.
     */
    suspend fun fetchGiftWraps(
        pubKeyHex: String,
        sinceEpochSeconds: Long,
        inboxRelays: List<String> = emptyList()
    ): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val subscriptionId = "gw-${System.currentTimeMillis()}"
            val reqMessage = JSONArray().apply {
                put("REQ")
                put(subscriptionId)
                put(JSONObject().apply {
                    put("kinds", JSONArray().put(GiftWrap.GIFT_WRAP_KIND))
                    put("#p", JSONArray().put(pubKeyHex))
                    put("since", sinceEpochSeconds)
                })
            }.toString()

            val relays = (inboxRelays + DEFAULT_RELAYS).distinct()
            val perRelay = coroutineScope {
                relays.map { async { queryRelayAll(it, reqMessage, subscriptionId) } }.awaitAll()
            }
            if (perRelay.none { it.responsive }) {
                throw IOException("no relay answered the gift wrap query")
            }
            val seen = HashSet<String>()
            val out = mutableListOf<JSONObject>()
            for (batch in perRelay) {
                for (ev in batch.events) {
                    if (seen.add(ev.optString("id"))) out.add(ev)
                }
            }
            Log.d(TAG, "fetchGiftWraps: ${out.size} unique wrap(s) since $sinceEpochSeconds")
            out
        }

    /**
     * Fetches the user's NIP-02 contact list (kind 3) and returns the pubkeys
     * they follow.
     *
     * This is used only to decide how loudly a channel invite arrives — it
     * grants nothing and is never consulted for access. An empty result on
     * failure is correct: nothing is marked trusted.
     */
    suspend fun fetchFollows(pubKeyHex: String): List<String> = withContext(Dispatchers.IO) {
        val subscriptionId = "follows-${System.currentTimeMillis()}"
        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(JSONObject().apply {
                put("kinds", JSONArray().put(3))
                put("authors", JSONArray().put(pubKeyHex))
                put("limit", 1)
            })
        }.toString()

        // Lenient on purpose, unlike the other fetches: follows grant nothing,
        // so treating an outage as "follows nobody" costs one quiet invite.
        val best = coroutineScope {
            DEFAULT_RELAYS.map { async { queryRelay(it, reqMessage, subscriptionId) } }.awaitAll()
        }.filterIsInstance<QueryOutcome.Found>().map { it.event }
            .maxByOrNull { it.getLong("created_at") } ?: return@withContext emptyList()

        val tags = best.optJSONArray("tags") ?: return@withContext emptyList()
        (0 until tags.length()).mapNotNull { i ->
            val t = tags.optJSONArray(i) ?: return@mapNotNull null
            if (t.length() >= 2 && t.optString(0) == "p") t.optString(1) else null
        }
    }

    /**
     * Publishes [event] to all default relays in parallel. Returns the number
     * of relays that accepted it (received an "OK" message with success=true).
     */
    suspend fun publishEvent(event: JSONObject): Int = withContext(Dispatchers.IO) {
        val eventId = event.optString("id")
        Log.d(TAG, "publishEvent id=${eventId.take(12)}… kind=${event.optInt("kind")} to ${DEFAULT_RELAYS.size} relays")
        val results = coroutineScope {
            DEFAULT_RELAYS.map { relay ->
                async { publishToRelay(relay, event) }
            }.awaitAll()
        }
        val acks = results.count { it }
        Log.d(TAG, "publishEvent id=${eventId.take(12)}… acks=$acks/${results.size}")
        acks
    }

    /**
     * Encrypts and publishes [earmarks] as a NIP-51 kind-30001 addressable
     * event. Because the event is addressable, relays automatically replace
     * the previous version (same pubkey + kind + d tag).
     *
     * Returns the number of relays that accepted the event.
     */
    suspend fun publishEarmarks(privKeyHex: String, earmarks: List<Earmark>): Int =
        withContext(Dispatchers.IO) {
            val plaintext = earmarksToJson(earmarks)
            val ciphertext = Nip44.encrypt(privKeyHex, plaintext)
            val event = NostrEvent.build(
                privKeyHex = privKeyHex,
                kind = 30001,
                content = ciphertext,
                tags = listOf(listOf("d", EARMARK_D_TAG))
            )
            publishEvent(event)
        }

    /**
     * Opens a WebSocket to [relayUrl] and sends an EVENT message. Waits for
     * the relay's OK reply. Returns true if the relay accepted the event.
     *
     * Logs everything — open, send, OK/NOTICE, failure, timeout — under the
     * "NostrService" tag so relay-level rejections can be distinguished from
     * real connection failures in logcat.
     */
    private suspend fun publishToRelay(
        relayUrl: String,
        event: JSONObject
    ): Boolean = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { cont ->
            val msg = JSONArray().apply {
                put("EVENT")
                put(event)
            }.toString()
            val eventId = event.optString("id")
            val request = Request.Builder().url(relayUrl).build()
            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "publish→$relayUrl OPEN, sending event id=${eventId.take(12)}…")
                    webSocket.send(msg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = JSONArray(text)
                        when (arr.optString(0)) {
                            "OK" -> if (arr.optString(1) == eventId) {
                                val ok = arr.optBoolean(2, false)
                                val reason = arr.optString(3, "")
                                Log.d(TAG, "publish→$relayUrl OK accepted=$ok reason=\"$reason\"")
                                webSocket.close(1000, null)
                                if (cont.isActive) cont.resume(ok)
                            }
                            "NOTICE" -> {
                                Log.w(TAG, "publish→$relayUrl NOTICE ${arr.optString(1)}")
                            }
                            else -> {
                                Log.v(TAG, "publish→$relayUrl msg=$text")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "publish→$relayUrl parse error on \"$text\": ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "publish→$relayUrl FAILURE ${t.javaClass.simpleName}: ${t.message}")
                    if (cont.isActive) cont.resume(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "publish→$relayUrl CLOSED code=$code reason=\"$reason\"")
                    if (cont.isActive) cont.resume(false)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    } ?: run {
        Log.w(TAG, "publish→$relayUrl TIMEOUT (15s)")
        false
    }

    /**
     * Opens a WebSocket to [relayUrl], sends [reqMessage], and waits for a
     * matching EVENT or EOSE. Never returns a bare null: the caller needs to
     * know whether the relay answered "nothing here" or never answered.
     */
    private suspend fun queryRelay(
        relayUrl: String,
        reqMessage: String,
        subscriptionId: String
    ): QueryOutcome = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine<QueryOutcome> { cont ->
            val request = Request.Builder().url(relayUrl).build()
            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(reqMessage)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONArray(text)
                        val type = msg.getString(0)
                        if (type == "EVENT" && msg.getString(1) == subscriptionId) {
                            val event = msg.getJSONObject(2)
                            if (cont.isActive) {
                                webSocket.close(1000, null)
                                cont.resume(QueryOutcome.Found(event))
                            }
                        } else if (type == "EOSE") {
                            // End of stored events — the relay answered and
                            // holds nothing.
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(QueryOutcome.Empty)
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resume(QueryOutcome.Unreachable)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resume(QueryOutcome.Unreachable)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    } ?: QueryOutcome.Unreachable

    /**
     * A multi-event query's haul from one relay. [responsive] is true when the
     * relay gave any sign of life — an EOSE or at least one event. A relay that
     * was silent start to finish holds an unknown number of messages, which is
     * different from holding none.
     */
    private data class RelayBatch(val events: List<JSONObject>, val responsive: Boolean)

    /**
     * Like [queryRelay] but accumulates every matching event until EOSE.
     *
     * Returns whatever arrived if the relay goes quiet before EOSE — a partial
     * batch of channel messages is far better than none, and the missing ones
     * will turn up on the next sync or from another relay.
     */
    private suspend fun queryRelayAll(
        relayUrl: String,
        reqMessage: String,
        subscriptionId: String
    ): RelayBatch {
        val collected = mutableListOf<JSONObject>()
        var sawEose = false
        withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { cont ->
                val request = Request.Builder().url(relayUrl).build()
                val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(reqMessage)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val msg = JSONArray(text)
                            when (msg.getString(0)) {
                                "EVENT" -> if (msg.getString(1) == subscriptionId) {
                                    synchronized(collected) { collected.add(msg.getJSONObject(2)) }
                                }
                                "EOSE" -> {
                                    sawEose = true
                                    webSocket.close(1000, null)
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        val events = synchronized(collected) { collected.toList() }
        return RelayBatch(events, responsive = sawEose || events.isNotEmpty())
    }
}
