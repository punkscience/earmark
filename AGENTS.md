# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Earmarks** is a minimal Android app (Kotlin + Jetpack Compose) that reads a user's *derpy* earmark list from Nostr, downloads their earmarked audio files from Blossom servers, decrypts them, caches them locally, and plays them in a shuffled playlist. Audio persists in the background and the app is Android Auto compatible.

Full protocol spec: `docs/ANDROID_EARMARKS_SPEC.md`

## Build Commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # build release APK
./gradlew installDebug         # build + install on connected device
./gradlew test                 # unit tests
./gradlew lint                 # lint
```

> The `gradle-wrapper.jar` must exist at `gradle/wrapper/gradle-wrapper.jar`. Generate it by running `gradle wrapper --gradle-version 8.7` once after installing Android Studio (which bundles Gradle).

## Architecture

```
app/src/main/java/com/derpy/earmarks/
├── MainActivity.kt            — Compose entry: routes to KeyEntryScreen or PlayerScreen
├── EarmarksApplication.kt     — Application subclass (referenced in manifest)
├── data/
│   ├── Earmark.kt             — Data classes + parseEarmarkList() (org.json, no extra dep)
│   ├── KeyStore.kt            — DataStore<Preferences> for the stored nsec hex key
│   └── EarmarkCache.kt        — Local file cache: earmark_<ts><ext> in cacheDir/earmarks/
├── nostr/
│   ├── Bech32.kt              — nsec1 bech32 decoder (no external dep)
│   ├── Nip44.kt               — NIP-44 v2 self-decrypt + pubkey derivation (Bouncy Castle)
│   └── NostrService.kt        — WebSocket relay queries (OkHttp); queries 4 relays in parallel
├── blossom/
│   └── BlossomService.kt      — Parallel chunk download + SHA-256 verify + AES-256-GCM decrypt
├── player/
│   ├── EarmarksMediaService.kt — MediaLibraryService (foreground service + Android Auto)
│   └── PlayerController.kt    — MediaController wrapper; exposes PlayerState StateFlow
└── ui/
    ├── MainViewModel.kt       — AppState machine: KeyMissing → Loading → Downloading → Playing
    ├── KeyEntryScreen.kt      — nsec/hex key entry (first launch)
    └── PlayerScreen.kt        — Now-playing + prev/pause/next; settings dialog to clear key
```

## Key Design Decisions

**NIP-44 — manual implementation (not rust-nostr)**
The rust-nostr Android SDK (`org.rust-nostr:nostr-sdk`) doesn't yet expose NIP-44 decrypt in its Kotlin bindings. Instead, `Nip44.kt` implements the full NIP-44 v2 algorithm directly using Bouncy Castle's lightweight API (no JCE provider registration needed):
- secp256k1 ECDH via `ECDHBasicAgreement`
- HKDF-SHA256 via `HKDFBytesGenerator`
- ChaCha20 decrypt via `ChaCha7539Engine` (IETF variant, 96-bit nonce)
- MAC verification via `javax.crypto.Mac` HMAC-SHA256

**Self-encryption**: derpy encrypts the earmark list to the user's own public key. The shared secret is `privkey^2 * G` on secp256k1.

**Nostr relay access**: Raw OkHttp WebSocket, no Nostr library. Sends NIP-01 REQ/EVENT/EOSE JSON manually. Queries all 4 default relays in parallel, takes the event with the highest `created_at`.

**Cache expiry**: On every launch, fetches the fresh earmark list and deletes `cacheDir/earmarks/earmark_<ts>.*` for any `ts` values not present in the new list.

**Background audio + Android Auto**: `EarmarksMediaService` (a `MediaLibraryService`) runs as a foreground service. The UI connects to it via `MediaController` so the same player instance is shared with Android Auto.

## Dependencies

| Library | Purpose |
|---------|---------|
| `org.bouncycastle:bcprov-jdk18on:1.78.1` | secp256k1 ECDH, HKDF, ChaCha20, AES-GCM |
| `androidx.media3:media3-exoplayer/session/ui:1.3.1` | Audio playback + Android Auto |
| `com.squareup.okhttp3:okhttp:4.12.0` | Nostr WebSocket + Blossom HTTP downloads |
| `androidx.datastore:datastore-preferences:1.1.1` | Persisted nsec key storage |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` | Async throughout |

## Crypto Constants (from spec)

| Item | Value |
|------|-------|
| Earmark event kind | `30001`, `d` tag = `"derpy-earmarks"` |
| NIP-44 conversation key salt | `"nip44-v2"` (UTF-8) |
| NIP-44 message key info | `"encryption"` (UTF-8) |
| NIP-44 message key length | 76 bytes → [0:32] chacha key, [32:44] chacha nonce, [44:76] hmac key |
| Chunk format | `[12-byte nonce][ciphertext][16-byte GCM tag]` |
| AES key | 32 bytes, base64-decoded from `blossom.key` |
| Default relays | `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`, `wss://nostr.wine` |

## Agent skills

### Issue tracker

Issues live in GitHub Issues at github.com/punkscience/earmarks. Skills use the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
