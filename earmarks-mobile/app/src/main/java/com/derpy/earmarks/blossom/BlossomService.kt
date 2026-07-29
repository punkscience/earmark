package com.derpy.earmarks.blossom

import com.derpy.earmarks.data.BlossomManifest
import com.derpy.earmarks.data.Chunk
import com.derpy.earmarks.data.Earmark
import com.derpy.earmarks.nostr.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/**
 * Blossom chunk transfer: download + SHA-256 verify + AES-256-GCM decrypt,
 * plus BUD-01 delete and BUD-04 mirror.
 *
 * Deliberately free of `android.*` APIs — `java.util.Base64` rather than
 * `android.util.Base64` — so the download and resume logic runs in JVM unit
 * tests instead of needing a device, the same convention the NIP-44 and gift
 * wrap code follows. Both encode standard padded base64, which is what the Go
 * clients emit (`base64.StdEncoding` in `earmark-core/blossom.go`).
 */
class BlossomService(private val httpClient: OkHttpClient) {

    /**
     * Outcome of attempting to download + decrypt a single earmark.
     * - [Success]: file was written to disk.
     * - [Orphaned]: every server listed for at least one chunk responded 404,
     *   so the blobs are definitively gone and the earmark is unplayable. The
     *   caller should treat this as a signal to republish the earmark list
     *   without this entry.
     * - [Unavailable]: some failure that might be transient (network error,
     *   5xx, SHA mismatch, empty body). The caller should NOT republish —
     *   a future session may succeed.
     */
    sealed class DownloadResult {
        object Success : DownloadResult()
        data class Orphaned(val chunkSha: String) : DownloadResult()
        data class Unavailable(val message: String) : DownloadResult()
    }

    /**
     * Downloads, verifies, decrypts, and reassembles all chunks for [earmark].
     *
     * Chunks are appended to [partFile] and the completed file is moved to
     * [destFile] only once every chunk has landed, so [destFile] is either
     * absent or whole — never a stub that the cache would mistake for a
     * playable track.
     *
     * **[partFile] survives failure on purpose.** A run interrupted by a lost
     * network, a Doze freeze, or the process being reclaimed keeps every chunk
     * it already verified, and the next run resumes from there. Only an
     * [Orphaned] result discards it, because blobs that every server has
     * 404'd can never complete. This is what makes an interruption cost the
     * current chunk instead of the whole track.
     *
     * Never throws on network/HTTP errors — returns a [DownloadResult] so the
     * caller can decide orphan cleanup vs retry-later. (Decryption failures are
     * still wrapped as [Unavailable].)
     *
     * Streams one chunk at a time: download → decrypt → write → release. The
     * original implementation downloaded every chunk concurrently and then
     * reduce-concatenated them into a single ByteArray, which peaked at ~3×
     * the file size and OOM'd the 256MB heap for ~60MB earmarks. Sequential
     * streaming caps peak memory at roughly two chunks regardless of file
     * size; the only speed cost is losing inter-chunk download parallelism,
     * which is acceptable for background prefetch.
     */
    suspend fun downloadAndDecrypt(
        earmark: Earmark,
        destFile: File,
        partFile: File,
        privKeyHex: String
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            val manifest = earmark.blossom
                ?: return@withContext DownloadResult.Unavailable("No blossom manifest")
            val keyBytes = try {
                Base64.getDecoder().decode(manifest.key)
            } catch (e: IllegalArgumentException) {
                return@withContext DownloadResult.Unavailable("AES key is not valid base64")
            }
            if (keyBytes.size != 32) {
                return@withContext DownloadResult.Unavailable("AES key must be 32 bytes")
            }

            val orderedChunks = manifest.chunks.sortedBy { it.index }
            if (orderedChunks.isEmpty()) {
                return@withContext DownloadResult.Unavailable("Manifest has no chunks")
            }
            // The manifest is remote input and the resume offset is computed
            // from it. A chunk too small to hold its own framing would make
            // that arithmetic meaningless, so refuse rather than resume at a
            // wrong offset and assemble a corrupt file.
            if (orderedChunks.any { it.size <= CHUNK_OVERHEAD }) {
                return@withContext DownloadResult.Unavailable("Manifest has a malformed chunk size")
            }

            val result: DownloadResult = try {
                partFile.parentFile?.mkdirs()
                val resume = resumePoint(orderedChunks, partFile.length())
                // Cut back any half-written tail. Off-boundary bytes are only
                // possible when a process died mid-write; everything below the
                // boundary was SHA-256 verified before it was appended.
                if (partFile.length() != resume.truncateTo) {
                    RandomAccessFile(partFile, "rw").use { it.setLength(resume.truncateTo) }
                }

                var terminal: DownloadResult? = null
                FileOutputStream(partFile, /* append = */ true).use { out ->
                    for (chunk in orderedChunks.drop(resume.nextChunk)) {
                        when (val r = downloadChunk(chunk, privKeyHex)) {
                            is ChunkFetchResult.AllNotFound -> {
                                terminal = DownloadResult.Orphaned(chunk.sha256)
                                break
                            }
                            is ChunkFetchResult.TransientFailure -> {
                                terminal = DownloadResult.Unavailable(r.message)
                                break
                            }
                            is ChunkFetchResult.Success -> {
                                val decrypted = try {
                                    decryptChunk(r.bytes, keyBytes)
                                } catch (e: Exception) {
                                    terminal = DownloadResult.Unavailable(
                                        "Decrypt failed: ${e.message}"
                                    )
                                    break
                                }
                                out.write(decrypted)
                            }
                        }
                    }
                }
                terminal ?: DownloadResult.Success
            } catch (e: Exception) {
                DownloadResult.Unavailable(e.message ?: e.javaClass.simpleName)
            }

            when (result) {
                is DownloadResult.Success -> publish(partFile, destFile, orderedChunks)
                // Provably unfinishable — every server 404'd a chunk. Keeping
                // the partial would just leak disk until the earmark is pruned.
                is DownloadResult.Orphaned -> { partFile.delete(); result }
                // Possibly transient: keep the bytes for the next attempt.
                is DownloadResult.Unavailable -> result
            }
        }

    /**
     * Moves a fully-downloaded [partFile] into place at [destFile].
     *
     * The length check is the safety net under the derived resume offset: if
     * the assembled file ever disagrees with what the manifest says it should
     * be, the arithmetic was wrong somewhere and the bytes are not the track we
     * think they are. Throw them away and re-fetch rather than publish a file
     * that would then look permanently cached.
     */
    private fun publish(partFile: File, destFile: File, chunks: List<Chunk>): DownloadResult {
        val expected = expectedPlainLength(chunks)
        if (partFile.length() != expected) {
            partFile.delete()
            return DownloadResult.Unavailable(
                "Assembled ${partFile.length()} bytes, manifest says $expected"
            )
        }
        destFile.parentFile?.mkdirs()
        // renameTo will not replace an existing file on every filesystem, and a
        // zero-length leftover reads as "not cached" so it can legitimately be
        // sitting there.
        destFile.delete()
        if (!partFile.renameTo(destFile)) {
            return DownloadResult.Unavailable("Could not move the completed download into the cache")
        }
        return DownloadResult.Success
    }

    /**
     * Result of attempting to delete every blob in a manifest from every
     * server that holds it. [failed] is the count of (chunk × server) pairs
     * where the DELETE failed; [firstError] holds a one-line description of
     * the first failure for surfacing to the user. A 404 is treated as
     * success because the blob is already gone.
     */
    data class DeleteResult(
        val succeeded: Int,
        val failed: Int,
        val firstError: String?
    ) {
        val allSucceeded: Boolean get() = failed == 0
    }

    /**
     * Deletes every blob in [manifest] from every server that holds it. All
     * (chunk × server) pairs are attempted in parallel; nothing is aborted
     * mid-flight. The caller decides what to do with partial-failure results
     * (the recommended policy is to NOT remove the earmark from the Nostr
     * list unless [DeleteResult.allSucceeded] is true, otherwise orphaned
     * blobs become permanently unreachable).
     */
    suspend fun deleteManifest(manifest: BlossomManifest, privKeyHex: String): DeleteResult =
        withContext(Dispatchers.IO) {
            val attempts = coroutineScope {
                manifest.chunks.flatMap { chunk ->
                    chunk.servers.map { server ->
                        async(Dispatchers.IO) {
                            try {
                                if (deleteBlob(server, chunk.sha256, privKeyHex)) {
                                    null // success
                                } else {
                                    "$server: HTTP failure for ${chunk.sha256.take(8)}…"
                                }
                            } catch (e: Exception) {
                                "$server: ${e.message ?: "unknown error"} (${chunk.sha256.take(8)}…)"
                            }
                        }
                    }
                }.awaitAll()
            }
            val errors = attempts.filterNotNull()
            DeleteResult(
                succeeded = attempts.size - errors.size,
                failed = errors.size,
                firstError = errors.firstOrNull()
            )
        }

    /**
     * Sends a BUD-01 DELETE request for [sha256hex] on [serverUrl].
     * Returns true on 2xx or 404 (already gone — idempotent).
     */
    private fun deleteBlob(serverUrl: String, sha256hex: String, privKeyHex: String): Boolean {
        val token = blossomAuthToken(privKeyHex, sha256hex, "delete")
        val url = "${serverUrl.trimEnd('/')}/$sha256hex"
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Nostr $token")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            response.isSuccessful || response.code == 404
        }
    }

    /**
     * Asks each of [destinationServers] to copy the chunk onto itself from a
     * server that already holds it (BUD-04 `PUT /mirror`).
     *
     * The destination server fetches the bytes directly, so **no audio passes
     * through the phone**. That is what makes adopting a channel post viable on
     * a metered connection; a download-and-reupload would move the file twice.
     *
     * Returns the servers now hosting the chunk. A server that does not
     * implement `/mirror` is simply left out — the caller keeps the original
     * servers in the manifest, so the track stays playable either way, it just
     * is not independently hosted.
     */
    suspend fun mirrorChunk(
        chunk: Chunk,
        destinationServers: List<String>,
        privKeyHex: String
    ): List<String> = withContext(Dispatchers.IO) {
        val source = chunk.servers.firstOrNull() ?: return@withContext emptyList()
        val sourceUrl = "${source.trimEnd('/')}/${chunk.sha256}"

        coroutineScope {
            destinationServers.map { dest ->
                async {
                    try {
                        val token = blossomAuthToken(privKeyHex, chunk.sha256, "upload")
                        val body = org.json.JSONObject()
                            .put("url", sourceUrl)
                            .toString()
                            .toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("${dest.trimEnd('/')}/mirror")
                            .put(body)
                            .header("Authorization", "Nostr $token")
                            .build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) dest else null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()
    }

    /**
     * Builds a BUD-11 authorization token: a signed kind-24242 nostr event,
     * base64-encoded, used as a Bearer-style token in the Authorization header.
     */
    private fun blossomAuthToken(privKeyHex: String, sha256hex: String, action: String): String {
        val now = System.currentTimeMillis() / 1000
        val event = NostrEvent.build(
            privKeyHex = privKeyHex,
            kind = 24242,
            content = "$action $sha256hex",
            tags = listOf(
                listOf("t", action),
                listOf("x", sha256hex),
                listOf("expiration", (now + 300).toString()) // 5-minute validity
            ),
            createdAt = now
        )
        return Base64.getEncoder().encodeToString(event.toString().toByteArray(Charsets.UTF_8))
    }

    private sealed class ChunkFetchResult {
        data class Success(val bytes: ByteArray) : ChunkFetchResult()
        /** Every server returned HTTP 404. The blob is definitively gone. */
        object AllNotFound : ChunkFetchResult()
        /** At least one server produced a non-404 failure — could be transient. */
        data class TransientFailure(val message: String) : ChunkFetchResult()
    }

    private fun downloadChunk(chunk: Chunk, privKeyHex: String): ChunkFetchResult {
        // Assume everything's a 404 until we see evidence otherwise. Any non-404
        // response (timeout, 5xx, SHA mismatch, empty body, IO error) flips this
        // to false so we return TransientFailure instead of declaring orphan.
        var allNotFound = true
        var firstError: String? = null
        fun recordError(msg: String) {
            allNotFound = false
            if (firstError == null) firstError = msg
        }
        for (server in chunk.servers) {
            try {
                val url = "${server.trimEnd('/')}/${chunk.sha256}"
                // Sign a BUD "get" authorization: servers with a pubkey
                // allowlist (e.g. the self-hosted primary) reject anonymous
                // GETs with 401; public servers ignore the header.
                val token = blossomAuthToken(privKeyHex, chunk.sha256, "get")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Nostr $token")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val bytes = response.body?.bytes()
                            if (bytes == null) {
                                recordError("$server: empty body")
                                return@use
                            }
                            val digest = MessageDigest.getInstance("SHA-256")
                            val actual = digest.digest(bytes).joinToString("") { "%02x".format(it) }
                            if (actual == chunk.sha256) return ChunkFetchResult.Success(bytes)
                            recordError("$server: sha256 mismatch")
                        }
                        response.code == 404 -> {
                            // leave allNotFound alone; no error recorded
                        }
                        else -> recordError("$server: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                recordError("$server: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return if (allNotFound) ChunkFetchResult.AllNotFound
        else ChunkFetchResult.TransientFailure(firstError ?: "unknown failure")
    }

    /**
     * Decrypts one AES-256-GCM chunk.
     * Format: [12-byte nonce][ciphertext + 16-byte GCM tag]
     */
    private fun decryptChunk(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(encrypted.size > 12 + 16) { "Encrypted chunk too short" }
        val nonce = encrypted.copyOfRange(0, 12)
        val ciphertextWithTag = encrypted.copyOfRange(12, encrypted.size)

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }
}
