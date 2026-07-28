# AGENTS.md

Universal context for any AI coding agent working in this repository.

## Project

**Earmark** is a private, encrypted music-stash system built on Nostr identity and Blossom storage. An *earmark* is an audio file the user flagged: encrypted client-side, chunked, uploaded to Blossom servers, and recorded in a private Nostr list (kind 30001, NIP-44 self-encrypted). Earmarks expire after 30 days.

A **channel** is a named room of Nostr identities who share earmarks with each other. Channel traffic is NIP-59 gift wrapped, so no relay can tell a channel exists. See the Channels section of `docs/PROTOCOL.md`.

This is a monorepo holding the protocol core and both clients.

| Directory | Project | Stack | Role |
|-----------|---------|-------|------|
| `earmark-core/` | earmark-core | Go | Shared protocol implementation — crypto, chunking, Blossom, the Nostr list, channels. No `main`, no UI, no config I/O. Also consumed by [derpy](https://github.com/punkscience/derpy). |
| `earmark-cli/` | earmark | Go | Desktop CLI — finds music on disk or in PLS/M3U playlists, encrypts, uploads, manages the earmark list and channels |
| `earmarks-mobile/` | earmarks-mobile | Kotlin + Jetpack Compose | Android app — reads the earmark list and channel feeds, downloads and decrypts from Blossom, plays shuffled with background audio and Android Auto |

The Go clients share `earmark-core`. Android shares nothing but the wire protocol — its Nostr, NIP-44 and (for channels) NIP-59 implementations are hand-written ports. **Any change to the protocol must be made on both sides.**

## Shared docs

- **`docs/PROTOCOL.md`** — the full earmark protocol spec: crypto constants, event shapes, chunk format, Blossom manifest layout, and the Channels section (gift wrap, rosters, retention). This is the contract between the clients. Originally written as the Android implementation spec; it is now the canonical protocol reference for all of them.
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
| Channel state event | `30001`, `d` tag = `"earmark-channels"`, self-encrypted like the earmark list |
| Channel transport | NIP-59 gift wrap — rumor kind `1737`, seal `13`, gift wrap `1059`. One wrap per member per message |
| Channel sender identity | The **seal's** pubkey. The rumor's `pubkey` field carries no authority |
| Channel key model | Per-member encryption, file key inside the rumor. No shared channel key, no backfill |
| Roster authority | Channel creator only, ordered by monotonic `seq` |
| Channel post lifetime | 30 days from `posted_at`; sender pins the chunks against their own purge |
| Gift wrap query window | `now - 32 days` — 30 days of content plus 2 days of maximum backdating |

## Build

```bash
# Shared Go core — most protocol tests live here
cd earmark-core && go build ./... && go vet ./... && go test ./...

# CLI
cd earmark-cli && go build ./... && go test ./...

# Android
cd earmarks-mobile && ./gradlew assembleDebug
```

The repo-root `go.work` ties `earmark-core` and `earmark-cli` together, so an edit to the core is picked up by the CLI with no publish step. `earmark-cli/go.mod` also carries a `replace` directive to `../earmark-core`, which keeps the CLI buildable outside the workspace (release tooling, CI checkouts).

## CI

`.github/workflows/mobile-build.yml` builds and tests the Android app on PRs that touch `earmarks-mobile/`, and publishes a sideloadable APK release on manual dispatch. Mobile release tags are prefixed `mobile-`. The Go side has no CI yet.

## Agent skills

### Issue tracker

Issues live in GitHub Issues at github.com/punkscience/earmark. Skills use the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context repo: `earmark-cli/` and `earmarks-mobile/`. Neither has a `CONTEXT.md` yet — if one is added, use the `CONTEXT-MAP.md` layout described in `docs/agents/domain.md` rather than a single root `CONTEXT.md`.

## Origin

Both projects are extractions from [derpy](https://github.com/punkscience/derpy) — the CLI from derpy's `[E]` earmark flow (minus TUI and playback), the Android app as a consumer of the list derpy produces. Some identifiers still carry the `derpy` name (the `d` tag, the Kotlin package `com.derpy.earmarks`); those are wire- and install-compatibility constraints, not aspirations.
