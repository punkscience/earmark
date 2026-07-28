package main

import (
	"os"

	core "github.com/punkscience/earmark/earmark-core"
)

// resolveNostrKey returns the user's Nostr private key (raw hex) following the
// resolution order: EARMARK_NOSTR_KEY env var → config file → empty string.
func resolveNostrKey() string {
	if env := os.Getenv("EARMARK_NOSTR_KEY"); env != "" {
		if hex, err := core.ResolvePrivateKey(env); err == nil {
			return hex
		}
	}
	cfg, err := LoadConfig()
	if err == nil && cfg.NostrPrivateKey != "" {
		return cfg.NostrPrivateKey
	}
	return ""
}
