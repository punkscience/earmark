package com.derpy.earmarks.blossom

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Regression test for the workerbee outage: Blossom servers with a pubkey
 * allowlist (blossom.towerofsong.ca) reject anonymous GETs with 401. The app
 * must send a signed BUD "get" authorization on chunk downloads, exactly as
 * it already does for deletes.
 */
@RunWith(AndroidJUnit4::class)
class BlossomServiceDownloadAuthTest {

    private val privKeyHex = "a".repeat(64)
    private val aesKey = ByteArray(32) { it.toByte() }
    private val plaintext = "the coast is clear".toByteArray()

    private lateinit var server: MockWebServer
    private lateinit var encryptedBlob: ByteArray
    private lateinit var blobSha: String

    @Before
    fun setUp() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, nonce)
        )
        encryptedBlob = nonce + cipher.doFinal(plaintext)
        blobSha = MessageDigest.getInstance("SHA-256")
            .digest(encryptedBlob)
            .joinToString("") { "%02x".format(it) }

        server = MockWebServer()
        server.dispatcher = authRequiringDispatcher()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Mimics a pubkey-allowlisted Blossom server: any GET without a valid
     * signed kind-24242 "get" event for the requested hash is rejected 401.
     */
    private fun authRequiringDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/$blobSha") return MockResponse().setResponseCode(404)

            val auth = request.getHeader("Authorization")
                ?: return MockResponse().setResponseCode(401)
            if (!auth.startsWith("Nostr ")) return MockResponse().setResponseCode(401)

            val event = try {
                JSONObject(String(Base64.decode(auth.removePrefix("Nostr "), Base64.DEFAULT)))
            } catch (e: Exception) {
                return MockResponse().setResponseCode(401)
            }
            val tags = event.getJSONArray("tags")
            var hasGet = false
            var hasHash = false
            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                if (tag.length() >= 2 && tag.getString(0) == "t" && tag.getString(1) == "get") hasGet = true
                if (tag.length() >= 2 && tag.getString(0) == "x" && tag.getString(1) == blobSha) hasHash = true
            }
            val valid = event.getInt("kind") == 24242 &&
                event.getString("content") == "get $blobSha" &&
                event.getString("sig").length == 128 &&
                hasGet && hasHash

            if (!valid) return MockResponse().setResponseCode(401)
            return MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(encryptedBlob))
        }
    }

    private fun earmark(): Earmark {
        val serverUrl = server.url("/").toString().trimEnd('/')
        return Earmark(
            artist = "Surround",
            album = "In Flight Safety",
            title = "The Coast is Clear",
            ts = 1_783_616_284L,
            blossom = BlossomManifest(
                key = Base64.encodeToString(aesKey, Base64.NO_WRAP),
                ext = ".mp3",
                chunks = listOf(
                    Chunk(index = 0, sha256 = blobSha, size = encryptedBlob.size, servers = listOf(serverUrl))
                )
            )
        )
    }

    @Test
    fun downloadsFromAuthRequiringServer() = runBlocking {
        val service = BlossomService(OkHttpClient())
        val dest = File.createTempFile("earmark-test", ".mp3")
        val part = File.createTempFile("earmark-test", ".mp3.part")
        try {
            val result = service.downloadAndDecrypt(earmark(), dest, part, privKeyHex)
            assertEquals(BlossomService.DownloadResult.Success, result)
            assertArrayEquals(plaintext, dest.readBytes())
        } finally {
            dest.delete()
            part.delete()
        }
    }
}
