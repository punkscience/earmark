// Package earmark implements the earmark protocol: AES-256-GCM encrypted,
// chunked audio stored on Blossom servers and recorded in a NIP-44
// self-encrypted Nostr list, plus channels for sharing earmarks between a
// roster of Nostr identities.
//
// It is the shared core of every Go earmark client. A change here reaches
// earmark-cli and derpy directly, and earmarks-mobile by way of the protocol
// spec in docs/PROTOCOL.md.
package earmark

import (
	"os"
	"strconv"
	"sync"
	"time"
)

// defaultRelays deliberately stays short. relay.damus.io has been serving
// intermittent 503s and nostr.wine is pay-to-write, so events published there
// by non-members are silently absent — a relay that holds none of your events
// is worse than no relay, because readers still wait on it.
var defaultRelays = []string{
	"wss://relay.towerofsong.ca",
	"wss://relay.primal.net",
}

// defaultBlossomServers is the fallback list used when the host has not
// configured any servers. blossom.towerofsong.ca is the primary (self-hosted)
// server; the others are public fallbacks that accept encrypted blob uploads
// from any authenticated pubkey. Media-only hosts (e.g. nostr.build) are
// excluded because they reject encrypted octet-stream blobs with 415.
var defaultBlossomServers = []string{
	"https://blossom.towerofsong.ca",
	"https://blossom.band",
	"https://cdn.satellite.earth",
}

// defaultUploadIdleTimeout is how long an upload may make no progress before it
// is abandoned when no override is configured.
const defaultUploadIdleTimeout = 60 * time.Second

// Settings is the host-supplied configuration the core operates under. Every
// field is optional; an empty field falls back to the built-in default.
//
// The core deliberately performs no config-file I/O of its own — each host
// keeps its own config format and location and calls Configure at startup.
type Settings struct {
	// Relays is the list of relay WebSocket URLs to publish to and fetch from.
	Relays []string
	// BlossomServers is the list of Blossom server base URLs used for chunk
	// upload, download and mirroring.
	BlossomServers []string
	// UploadIdleTimeout is how long an upload may make no progress (no bytes
	// sent) before it is abandoned. The deadline resets whenever data flows, so
	// a slow-but-steady upload runs indefinitely and only a stalled connection
	// is killed.
	UploadIdleTimeout time.Duration
	// CacheDir is a directory the core may write small caches into, such as the
	// NIP-65 relay-list lookup. Optional: with it unset the caches live only in
	// memory, which is useless to a short-lived CLI process — every invocation
	// would pay the lookup again.
	CacheDir string
}

var (
	settingsMu sync.RWMutex
	settings   Settings
)

// Configure installs the settings the core operates under. Hosts call it once
// at startup, and again whenever their config changes.
func Configure(s Settings) {
	settingsMu.Lock()
	settings = s
	settingsMu.Unlock()
}

// CacheDir returns the directory the core may write caches into, or "" when
// the host has not offered one.
func CacheDir() string {
	settingsMu.RLock()
	defer settingsMu.RUnlock()
	return settings.CacheDir
}

// Relays returns the configured relay list, or the built-in defaults.
func Relays() []string {
	settingsMu.RLock()
	defer settingsMu.RUnlock()
	if len(settings.Relays) > 0 {
		return settings.Relays
	}
	return defaultRelays
}

// BlossomServers returns the configured Blossom server list, or the built-in
// defaults.
func BlossomServers() []string {
	settingsMu.RLock()
	defer settingsMu.RUnlock()
	if len(settings.BlossomServers) > 0 {
		return settings.BlossomServers
	}
	return defaultBlossomServers
}

// uploadIdleTimeout resolves the upload idle timeout: the
// EARMARK_UPLOAD_IDLE_TIMEOUT env var (seconds) wins, then the configured
// value, then the built-in default.
func uploadIdleTimeout() time.Duration {
	if v := os.Getenv("EARMARK_UPLOAD_IDLE_TIMEOUT"); v != "" {
		if secs, err := strconv.Atoi(v); err == nil && secs > 0 {
			return time.Duration(secs) * time.Second
		}
	}
	settingsMu.RLock()
	defer settingsMu.RUnlock()
	if settings.UploadIdleTimeout > 0 {
		return settings.UploadIdleTimeout
	}
	return defaultUploadIdleTimeout
}

// DefaultRelays returns a copy of the built-in relay list. Hosts use it to
// implement "reset to defaults" in their own config commands.
func DefaultRelays() []string {
	return append([]string{}, defaultRelays...)
}

// DefaultBlossomServers returns a copy of the built-in Blossom server list.
func DefaultBlossomServers() []string {
	return append([]string{}, defaultBlossomServers...)
}
