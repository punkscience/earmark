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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Downloads must survive interruption. The app is expected to fetch whole
 * albums in the background, where losing the network, being Doze-frozen, or
 * having the process reclaimed mid-track is routine rather than exceptional.
 *
 * Before this, any failure deleted the destination file outright, so a track
 * that had already pulled three of its four 16 MiB chunks threw all of it away
 * and started over on the next attempt. These tests pin the replacement
 * contract: verified chunks stay on disk, the next run picks up where the last
 * one stopped, and a partial file is never published as a playable track.
 */
class BlossomServiceResumeTest {

    private val privKeyHex = "b".repeat(64)
    private val aesKey = ByteArray(32) { (it * 3).toByte() }

    /** Three chunks of distinct, unequal plaintext — the whole assembled track. */
    private val plaintexts = listOf(
        ByteArray(400) { 'a'.code.toByte() },
        ByteArray(250) { 'b'.code.toByte() },
        ByteArray(90) { 'c'.code.toByte() }
    )
    private val wholeTrack: ByteArray get() = plaintexts.reduce { a, b -> a + b }

    private lateinit var server: MockWebServer
    private lateinit var blobs: List<ByteArray>
    private lateinit var shas: List<String>

    /** Hashes to fail with a 500 instead of serving. Mutated per test. */
    private val failWith500 = mutableSetOf<String>()

    /** Hashes to 404 from every server, which is what "orphaned" looks like. */
    private val failWith404 = mutableSetOf<String>()

    /** How many times each blob was actually served, to prove work is not redone. */
    private val served = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        blobs = plaintexts.mapIndexed { i, plain ->
            val nonce = ByteArray(12) { (i + it).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, nonce)
            )
            nonce + cipher.doFinal(plain)
        }
        shas = blobs.map { blob ->
            MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }
        }

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val sha = request.path?.removePrefix("/").orEmpty()
                val index = shas.indexOf(sha)
                if (index < 0) return MockResponse().setResponseCode(404)
                if (sha in failWith404) return MockResponse().setResponseCode(404)
                if (failWith500.remove(sha)) return MockResponse().setResponseCode(500)
                served[sha] = (served[sha] ?: 0) + 1
                return MockResponse().setResponseCode(200).setBody(Buffer().write(blobs[index]))
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /**
     * @param sizeOverrides declared `size` per chunk index, for the case where
     *   the manifest disagrees with the blob actually served.
     */
    private fun earmark(sizeOverrides: Map<Int, Int> = emptyMap()): Earmark {
        val url = server.url("/").toString().trimEnd('/')
        return Earmark(
            artist = "Lisette",
            album = "Long Player",
            title = "Interrupted",
            ts = 1_783_616_999L,
            blossom = BlossomManifest(
                key = Base64.getEncoder().encodeToString(aesKey),
                ext = ".mp3",
                chunks = blobs.mapIndexed { i, blob ->
                    Chunk(
                        index = i,
                        sha256 = shas[i],
                        size = sizeOverrides[i] ?: blob.size,
                        servers = listOf(url)
                    )
                }
            )
        )
    }

    private fun tempPair(): Pair<File, File> {
        val dir = File.createTempFile("earmark-resume", "").let { it.delete(); it.mkdirs(); it }
        return File(dir, "earmark_1.mp3") to File(dir, "earmark_1.mp3.part")
    }

    @Test
    fun resumesFromTheLastCompleteChunkAfterAMidFileFailure() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // Second chunk 500s once — a server hiccup partway through a track.
            failWith500 += shas[1]

            val first = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertTrue("expected transient failure", first is BlossomService.DownloadResult.Unavailable)

            // The verified first chunk is still on disk, and nothing was
            // published — a half a track must never look cached.
            assertTrue("partial should survive a transient failure", part.exists())
            assertEquals(plaintexts[0].size.toLong(), part.length())
            assertFalse("partial must not be published", dest.exists())

            val second = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertEquals(BlossomService.DownloadResult.Success, second)

            // Byte-identical to what a clean download would have produced.
            assertArrayEquals(wholeTrack, dest.readBytes())
            assertFalse("partial should be gone once published", part.exists())

            // The point of the exercise: chunk 0 was fetched once across both
            // runs. Without resumption it would have been fetched twice.
            assertEquals(1, served[shas[0]])
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun truncatesAHalfWrittenTailRatherThanAppendingToIt() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // Simulate a process killed mid-write: a complete first chunk plus
            // a torn fragment of the second.
            part.parentFile?.mkdirs()
            part.writeBytes(plaintexts[0] + ByteArray(17) { 'X'.code.toByte() })

            val result = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertEquals(BlossomService.DownloadResult.Success, result)

            // The torn fragment is gone rather than embedded mid-track.
            assertArrayEquals(wholeTrack, dest.readBytes())
            assertEquals(1, served[shas[1]])
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun discardsThePartialWhenTheTrackIsOrphaned() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // Every server 404s a chunk: the blobs are provably gone, so the
            // partial can never be completed and would just leak disk.
            failWith404 += shas[1]

            val result = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertTrue("expected orphan", result is BlossomService.DownloadResult.Orphaned)
            assertFalse("orphaned partial should be deleted", part.exists())
            assertFalse(dest.exists())
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aCompletePartialIsPublishedWithoutRefetchingAnything() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // The interruption landed between the last chunk write and the
            // rename. Nothing left to download, just finish the job.
            part.parentFile?.mkdirs()
            part.writeBytes(wholeTrack)

            val result = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertEquals(BlossomService.DownloadResult.Success, result)
            assertArrayEquals(wholeTrack, dest.readBytes())
            assertTrue("no chunk should have been fetched", served.isEmpty())
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun anOverLongPartialIsTruncatedBackToTheTrackRatherThanRejected() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // Trailing garbage past the final boundary. Everything below the
            // boundary was verified on the way in, so the right answer is to
            // cut the tail off, not to re-download the track.
            part.parentFile?.mkdirs()
            part.writeBytes(wholeTrack + ByteArray(64) { 'Z'.code.toByte() })

            val result = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertEquals(BlossomService.DownloadResult.Success, result)
            assertArrayEquals(wholeTrack, dest.readBytes())
            assertTrue("no chunk should have been fetched", served.isEmpty())
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aManifestWhoseSizeDisagreesWithTheBlobIsRejectedNotCached() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val (dest, part) = tempPair()
        try {
            // `sha256` pins a chunk's content but `size` is a separate field,
            // and the resume offset is computed from `size`. If the two
            // disagree, every subsequent boundary is wrong and a resumed
            // download would splice chunks at the wrong offsets. The assembled
            // length check is what catches it — without it this lands in the
            // cache as a permanently corrupt track that never re-downloads.
            val result = service.downloadAndDecrypt(
                earmark(sizeOverrides = mapOf(0 to blobs[0].size + 100)),
                dest,
                part,
                privKeyHex
            )
            assertTrue("expected rejection", result is BlossomService.DownloadResult.Unavailable)
            assertFalse("a mismatched assembly must not be published", dest.exists())
            assertFalse("a mismatched assembly must be discarded", part.exists())
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }
}
