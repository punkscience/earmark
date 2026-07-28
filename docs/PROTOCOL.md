# Earmark Protocol Spec

Canonical reference for the earmark wire protocol — the contract between `earmark-cli/` (producer) and `earmarks-mobile/` (consumer). Both must agree on everything here; change one side and you must change the other.

Written originally as the Android implementation spec, so it is phrased from the consumer's side: everything needed to build an application that reads a user's derpy earmark list from Nostr, downloads their earmarked audio files from Blossom servers, decrypts them, and plays them as a playlist. The CLI implements the mirror image — encrypt, chunk, upload, publish.

The document is in two halves. **Steps 1–6** cover the single-user protocol — one owner, one self-encrypted list. **[Channels](#channels)** covers sharing between members of a room, which is a strict addition: a client that implements only the first half remains correct.

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
| Gift wrap fails to decrypt | Not an error — someone else's mail or spam. Drop silently |
| Gift wrap or seal signature invalid | Discard the message; do not surface |
| `post` from a non-roster sender | Drop silently (spam defence) |
| `roster` not signed by the creator | Drop silently |
| `roster` with a stale `seq` | Ignore; a newer roster already applied |
| Unknown envelope `v` or `type` | Ignore the message — forward compatibility, not a failure |
| Channel has no posts | Show "Tracks posted from now on will appear here" — there is no backfill by design |
| Blossom server lacks `PUT /mirror` | Fall back to download-verify-upload for Keep |
| Recipient has no kind-10050 list | Fall back to the configured relays, and tell the user delivery is a guess |
| A relay in the query set is unresponsive | Do not block on it — return once an answer is in hand |

---

# Channels

Everything above describes the single-user protocol: one owner, one self-encrypted list, a file key that never leaves it.

A **channel** is a named room with N members. Any member can post an earmark into it; every member can play what was posted. The target case is two to five friends trading music.

## Design properties

These are load-bearing. Breaking one is a protocol change, not an implementation detail.

| Property | Consequence |
|---|---|
| **A channel is purely client-side.** No relay, and no observer of any relay, can tell that a channel exists, what it is called, who is in it, or that two pubkeys are in one together. | All channel traffic is NIP-59 gift wrapped. No channel identifier ever appears in an event tag. |
| **Per-member encryption. There is no shared channel key.** Each post is encrypted separately to each member, and the file key rides inside that per-recipient payload. | Removing a member is a no-op — you stop encrypting to them. No rekey ceremony, no single secret whose leak exposes the channel's history. Cost is one event per member per post. |
| **No backfill.** A member sees only what was posted after they joined. | A direct consequence of per-member encryption: there is no historical key to hand over. Clients **must** say so in the UI; an empty channel is correct behaviour, not a fault. |
| **Creator-only rosters.** Only the channel creator can add or remove members. | No member can silently widen the audience for music another member posts next. If a creator goes dark the roster freezes; posting still works, and you fork a new channel to change membership. |
| **Retention is the sender's obligation, for 30 days.** | A post is live for 30 days from `posted_at`, matching earmark lifetime. The sender pins the underlying chunks so their own purge cannot break a recipient. To hold something longer, the recipient adopts it (see *Keep*). |

## Channel identity and state

A channel is defined by a **descriptor**:

```json
{
  "id":         "<64 hex chars — 32 random bytes>",
  "name":       "Family Jams",
  "creator":    "<creator pubkey, 64 hex>",
  "created_at": 1712345678
}
```

The `id` is generated from a CSPRNG at creation and is **never derived from the name**. It appears only inside encrypted payloads.

Each client keeps its channel state in a **second self-encrypted addressable event**, identical in construction to the earmark list but with a different `d` tag:

```json
{
  "kinds":   [30001],
  "authors": ["<user-pubkey-hex>"],
  "#d":      ["earmark-channels"],
  "limit":   1
}
```

Content is NIP-44 self-encrypted exactly as the earmark list is (§ *Decrypt the content — NIP-44*). Plaintext:

```json
{
  "v": 1,
  "channels": [
    {
      "descriptor": { "id": "...", "name": "Family Jams", "creator": "...", "created_at": 1712345678 },
      "members":    ["<pubkey>", "<pubkey>"],
      "seq":        3,
      "joined_at":  1712345690
    }
  ],
  "invites": [
    { "descriptor": {...}, "members": [...], "from": "<pubkey>", "received_at": 1712340000, "trusted": true }
  ],
  "pins": [
    { "chan": "<channel id>", "chunks": ["<sha256>", "..."], "posted_at": 1712345678 }
  ]
}
```

Reusing the list machinery gives cross-device channel sync for free — a new install with the same nsec recovers its channels along with its earmarks.

## Message envelope

Every channel message is a JSON object carried as the content of a NIP-59 rumor:

```json
{ "v": 1, "chan": "<channel id>", "type": "invite|roster|post|leave", ... }
```

`v` is the envelope version. Receivers **must** ignore envelopes with an unrecognised `v` or `type` rather than erroring — that is the forward-compatibility hinge.

### `invite`

```json
{
  "v": 1, "chan": "<id>", "type": "invite",
  "name": "Family Jams",
  "creator": "<pubkey>",
  "members": ["<pubkey>", "..."],
  "created_at": 1712345678
}
```

Sent by the creator when adding a member. The recipient surfaces it for accept/decline; accepting writes the channel into their state event. Declining records the id so the invite is not re-surfaced.

### `roster`

```json
{
  "v": 1, "chan": "<id>", "type": "roster",
  "name": "Family Jams",
  "members": ["<pubkey>", "..."],
  "seq": 4,
  "updated_at": 1712349999
}
```

The full membership list, not a delta. Applied **only** when both hold:

1. the **seal author** is the channel's `creator`, and
2. `seq` is strictly greater than the highest `seq` already applied for that channel.

Roster messages from anyone else are dropped silently. `seq` starts at 1 and increments on every membership change.

**A roster goes to everyone the change touches, including anyone removed by it.** There is no separate "you have been removed" message: a member who finds themselves absent from a roster their creator signed drops the channel and its pins locally. Omitting the removed member would leave a dead channel on their screen forever — receiving nothing, explaining nothing.

### `post`

```json
{
  "v": 1, "chan": "<id>", "type": "post",
  "posted_at": 1712350000,
  "earmark": {
    "artist": "John Coltrane",
    "album":  "A Love Supreme",
    "title":  "Resolution",
    "ts":     1712345678,
    "blossom": { "key": "...", "ext": ".flac", "chunks": [ ... ] }
  }
}
```

The `earmark` object is exactly the shape defined in § *Parse the earmark list*, **including `blossom.key`** — the AES-256 file key. `path` is omitted; it is meaningless off the originating machine.

Because the whole envelope is encrypted to exactly one recipient, **per-member key wrapping is implicit**. There is no separate key-wrapping construct.

A post is accepted only if the **seal author** is in the receiver's current roster for `chan`. Posts from non-members are dropped silently — this is the primary spam defence.

Post identity is `(seal author, chan, posted_at)`. Clients dedupe on it, since the same post arrives once per relay.

### `leave`

```json
{ "v": 1, "chan": "<id>", "type": "leave", "left_at": 1712351111 }
```

Self-removal only. The seal author removes themselves; receivers drop them from their local roster and stop encrypting to them. A `leave` naming anyone other than its own seal author is invalid.

## Transport — NIP-59 gift wrap

Every message above is delivered as one gift wrap **per recipient**. Three layers:

**1. Rumor** — an *unsigned* event. Kind `1737` (an application-internal discriminator; a rumor is never published, so this value is only ever seen after decryption). `content` is the envelope JSON. `sig` **must** be empty.

**2. Seal** — kind `13`. `content` is `NIP-44(rumor JSON, conversationKey(recipient_pub, sender_priv))`. Signed by the sender's **real** key. No tags. `created_at` randomly backdated.

**3. Gift wrap** — kind `1059`. `content` is `NIP-44(seal JSON, conversationKey(recipient_pub, ephemeral_priv))` where `ephemeral_priv` is a **freshly generated key, used once and discarded**. Signed by that ephemeral key. Tags: `[["p", "<recipient pubkey>"]]`. `created_at` randomly backdated.

This is standard NIP-59. Go implementations should use `github.com/nbd-wtf/go-nostr/nip59` (`GiftWrap` / `GiftUnwrap`) rather than hand-rolling.

### Backdating

Both the seal and the gift wrap carry a randomised `created_at` in the past, so relay timestamps cannot be correlated into a conversation. `go-nostr` backdates by up to 6 hours; the NIP allows up to 2 days.

**Receivers must therefore never order or expire channel content by `created_at`.** Use `posted_at` from inside the rumor. Query windows must allow for the maximum backdating: to see 30 days of posts, query `since: now - 32 days`.

### Relay selection

Gift wraps are the one place where "publish to the configured relays" is wrong.

A wrap is addressed to its **recipient**, so it belongs on relays that recipient reads — their **NIP-17 kind-10050 DM relay list** — not on the sender's outbox and not on whatever the sending install happens to be configured with. Publishing to the sender's relays works only when both parties happen to share one, and fails **silently** otherwise: the publish succeeds, and the message is never seen.

| Operation | Relays |
|---|---|
| Publish a gift wrap | the **recipient's** kind-10050 list, unioned with the configured set |
| Read gift wraps addressed to you | **your own** kind-10050 list, unioned with the configured set |
| Publish the earmark list or channel state | the **sender's** NIP-65 kind-10002 write relays ∪ configured (outbox model) |
| Look up a kind-10002 or kind-10050 | configured ∪ indexer relays (`purplepag.es`, `relay.nostr.band`) |

A recipient with no kind-10050 falls back to the configured set. That is a guess, and clients **should** say so — otherwise the first symptom is a friend who never receives anything and no error anywhere.

Relay-list lookups are cached with a 15-minute TTL, **including empty results**. Without caching the negative, the common case — a user who has published no list — costs a full lookup timeout on every single operation.

Clients must not publish a kind-10050 automatically. The list may have been curated in the user's primary Nostr client, and a music tool has no business overwriting it. Offer it as an explicit action.

### Receiving

```json
{ "kinds": [1059], "#p": ["<my pubkey>"], "since": <now - 32 days> }
```

sent to your own inbox relays (above).

For each gift wrap:

1. Verify the gift wrap's own signature. Discard if invalid.
2. NIP-44 decrypt `content` with `conversationKey(gw.pubkey, my_priv)` → seal JSON.
3. Verify the **seal's** signature. Discard if invalid.
4. NIP-44 decrypt the seal's `content` with `conversationKey(seal.pubkey, my_priv)` → rumor JSON.
5. **The sender is `seal.pubkey`.** The rumor's own `pubkey` field is attacker-controlled and carries no authority — overwrite it with `seal.pubkey` (this is what `nip59.GiftUnwrap` does) or reject the message if the two differ. Never trust it as-is.
6. Parse the envelope and apply the `type` rules above.

Gift wraps that fail to decrypt are not errors — they are other people's mail, or spam. Drop them without surfacing anything.

## Sending

To post an earmark to a channel:

1. Load the current roster from channel state.
2. Build the `post` envelope once.
3. For each member **other than yourself**, generate a fresh ephemeral key and emit one gift wrap.
4. Publish all wraps to the configured relays.
5. Record a pin (below).

Fan-out is `members - 1` events per post. At the design scale (≤ 10 members) this is trivial; implementations should still stagger publishes to avoid tripping relay rate limits.

## Retention

### Pinning

A channel post hands out chunk hashes the sender is hosting. If the sender later purges that earmark from their personal list, the recipients' copies break.

So: on posting, the sender appends `{chan, chunks: [sha256...], posted_at}` to `pins` in the channel state event. Blossom chunk deletion **must** skip any hash referenced by a pin whose `posted_at` is within the last 30 days. Expired pins are dropped during the same purge pass.

### Keep

A channel post is only guaranteed for 30 days. A recipient who wants to hold on to a track **adopts** it: the chunks are copied to the recipient's own Blossom servers and the earmark is written into their personal list with a fresh `ts`, starting a new 30-day clock under their own ownership.

The copy uses **Blossom BUD-04 mirroring**, not a re-upload:

```
PUT https://<my-blossom-server>/mirror
Authorization: Nostr <base64 kind-24242 event>
Content-Type: application/json

{ "url": "https://<source-server>/<sha256>" }
```

The kind-24242 auth event is built exactly as for upload (§ *Blossom auth*), with the `x` tag set to the chunk hash. The server fetches the bytes itself — **no bytes move through the client**, which is what makes Keep viable on a phone.

Where a server does not implement `/mirror`, fall back to download-verify-upload.

Adoption reuses the **same AES key and the same chunk hashes**. It is a change of hosting, not of encryption; nothing is re-encrypted. The original sender can still decrypt the adopted copy, which is inherent to having been given the file.

## Anti-spam and web of trust

Two filters, both client-side:

- **Posts** from a seal author not in the receiver's current roster for that channel: dropped silently.
- **Invites** from a pubkey absent from the receiver's NIP-02 follow list (kind `3`): accepted into the state event's `invites` array with `"trusted": false`, but not surfaced as a notification. Clients show these in a separate requests bucket.

Web of trust does **not** grant access and is not a discovery mechanism. Membership is decided by the creator and enforced by who the senders encrypt to. Following someone only affects how loudly their invite arrives.

---

## Key constants

| Constant | Value |
|----------|-------|
| Earmark list event kind | `30001` |
| Earmark list `d` tag | `"derpy-earmarks"` |
| Channel state `d` tag | `"earmark-channels"` (kind `30001`, self-encrypted) |
| Channel rumor kind | `1737` (internal; never published unencrypted) |
| Seal kind | `13` (NIP-59) |
| Gift wrap kind | `1059` (NIP-59) |
| Channel id | 32 random bytes, hex |
| Channel envelope version | `1` |
| Gift wrap query window | `now - 32 days` (30-day content + 2-day max backdating) |
| Channel post lifetime | 30 days from `posted_at` |
| Blossom mirror endpoint | `PUT /mirror` (BUD-04), kind-24242 auth |
| Gift wrap delivery relays | recipient's NIP-17 kind-10050 list ∪ configured |
| Outbox relays (own events) | NIP-65 kind-10002 write relays ∪ configured |
| Relay-list indexers | `wss://purplepag.es`, `wss://relay.nostr.band` |
| Relay-list cache TTL | 15 minutes, empty results included |
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
