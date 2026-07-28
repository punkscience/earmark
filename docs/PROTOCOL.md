# Earmark Protocol Spec

Canonical reference for the earmark wire protocol — the contract between `earmark-cli/` (producer) and `earmarks-mobile/` (consumer). Both must agree on everything here; change one side and you must change the other.

Written originally as the Android implementation spec, so it is phrased from the consumer's side: everything needed to build an application that reads a user's derpy earmark list from Nostr, downloads their earmarked audio files from Blossom servers, decrypts them, and plays them as a playlist. The CLI implements the mirror image — encrypt, chunk, upload, publish.

---

## What the app needs to do

1. Accept a Nostr private key (nsec1... bech32 or raw hex)
2. Derive the user's public key from it
3. Fetch and decrypt the private earmark list from Nostr relays
4. For each earmark, download its encrypted audio chunks from Blossom servers
5. Decrypt and reassemble the chunks into a playable audio file
6. Play the resulting files as a sequential playlist

---

## Recommended Tech Stack

- **Language:** Kotlin
- **Nostr:** [rust-nostr/nostr-sdk-android](https://github.com/rust-nostr/nostr-sdk) or the pure-Kotlin [nostr-kt](https://github.com/dluvian/nostr-kt) — you need NIP-44 decryption and basic relay querying
- **HTTP:** OkHttp or Ktor client
- **Crypto:** Android's built-in `javax.crypto` (AES/GCM/NoPadding) — no external library needed
- **Audio playback:** `ExoPlayer` (Media3) — handles mp3, flac, ogg, m4a, wav from local files
- **Async:** Kotlin coroutines + `Dispatchers.IO`
- **UI:** Jetpack Compose

---

## Step 1 — Nostr key handling

The user enters either:
- An `nsec1...` bech32-encoded private key, or
- A raw 64-character hex private key

**Decode nsec to hex:**

nsec1 keys are bech32-encoded. Strip the `nsec1` prefix and decode the bech32 payload to get the raw 32-byte private key. Represent it as a 64-char lowercase hex string internally.

Many Nostr Android libraries expose `Keys.fromNsec(nsec)` or similar — use whatever your chosen library provides.

**Derive public key:**

The public key is the secp256k1 compressed x-coordinate of `privKey * G`, serialized as a 64-char hex string (the x coordinate only — not the full compressed 33-byte form). This is standard Schnorr/BIP-340 convention used throughout Nostr.

---

## Step 2 — Fetch the earmark list from Nostr

The earmark list is a **NIP-51 kind-30001 addressable event** with a `d` tag of `"derpy-earmarks"`.

### Query filter

Connect to one or more Nostr relays via WebSocket and send:

```json
{
  "kinds": [30001],
  "authors": ["<user-pubkey-hex>"],
  "#d": ["derpy-earmarks"],
  "limit": 1
}
```

**Default relays** (use any, or all in parallel):
```
wss://relay.towerofsong.ca
wss://relay.damus.io
wss://relay.primal.net
wss://nostr.wine
```

When querying multiple relays in parallel, keep the event with the highest `created_at` timestamp.

### Decrypt the content — NIP-44

The event `content` is NIP-44 encrypted. The encryption scheme is **ChaCha20-Poly1305** with a key derived from a secp256k1 ECDH shared secret.

The twist: derpy uses **self-encryption** — the user encrypts to themselves. The "recipient" public key passed to the ECDH is the user's own public key.

**NIP-44 conversation key derivation:**

```
shared_point = secp256k1_ecdh(recipientPubKey, senderPrivKey)
                              // only the x-coordinate (32 bytes)
conversationKey = HKDF-SHA256(
    input_key_material = shared_point_x_bytes,
    salt               = "nip44-v2",
    info               = ""
) [32 bytes]
```

Because sender == recipient (self-encryption), both sides use the same private key and the same public key, so the conversation key is always the same value.

**NIP-44 v2 decrypt:**

The ciphertext format is a base64-encoded blob. After decoding:

```
[version_byte (0x02)] [nonce (32 bytes)] [ciphertext] [MAC (32 bytes)]
```

Decryption:
```
message_key, chacha_nonce = HKDF-SHA256(
    input_key_material = conversationKey,
    salt               = nonce,          // the 32-byte nonce from the payload
    info               = "encryption"    // or "message-keys" — check NIP-44 v2 spec
) [split into 32-byte key + 12-byte nonce]

plaintext = ChaCha20-Poly1305-Decrypt(
    key    = message_key,
    nonce  = chacha_nonce,
    input  = ciphertext,
    aad    = ""
)
```

The plaintext is padded — NIP-44 v2 uses a specific padding scheme. Strip the padding per spec. After unpadding you have a JSON string.

> **Implementation note:** Rather than implementing NIP-44 yourself, strongly prefer using a library that already implements it. The Rust-based `nostr-sdk` has Android bindings and a correct NIP-44 implementation. If using a pure-Kotlin library, verify it implements NIP-44 v2 specifically (not v1).

---

## Step 3 — Parse the earmark list

After decryption, `JSON.parse` the plaintext. It is an array of earmark objects:

```json
[
  {
    "artist": "John Coltrane",
    "album": "A Love Supreme",
    "title": "Resolution",
    "path": "/home/darryl/Music/coltrane/resolution.flac",
    "ts": 1712345678,
    "blossom": {
      "key": "base64-encoded-32-byte-AES256-key",
      "ext": ".flac",
      "chunks": [
        {
          "index": 0,
          "sha256": "abcdef1234567890...",
          "size": 16777244,
          "servers": [
            "https://blossom.band",
            "https://cdn.satellite.earth"
          ]
        },
        {
          "index": 1,
          "sha256": "fedcba9876543210...",
          "size": 8388636,
          "servers": [
            "https://blossom.band"
          ]
        }
      ]
    }
  }
]
```

### Field reference

| Field | Type | Notes |
|-------|------|-------|
| `artist` | string | May be empty |
| `album` | string | May be empty |
| `title` | string | May be empty |
| `path` | string | Absolute path on the originating machine — not useful on Android |
| `ts` | int64 | Unix seconds — the earmark timestamp, also serves as its unique ID |
| `blossom` | object or null | Null means the file was never uploaded; skip these entries |
| `blossom.key` | string | Base64 standard encoding of the 32-byte AES-256-GCM key |
| `blossom.ext` | string | Original file extension: `.mp3`, `.flac`, `.ogg`, `.m4a`, `.wav` |
| `blossom.chunks` | array | Ordered list of encrypted chunks, must be reassembled in index order |
| `blossom.chunks[].index` | int | 0-based position in the reassembled file |
| `blossom.chunks[].sha256` | string | Lowercase hex SHA-256 of the **encrypted** chunk bytes |
| `blossom.chunks[].size` | int | Byte length of the **encrypted** chunk |
| `blossom.chunks[].servers` | []string | Servers known to hold this chunk |

Filter out any earmarks where `blossom` is null — they cannot be played on Android.

---

## Step 4 — Download chunks from Blossom

Blossom is a simple HTTP blob store. Each chunk is fetched by its SHA-256 hash.

### Download a chunk

```
GET https://<server>/<sha256hex>
```

No authentication required for downloads on public servers. Response body is the raw encrypted chunk bytes.

**Always verify the SHA-256 after download:**
```
if SHA256(responseBytes) != chunk.sha256 { discard and retry another server }
```

### Fallback strategy

Each chunk has a `servers` list. Try them in order. If one fails (network error, non-200, SHA-256 mismatch), try the next server in the list.

### Parallel downloads

Download all chunks for a single earmark concurrently (one goroutine / coroutine per chunk). Collect results into a `List<ByteArray?>` indexed by `chunk.index`. Wait for all to complete before decrypting.

---

## Step 5 — Decrypt and reassemble

### Encrypted chunk format

Each chunk is structured as:
```
[12-byte random nonce][ciphertext][16-byte AES-GCM authentication tag]
```

This is standard AES-256-GCM output from `javax.crypto.Cipher` in `AES/GCM/NoPadding` mode.

### Decrypt one chunk

```kotlin
fun decryptChunk(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val nonce = encryptedBytes.copyOfRange(0, 12)
    val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)
    val spec = GCMParameterSpec(128, nonce)   // 128-bit (16-byte) auth tag
    val secretKey = SecretKeySpec(keyBytes, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
    return cipher.doFinal(ciphertext)
}
```

The AES key is decoded from `blossom.key` (base64 standard):
```kotlin
val keyBytes = Base64.decode(manifest.key, Base64.DEFAULT)
// keyBytes.size == 32
```

### Reassemble

Sort decrypted chunks by `index` (they arrive in arbitrary order due to parallel download), then concatenate:

```kotlin
val plaintext: ByteArray = (0 until chunks.size)
    .map { i -> decryptedChunks[i]!! }
    .reduce { acc, chunk -> acc + chunk }
```

Write `plaintext` to a temp file with the original extension from `blossom.ext`:
```kotlin
val tempFile = File.createTempFile("earmark_$timestamp", manifest.ext, cacheDir)
tempFile.writeBytes(plaintext)
```

---

## Step 6 — Play the playlist with ExoPlayer

Add Media3 ExoPlayer to your `build.gradle`:
```gradle
implementation "androidx.media3:media3-exoplayer:1.3.1"
implementation "androidx.media3:media3-ui:1.3.1"
```

Build a playlist from the downloaded temp files:

```kotlin
val player = ExoPlayer.Builder(context).build()

val mediaItems = earmarks.mapNotNull { earmark ->
    val file = downloadedFiles[earmark.ts] ?: return@mapNotNull null
    MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(earmark.title)
                .setArtist(earmark.artist)
                .setAlbumTitle(earmark.album)
                .build()
        )
        .build()
}

player.setMediaItems(mediaItems)
player.prepare()
player.play()
```

ExoPlayer handles sequential playback automatically. When the current item ends, it advances to the next.

Supported formats (ExoPlayer + Android built-in decoders):
- `.mp3` — universally supported
- `.flac` — supported on API 21+
- `.ogg` (Vorbis/Opus) — supported
- `.m4a` (AAC) — supported
- `.wav` — supported

---

## Putting it all together — suggested flow

```
User enters nsec/hex key
        ↓
Decode to hex privKey
        ↓
Derive pubKey (secp256k1)
        ↓
Query Nostr relays for kind-30001, d="derpy-earmarks", author=pubKey
        ↓
Decrypt content with NIP-44 (self-encryption: recipient = own pubKey)
        ↓
Parse JSON → []Earmark
Filter out earmarks where blossom == null
        ↓
For each earmark (in parallel across earmarks):
    For each chunk (in parallel within earmark):
        GET https://<server>/<sha256>
        Verify SHA-256
        Retry next server on failure
    Sort chunks by index
    Decrypt each with AES-256-GCM
    Concatenate → tempFile.<ext>
        ↓
Build ExoPlayer playlist from temp files
Play
```

---

## Error handling guidance

| Situation | Recommended behaviour |
|-----------|----------------------|
| No kind-30001 event found | Show "No earmarks yet" |
| Earmark has `blossom: null` | Skip silently (file was never uploaded) |
| All servers fail for a chunk | Skip that earmark, show error in UI |
| AES-GCM auth tag failure | Corrupt or tampered chunk — skip earmark |
| NIP-44 decrypt fails | Wrong key or corrupt event — show error |
| Relay connection timeout | Use 15s timeout; try all relays in parallel |

---

## Key constants

| Constant | Value |
|----------|-------|
| Earmark list event kind | `30001` |
| Earmark list `d` tag | `"derpy-earmarks"` |
| Chunk size (plaintext) | `16 * 1024 * 1024` bytes (16 MiB) |
| Encrypted chunk overhead | 12 bytes (nonce) + 16 bytes (GCM tag) = 28 bytes |
| AES key size | 32 bytes (AES-256) |
| AES-GCM nonce size | 12 bytes |
| AES-GCM tag size | 16 bytes |
| Default relays | relay.towerofsong.ca, damus.io, relay.primal.net, nostr.wine |
| Default Blossom servers | blossom.band, cdn.satellite.earth, nostr.build |

---

## NIP-44 reference implementations

If you need to implement NIP-44 without a library:

- Full spec: https://github.com/nostr-protocol/nips/blob/master/44.md
- Test vectors (use these to validate your implementation): included in the NIP-44 repo under `tests/`
- The `nostr-sdk` Kotlin/JVM bindings are the safest path — the spec has subtleties around padding and HKDF info strings that are easy to get wrong from scratch.
