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
| `settings.go` | `Settings` + `Configure` — the only configuration seam. Relay list, Blossom server list, upload idle timeout, and their defaults. |
| `keys.go` | nsec/npub ↔ hex conversion, pubkey derivation |
| `relay.go` | Relay publish and query helpers, NIP-65 write-relay discovery, NIP-02 follow list, kind-1 notes |
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

The one exception is `EARMARK_UPLOAD_IDLE_TIMEOUT` (seconds), read directly from the environment because it applies identically to every host and overrides the configured value.

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
