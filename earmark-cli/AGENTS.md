# AGENTS.md

Universal context for any AI coding agent working in the `earmark-cli/` subproject. Read the repo-root `AGENTS.md` first — it covers the monorepo layout and the protocol invariants shared with the Android app in `earmarks-mobile/`.

## Project

**earmark** is a standalone CLI utility that finds music files (on disk, or from PLS/M3U playlists), encrypts them, and uploads them to Blossom cloud storage backed by Nostr identity. It is an extraction of the Blossom upload + Nostr earmark-list features from [derpy](https://github.com/punkscience/derpy) into a reusable general-purpose tool — derpy's `[E]` earmark flow, minus the TUI and audio playback.

## Source of truth

The original implementation being extracted lives in `../derpy`. Key source files:

| File | Role |
|------|------|
| `blossom.go` | AES-256-GCM encryption, 16 MiB chunking, BUD-01/11 upload/download, kind-24242 auth tokens, kind-10063 server discovery |
| `blossom_test.go` | Encryption round-trips, httptest server integration, partial-upload failures |
| `nostr_list.go` | NIP-51 kind-30001 earmark list CRUD, NIP-44 self-encryption, 30-day auto-purge |
| `nostr_list_test.go` | Earmark add/update/cleanup lifecycle tests |
| `earmark_queue.go` | Offline queue (`~/.config/earmark/queue.json`), transactional-outbox pattern, flush-on-connectivity |
| `earmark_queue_test.go` | Queue persistence and flush tests |
| `nostr.go` | Relay publishing, NIP-65 relay discovery, key resolution |
| `config.go` | Config file I/O, Nostr relay and Blossom server lists |

## What earmark does NOT include

- No TUI (Bubble Tea lives in derpy only)
- No audio playback, speaker, or MPRIS2
- No ListenBrainz or Bluesky integration
- No local tag system (tag, sumcache, indexer, search modules stay in derpy)

## Domain glossary

From derpy's `CONTEXT.md`:

| Term | Definition |
|------|-----------|
| **Track** | A single audio file (mp3, flac, wav, ogg, m4a, aac) on disk |
| **Earmark** | A user-flagged Track — encrypted, uploaded to Blossom, recorded in a private Nostr list (kind 30001). Expires after 30 days. |
| **Blossom Manifest** | Per-file metadata: AES-256-GCM key, original extension, list of encrypted chunks (each with SHA-256, size, and server URLs) |

## Architecture

### Encryption & upload pipeline

```
Input (file or playlist entry)
  → PrepareUpload: AES-256-GCM key, split into 16 MiB chunks, encrypt each independently
  → UploadPrepared: concurrent upload to ≥2 Blossom servers per chunk (BUD-11 auth)
  → Manifest stored in Nostr kind-30001 (NIP-51, NIP-44 self-encrypted)
```

### Nostr identity

- User's nsec1/hex private key persisted in `~/.config/earmark/config.json` (0600)
- NIP-44 self-encryption: earmark list is ciphertext on relays; only the key holder can read it
- NIP-65 relay discovery for relay selection
- kind-10063 for Blossom server discovery (unioned with built-in defaults)
- Built-in relay defaults: `relay.towerofsong.ca`, `relay.damus.io`, `relay.primal.net`, `nostr.wine`
- Built-in Blossom server defaults: `blossom.towerofsong.ca` (primary), `blossom.band`, `cdn.satellite.earth`

### Offline queue

- Earmarks written to `~/.config/earmark/queue.json` immediately (transactional outbox)
- Flushed to Nostr when connectivity returns
- Survives restarts; no data loss on [E] press even when offline

## Build & run

Run from `earmark-cli/` — the Go module is rooted here, not at the repo root.

```bash
go mod tidy
go build -o earmark

# Usage (planned — design the CLI with Cobra)
earmark upload <file-or-playlist>
earmark list
earmark download <earmark-id>
earmark key <nsec1-or-hex>
```

## CLI design

Use **Cobra** for command structure. Subcommands (to be implemented):

- `upload` — encrypt and upload one or more audio files or playlist entries to Blossom
- `list` — fetch and display the user's private earmark list from Nostr
- `download` — download and reassemble an earmark from Blossom chunks
- `key` — store/manage Nostr private key
- `blossom list|add|remove|reset` — Blossom server management

Accept both individual audio files and PLS/M3U playlist files as input. When given a playlist, process each entry independently.

## Development directives

- Use SOLID principles
- Comment code where the "why" is non-obvious
- Always write tests (`*_test.go` files) alongside implementation
- Use `httptest` for Blossom server mocking, in-memory maps for Nostr relay mocking
- Match Go idioms from the derpy source (same error patterns, same struct conventions)
- Use Cobra for CLI commands (not `flag`)
- Config directory: `~/.config/earmark/` (not `~/.config/derpy/`)