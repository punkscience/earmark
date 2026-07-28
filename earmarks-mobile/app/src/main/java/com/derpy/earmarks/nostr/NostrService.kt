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
import kotlin.coroutines.resume

private const val TAG = "NostrService"

private val DEFAULT_RELAYS = listOf(
    "wss://relay.towerofsong.ca",
    "wss://relay.damus.io",
    "wss://relay.primal.net",
    "wss://nostr.wine"
)

/**
 * The NIP-51 `d` tag the desktop app publishes earmark lists under. The
 * desktop project was previously called "dirplay" and used a different tag,
 * but a one-shot migration on the desktop side republishes existing lists
 * under this name and issues a NIP-09 deletion for the legacy event, so the
 * Android app only ever needs to know about the current tag.
 */
private const val EARMARK_D_TAG = "derpy-earmarks"

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

        // Query all relays in parallel, collect first valid event from each
        val events = coroutineScope {
            DEFAULT_RELAYS.map { relay ->
                async { queryRelay(relay, reqMessage, subscriptionId) }
            }.awaitAll()
        }.filterNotNull()

        if (events.isEmpty()) return@withContext emptyList()

        // Use the most recent event
        val bestEvent = events.maxByOrNull { it.getLong("created_at") }
            ?: return@withContext emptyList()

        val encryptedContent = bestEvent.getString("content")
        val plaintext = Nip44.decrypt(privKeyHex, encryptedContent)
        parseEarmarkList(plaintext)
    }

    /**
     * Fetches and decrypts an addressable kind-30001 event under an arbitrary
     * `d` tag, self-encrypted to [privKeyHex]. Returns null when none exists.
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

            val events = coroutineScope {
                DEFAULT_RELAYS.map { async { queryRelay(it, reqMessage, subscriptionId) } }.awaitAll()
            }.filterNotNull()

            val best = events.maxByOrNull { it.getLong("created_at") } ?: return@withContext null
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
     * Fetches every kind-1059 gift wrap addressed to [pubKeyHex] since
     * [sinceEpochSeconds], deduplicated by event id.
     *
     * Unlike the earmark list this cannot collapse to a single newest event —
     * gift wraps are not addressable, every one of them is a distinct message,
     * and each relay may hold a different subset.
     */
    suspend fun fetchGiftWraps(pubKeyHex: String, sinceEpochSeconds: Long): List<JSONObject> =
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

            val perRelay = coroutineScope {
                DEFAULT_RELAYS.map { async { queryRelayAll(it, reqMessage, subscriptionId) } }.awaitAll()
            }
            val seen = HashSet<String>()
            val out = mutableListOf<JSONObject>()
            for (events in perRelay) {
                for (ev in events) {
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

        val events = coroutineScope {
            DEFAULT_RELAYS.map { async { queryRelay(it, reqMessage, subscriptionId) } }.awaitAll()
        }.filterNotNull()
        val best = events.maxByOrNull { it.getLong("created_at") } ?: return@withContext emptyList()

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
     * Opens a WebSocket to [relayUrl], sends [reqMessage], and waits for a matching EVENT.
     * Returns the event JSONObject or null on failure/timeout.
     */
    private suspend fun queryRelay(
        relayUrl: String,
        reqMessage: String,
        subscriptionId: String
    ): JSONObject? = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { cont ->
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
                                cont.resume(event)
                            }
                        } else if (type == "EOSE") {
                            // End of stored events — nothing found
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(null)
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resume(null)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    }

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
    ): List<JSONObject> {
        val collected = mutableListOf<JSONObject>()
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
        return synchronized(collected) { collected.toList() }
    }
}
