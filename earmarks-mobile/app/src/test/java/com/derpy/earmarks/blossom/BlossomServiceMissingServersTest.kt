package com.derpy.earmarks.blossom

import com.derpy.earmarks.data.BlossomManifest
import com.derpy.earmarks.data.Chunk
import com.derpy.earmarks.data.Earmark
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An earmark published before the manifest carried a server list has an empty
 * `servers`, and that used to be read as proof the blobs were gone.
 *
 * `downloadChunk` iterated the empty list, made no request at all, and returned
 * `AllNotFound` — its "definitively gone" verdict — off zero evidence. The sync
 * worker took that as an orphan, swept the blobs off Blossom and republished
 * the earmark list without the track. Four of them went that way before anyone
 * noticed, and the audio is not recoverable.
 *
 * These tests pin the replacement contract. An unlisted chunk is looked for on
 * the client's own servers. No chunk is ever orphaned without a server having
 * actually said 404 — and that 404 has to come from a server the manifest
 * itself named, because the fallback is a search for a blob whose location was
 * never recorded, and a server that was never told to hold something cannot
 * testify that it was deleted.
 */
class BlossomServiceMissingServersTest {

    private val privKeyHex = "c".repeat(64)
    private val aesKey = ByteArray(32) { (it * 5).toByte() }
    private val plaintext = ByteArray(300) { 'q'.code.toByte() }

    private lateinit var server: MockWebServer
    private lateinit var blob: ByteArray
    private lateinit var sha: String

    /** When set, the server 404s the blob instead of serving it. */
    private var notFound = false

    /** Every path the server was asked for, to prove a request happened. */
    private val requested = mutableListOf<String>()

    /** Source URLs handed to `PUT /mirror`, in the order they were tried. */
    private val mirroredFrom = mutableListOf<String>()

    /** When set, `/mirror` rejects a source URL starting with this prefix. */
    private var mirrorRejects: String? = null

    @Before
    fun setUp() {
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, nonce)
        )
        blob = nonce + cipher.doFinal(plaintext)
        sha = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.path.orEmpty()
                if (request.path == "/mirror") {
                    val from = JSONObject(request.body.readUtf8()).getString("url")
                    mirroredFrom += from
                    val reject = mirrorRejects
                    if (reject != null && from.startsWith(reject)) {
                        return MockResponse().setResponseCode(400)
                    }
                    return MockResponse().setResponseCode(200)
                }
                if (notFound) return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(200).setBody(Buffer().write(blob))
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** An earmark whose single chunk names no servers — the pre-`servers` shape. */
    private fun earmarkWithoutServers() = earmarkNaming(emptyList())

    /** An earmark whose single chunk records [servers] as where the blob went. */
    private fun earmarkNaming(servers: List<String>) = Earmark(
        artist = "Damian's Ghost",
        album = "Universalis",
        title = "Visions",
        ts = 1_785_429_669L,
        blossom = BlossomManifest(
            key = Base64.getEncoder().encodeToString(aesKey),
            ext = ".flac",
            chunks = listOf(Chunk(index = 0, sha256 = sha, size = blob.size, servers = servers))
        )
    )

    private fun tempPair(): Pair<File, File> {
        val dir = File.createTempFile("earmark-servers", "").let { it.delete(); it.mkdirs(); it }
        return File(dir, "earmark_1.flac") to File(dir, "earmark_1.flac.part")
    }

    @Test
    fun aChunkNamingNoServersIsFetchedFromTheFallback() = runBlocking {
        val fallback = listOf(server.url("/").toString().trimEnd('/'))
        val service = BlossomService(OkHttpClient(), fallback)
        val (dest, part) = tempPair()
        try {
            val result = service.downloadAndDecrypt(earmarkWithoutServers(), dest, part, privKeyHex)

            assertEquals(BlossomService.DownloadResult.Success, result)
            assertArrayEquals(plaintext, dest.readBytes())
            assertEquals(listOf("/$sha"), requested)
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aChunkNobodyCouldBeAskedAboutIsNotOrphaned() = runBlocking {
        // No servers in the manifest and none configured: zero requests were
        // made, so there is no evidence either way. Orphaning here is what
        // deleted tracks that were merely unaddressed.
        val service = BlossomService(OkHttpClient(), emptyList())
        val (dest, part) = tempPair()
        try {
            val result = service.downloadAndDecrypt(earmarkWithoutServers(), dest, part, privKeyHex)

            assertTrue(
                "a chunk with nowhere to ask must not be orphaned, got $result",
                result is BlossomService.DownloadResult.Unavailable
            )
            assertTrue("nothing should have been requested", requested.isEmpty())
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun mirroringAChunkNamingNoSourceFallsBackRatherThanCopyingNothing() = runBlocking {
        // Adopting a channel post whose chunk lists no source used to return
        // an empty list without a request, leaving the "kept" track hosted
        // only on the poster's stash — theirs to delete.
        val url = server.url("/").toString().trimEnd('/')
        val service = BlossomService(OkHttpClient(), listOf(url))
        val chunk = Chunk(index = 0, sha256 = sha, size = blob.size, servers = emptyList())

        val hosted = service.mirrorChunk(chunk, listOf(url), privKeyHex)

        assertEquals(listOf(url), hosted)
        assertEquals(listOf("$url/$sha"), mirroredFrom)
    }

    @Test
    fun mirroringTriesTheNextCandidateSourceWhenOneIsRejected() = runBlocking {
        // The candidate list is candidates, not a single guess: a source the
        // destination cannot fetch from must not end the attempt.
        val url = server.url("/").toString().trimEnd('/')
        mirrorRejects = "https://dead.example"
        val service = BlossomService(OkHttpClient(), listOf("https://dead.example", url))
        val chunk = Chunk(index = 0, sha256 = sha, size = blob.size, servers = emptyList())

        val hosted = service.mirrorChunk(chunk, listOf(url), privKeyHex)

        assertEquals(listOf(url), hosted)
        assertEquals(listOf("https://dead.example/$sha", "$url/$sha"), mirroredFrom)
    }

    @Test
    fun mirroringWithNoSourceAnywhereRequestsNothing() = runBlocking {
        val url = server.url("/").toString().trimEnd('/')
        val service = BlossomService(OkHttpClient(), emptyList())
        val chunk = Chunk(index = 0, sha256 = sha, size = blob.size, servers = emptyList())

        val hosted = service.mirrorChunk(chunk, listOf(url), privKeyHex)

        assertTrue("nothing to mirror from, so nothing hosted", hosted.isEmpty())
        assertTrue("nothing should have been requested", requested.isEmpty())
    }

    @Test
    fun aFallbackServerSaying404DoesNotOrphan() = runBlocking {
        // The fallback is a good-faith search for a blob whose location was
        // never recorded. A 404 from a server that was never told to hold it
        // says this client does not know where the blob went, not that it was
        // deleted — and the four tracks that started all this had exactly this
        // shape. Unavailable forever is the recoverable outcome; deleted is not.
        notFound = true
        val fallback = listOf(server.url("/").toString().trimEnd('/'))
        val service = BlossomService(OkHttpClient(), fallback)
        val (dest, part) = tempPair()
        try {
            val result = service.downloadAndDecrypt(earmarkWithoutServers(), dest, part, privKeyHex)

            assertTrue(
                "a 404 from a server the manifest never named is not proof, got $result",
                result is BlossomService.DownloadResult.Unavailable
            )
            assertEquals("the fallback is still asked", listOf("/$sha"), requested)
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aRecordedServerSaying404StillOrphans() = runBlocking {
        // The other half of the contract. A manifest that names where it put
        // the blob, and that server saying 404, is the evidence orphaning is
        // for — without this the cleanup path would be dead code.
        notFound = true
        val url = server.url("/").toString().trimEnd('/')
        val service = BlossomService(OkHttpClient(), emptyList())
        val (dest, part) = tempPair()
        try {
            val result = service.downloadAndDecrypt(earmarkNaming(listOf(url)), dest, part, privKeyHex)

            assertTrue(
                "a 404 from the server that was named is still an orphan, got $result",
                result is BlossomService.DownloadResult.Orphaned
            )
            assertEquals(listOf("/$sha"), requested)
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }
}
