# AGENTS.md

Universal context for any AI coding agent working in the `earmark-cli/` subproject. Read the repo-root `AGENTS.md` first — it covers the monorepo layout and the protocol invariants shared with the Android app in `earmarks-mobile/`.

## Project

**earmark** is a standalone CLI utility that finds music files (on disk, or from PLS/M3U playlists), encrypts them, and uploads them to Blossom cloud storage backed by Nostr identity. It is an extraction of the Blossom upload + Nostr earmark-list features from [derpy](https://github.com/punkscience/derpy) into a reusable general-purpose tool — derpy's `[E]` earmark flow, minus the TUI and audio playback.

## Where the protocol lives

The protocol implementation is **not in this directory**. It lives in `../earmark-core`, imported as `core "github.com/punkscience/earmark/earmark-core"` and shared with [derpy](https://github.com/punkscience/derpy). Read `earmark-core/AGENTS.md` before touching anything protocol-shaped.

| Concern | Where |
|------|------|
| AES-256-GCM encryption, 16 MiB chunking, BUD-01/11 upload/download/mirror, kind-24242 auth, kind-10063 discovery | `earmark-core/blossom.go` |
| NIP-51 kind-30001 earmark list CRUD, NIP-44 self-encryption, 30-day auto-purge | `earmark-core/list.go` |
| Relay publish/query, NIP-65 relay discovery, NIP-02 follows | `earmark-core/relay.go` |
| nsec/npub ↔ hex conversion | `earmark-core/keys.go` |
| Channels — gift wrap, rosters, channel state, pins | `earmark-core/channel*.go`, `giftwrap.go`, `roster.go` |

What stays here:

| File | Role |
|------|------|
| `cmd/earmark/main.go` | Cobra command tree; `main` lives under `cmd/` so `go install` names the binary `earmark` |
| `cmd/earmark/config.go` | Config file I/O (`~/.config/earmark/config.json`) and `configureCore()`, which pushes it into the core |
| `cmd/earmark/key.go` | Key resolution order: `EARMARK_NOSTR_KEY` → config file |
| `cmd/earmark/earmark_queue.go` | Offline queue (`~/.config/earmark/queue.json`), transactional-outbox pattern, flush-on-connectivity |
| `cmd/earmark/uploader.go` | Drives the core's prepare → upload → record pipeline, with terminal progress |
| `cmd/earmark/{scanner,playlist,selector,upload_tui}.go`, `internal/filter` | Finding files on disk and choosing between them |

**The core reads no config files.** `configureCore()` is what makes the user's relay and Blossom server lists take effect; it runs at startup in `main()` and again from `SaveConfig`. Adding a new core setting means adding it to `core.Settings` *and* to `configureCore`, or it silently does nothing.

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

Run from `earmark-cli/`. The module is `github.com/punkscience/earmark/earmark-cli`; the repo-root `go.work` joins it to `earmark-core`, and a `replace` directive in `go.mod` keeps the build working outside the workspace.

```bash
go build -o earmark ./cmd/earmark
go test ./...

# or install it
go install github.com/punkscience/earmark/earmark-cli/cmd/earmark@latest
```

> **Why `cmd/earmark/`.** Go names an installed binary after the last element of its import path. The module used to be `ca.punkscience.earmark`, so `go install` produced a binary literally called `ca.punkscience.earmark` sitting uselessly next to the real one — a trap that cost real debugging time. `main` living in `cmd/earmark/` is what makes `go install` produce `earmark`.

Most protocol tests live in `earmark-core` — run those too when you change anything shared.

## CLI design

**Cobra** for command structure. The root command is the ingest path: positional arguments are audio files, PLS/M3U playlists, or keywords searched against `--source`. Playlists are processed entry by entry.

| Command | Role |
|---|---|
| *(root)* | Find, encrypt, upload, record. `--source <dir>`, `--channel <name>` (repeatable — also post each earmark to that channel) |
| `list` | Fetch and display the private earmark list |
| `download` | Download, decrypt and reassemble earmarks to the current directory |
| `key <nsec\|hex>` | Store the Nostr private key |
| `version` | Print the build version |

### `channel` subcommands

Defined in `channel_cmd.go`. All of them go through `earmark-core`; the CLI holds no channel logic of its own.

| Command | Role |
|---|---|
| `create <name>` | Create a channel you own |
| `list` / `members <channel>` | Show your channels / one channel's roster |
| `invite <channel> <npub>` / `remove <channel> <npub>` | Creator-only membership changes |
| `leave <channel>` | Remove yourself |
| `invites` / `accept <id>` / `decline <id>` | Pending invites. Invites from pubkeys outside your kind-3 follow list are listed under a separate "Requests" heading |
| `send <channel> <search>` | Post an already-uploaded earmark. `<search>` matches artist/album/title and refuses to guess when ambiguous |
| `feed [channel]` | Posts received, newest first. Post ids come from here |
| `keep <post_id>` | Adopt a post into your own stash via Blossom mirroring |

Channels take arguments by name, full id, or unambiguous id prefix (`ChannelState.FindByName`).

Two things the UI must keep saying out loud, because both look like bugs otherwise:

- **No backfill.** A new member's feed is empty and that is correct. `accept` and `feed` both say so explicitly.
- **Removal is silent to the roster but not to the person.** The removed member receives the new roster and their client drops the channel; everyone else just sees a shorter list.

## Development directives

- Use SOLID principles
- Comment code where the "why" is non-obvious
- Always write tests (`*_test.go` files) alongside implementation
- Use `httptest` for Blossom server mocking, in-memory maps for Nostr relay mocking
- Match Go idioms from the derpy source (same error patterns, same struct conventions)
- Use Cobra for CLI commands (not `flag`)
- Config directory: `~/.config/earmark/` (not `~/.config/derpy/`)