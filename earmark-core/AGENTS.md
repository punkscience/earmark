# AGENTS.md

Universal context for any AI coding agent working in the `earmark-core/` subproject. Read the repo-root `AGENTS.md` first — it covers the monorepo layout and the protocol invariants.

## Project

**earmark-core** is the shared Go implementation of the earmark protocol. It has no `main`, no UI, and no config-file I/O. It exists because two Go clients need identical protocol behaviour:

| Consumer | Repo | Uses it for |
|---|---|---|
| `earmark-cli` | this monorepo | the whole protocol surface |
| `derpy` | [punkscience/derpy](https://github.com/punkscience/derpy) | the `[E]` earmark flow and channel send/browse |

Module path is `github.com/punkscience/earmark/earmark-core`. `earmark-cli` resolves it through the repo-root `go.work` and a `replace` directive; derpy consumes tagged `earmark-core/vX.Y.Z` releases.

> **A change here reaches three clients.** Two directly, and `earmarks-mobile` indirectly — the Kotlin implementation is a hand-written port of the same wire format. Anything that alters bytes on the wire must be reflected in `docs/PROTOCOL.md` and mirrored in Kotlin, or the clients silently stop understanding each other.

## Package layout

| File | Role |
|------|------|
| `settings.go` | `Settings` + `Configure` — the only configuration seam. Relay list, Blossom server list, upload idle timeout, cache directory, and their defaults. |
| `keys.go` | nsec/npub ↔ hex conversion, pubkey derivation |
| `relay.go` | Relay publish/query helpers, the NIP-65 outbox relay set, NIP-02 follow list, kind-1 notes |
| `inbox.go` | NIP-17 kind-10050 inbox relays — where gift wraps are delivered and read |
| `relaycache.go` | Shared TTL cache for kind-10002/10050 lookups, memory + disk |
| `blossom.go` | AES-256-GCM chunk encryption, 16 MiB chunking, BUD-01/11 upload/download/delete, kind-24242 auth tokens, kind-10063 server discovery, reassembly |
| `list.go` | The NIP-51 kind-30001 earmark list: NIP-44 self-encryption, CRUD, legacy-tag migration, 30-day purge |

## The configuration seam

The core reads **no config files**. Hosts keep their own config format and location (`~/.config/earmark/`, `~/.config/derpy/`) and push values in at startup:

```go
core.Configure(core.Settings{
    Relays:            cfg.NostrRelays,
    BlossomServers:    cfg.BlossomServers,
    UploadIdleTimeout: time.Duration(cfg.UploadIdleTimeoutSeconds) * time.Second,
})
```

Empty fields fall back to built-in defaults. Call it again after any config change — `earmark-cli` does this from `SaveConfig`.

`Settings.CacheDir` is where the core may write small caches (currently the NIP-65 lookup). It is optional, but a host that omits it gets an in-memory cache only — worthless to a short-lived CLI, which exits before it can ever be read back. That mistake made every `earmark channel` invocation repay the relay-list lookup.

The one exception is `EARMARK_UPLOAD_IDLE_TIMEOUT` (seconds), read directly from the environment because it applies identically to every host and overrides the configured value.

## The outbox model

A user's own addressable events — the earmark list and channel state — are
published to, and read from, `UserPublishRelays(pubHex)`: their NIP-65 write
relays unioned with the configured list. Publishing only to the configured
relays means another install, or any client reading their relay list, cannot
find them.

kind-10002 lookups also query indexer relays (purplepag.es, relay.nostr.band),
because the relay list was probably published from a different client. Lookups
are TTL-cached for 15 minutes — in memory, and on disk when `Settings.CacheDir`
is set — with empty results cached too, so an offline session does not pay the
timeout on every publish.

**`QueryRelays` does not wait for every relay.** Draining all of them means one
dead or slow relay costs the caller the whole context timeout on every query;
adding the indexer relays made that near-certain, and it turned a channel
command into 20 seconds of silence. Once an answer is in hand, stragglers get a
2-second grace period and are then abandoned. The trade is that a newer event
sitting only on a very slow relay can be missed, which for addressable events
resolves itself on the next query.

**Gift wraps use the opposite rule.** A channel message is for its *recipient*,
so it goes to their NIP-17 kind-10050 inbox relays (`UserInboxRelays`), read off
the wrap's own `p` tag — never the sender's outbox. Reading gift wraps uses your
*own* kind-10050 list. See `inbox.go`.

Both lookups share the cache in `relaycache.go`: 15-minute TTL, in memory and on
disk, **including empty results**. A user with no published list is the common
case, and not caching that negative made every command pay a lookup timeout.

A recipient with no kind-10050 falls back to the configured set. That is a
guess, and the CLI says so — the failure is otherwise completely silent on both
ends. Clients never publish a kind-10050 automatically; it may have been curated
in the user's primary client.

## Conventions

- Every exported function that talks to the network takes a `context.Context` as its first argument. Callers own the deadline.
- Private keys are passed as raw 64-char hex strings, never as `nsec`. Convert at the edge with `ResolvePrivateKey`.
- Errors wrap with `%w` and read as a sentence: `"could not derive public key: ..."`.
- No `log` output. The core returns errors; hosts decide how to surface them.

## Build

```bash
cd earmark-core && go build ./... && go vet ./... && go test ./...
```

Tests use `httptest` servers for the Blossom paths — nothing in the suite touches a real relay or a real Blossom server, and it must stay that way so the suite runs offline.
