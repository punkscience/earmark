package main

import (
	"fmt"
	"os"
	"strings"
	"unicode"

	"github.com/nbd-wtf/go-nostr/nip19"
)

// resolveNostrKey returns the user's Nostr private key (raw hex) following the
// resolution order: EARMARK_NOSTR_KEY env var → config file → empty string.
func resolveNostrKey() string {
	if env := os.Getenv("EARMARK_NOSTR_KEY"); env != "" {
		if hex, err := resolvePrivateKey(env); err == nil {
			return hex
		}
	}
	cfg, err := LoadConfig()
	if err == nil && cfg.NostrPrivateKey != "" {
		return cfg.NostrPrivateKey
	}
	return ""
}

// resolvePrivateKey accepts either a bech32 nsec1... string or a raw 64-char
// hex string and always returns the raw hex private key.
func resolvePrivateKey(key string) (string, error) {
	key = strings.TrimSpace(key)
	key = strings.Map(func(r rune) rune {
		if unicode.IsPrint(r) {
			return r
		}
		return -1
	}, key)
	if strings.HasPrefix(key, "nsec1") {
		prefix, val, err := nip19.Decode(key)
		if err != nil {
			return "", fmt.Errorf("invalid nsec key: %w", err)
		}
		if prefix != "nsec" {
			return "", fmt.Errorf("expected an nsec key, got %q prefix", prefix)
		}
		hex, ok := val.(string)
		if !ok {
			return "", fmt.Errorf("unexpected nsec decode type")
		}
		return hex, nil
	}
	return key, nil
}
