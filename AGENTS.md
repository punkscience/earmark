# AGENTS.md

Universal context for any AI coding agent working in this repository.

## Project

**Earmark** is a private, encrypted music-stash system built on Nostr identity and Blossom storage. An *earmark* is an audio file the user flagged: encrypted client-side, chunked, uploaded to Blossom servers, and recorded in a private Nostr list (kind 30001, NIP-44 self-encrypted). Earmarks expire after 30 days.

This is a monorepo holding both clients of that protocol.

| Directory | Project | Stack | Role |
|-----------|---------|-------|------|
| `earmark-cli/` | earmark | Go | Desktop CLI — finds music on disk or in PLS/M3U playlists, encrypts, uploads, manages the earmark list |
| `earmarks-mobile/` | earmarks-mobile | Kotlin + Jetpack Compose | Android app — reads the earmark list, downloads and decrypts from Blossom, plays shuffled with background audio and Android Auto |

The two share no build system and no code — only the wire protocol. **Any change to the protocol must be made in both.**

## Shared docs

- **`docs/PROTOCOL.md`** — the full earmark protocol spec: crypto constants, event shapes, chunk format, Blossom manifest layout. This is the contract between the two clients. Originally written as the Android implementation spec; it is now the canonical protocol reference for both.
- **`docs/agents/`** — repo-wide agent conventions (issue tracker, triage labels, domain docs).

Each subproject has its own `AGENTS.md` with build commands, architecture, and local conventions. Read the one for the directory you are working in.

## Protocol invariants

Changing any of these breaks the other client. Verify both sides before touching them.

| Item | Value |
|------|-------|
| Earmark event kind | `30001`, `d` tag = `"derpy-earmarks"` |
| List encryption | NIP-44 v2, self-encrypted to the user's own pubkey (shared secret `privkey² · G`) |
| Chunk size | 16 MiB plaintext |
| Chunk format | `[12-byte nonce][ciphertext][16-byte GCM tag]` |
| File key | AES-256-GCM, 32 bytes, base64 in `blossom.key` |
| Blossom auth | kind 24242 signed tokens (BUD-01/11), on both upload and download |
| Blossom server discovery | kind 10063, unioned with built-in defaults |
| Default relays | `wss://relay.towerofsong.ca`, `wss://relay.damus.io`, `wss://relay.primal.net`, `wss://nostr.wine` |
| Default Blossom servers | `blossom.towerofsong.ca` (primary), `blossom.band`, `cdn.satellite.earth` |
| Earmark lifetime | 30 days, auto-purged |

## Build

```bash
# CLI
cd earmark-cli && go build ./... && go test ./...

# Android
cd earmarks-mobile && ./gradlew assembleDebug
```

## CI

`.github/workflows/mobile-build.yml` builds and tests the Android app on PRs that touch `earmarks-mobile/`, and publishes a sideloadable APK release on manual dispatch. Mobile release tags are prefixed `mobile-`. The CLI has no CI yet.

## Agent skills

### Issue tracker

Issues live in GitHub Issues at github.com/punkscience/earmark. Skills use the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context repo: `earmark-cli/` and `earmarks-mobile/`. Neither has a `CONTEXT.md` yet — if one is added, use the `CONTEXT-MAP.md` layout described in `docs/agents/domain.md` rather than a single root `CONTEXT.md`.

## Origin

Both projects are extractions from [derpy](https://github.com/punkscience/derpy) — the CLI from derpy's `[E]` earmark flow (minus TUI and playback), the Android app as a consumer of the list derpy produces. Some identifiers still carry the `derpy` name (the `d` tag, the Kotlin package `com.derpy.earmarks`); those are wire- and install-compatibility constraints, not aspirations.
