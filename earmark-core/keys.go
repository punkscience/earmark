package earmark

import (
	"fmt"
	"strings"
	"unicode"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
)

// ResolvePrivateKey accepts either a bech32 nsec1... string or a raw 64-char
// hex string and always returns the raw hex private key.
func ResolvePrivateKey(key string) (string, error) {
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

// ResolvePublicKey accepts either a bech32 npub1... string or a raw 64-char hex
// string and always returns the raw hex public key.
func ResolvePublicKey(key string) (string, error) {
	key = strings.TrimSpace(key)
	if strings.HasPrefix(key, "npub1") {
		prefix, val, err := nip19.Decode(key)
		if err != nil {
			return "", fmt.Errorf("invalid npub key: %w", err)
		}
		if prefix != "npub" {
			return "", fmt.Errorf("expected an npub key, got %q prefix", prefix)
		}
		hex, ok := val.(string)
		if !ok {
			return "", fmt.Errorf("unexpected npub decode type")
		}
		return hex, nil
	}
	if !nostr.IsValidPublicKey(key) {
		return "", fmt.Errorf("not a valid public key: %q", key)
	}
	return key, nil
}

// NpubFromPrivateKey derives a bech32-encoded npub from a raw hex private key.
func NpubFromPrivateKey(hexPrivKey string) (string, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return "", fmt.Errorf("could not derive public key: %w", err)
	}
	npub, err := nip19.EncodePublicKey(pubHex)
	if err != nil {
		return pubHex, nil
	}
	return npub, nil
}

// NpubFromPublicKey encodes a raw hex public key as bech32 npub, falling back
// to the hex form when encoding fails.
func NpubFromPublicKey(pubHex string) string {
	npub, err := nip19.EncodePublicKey(pubHex)
	if err != nil {
		return pubHex
	}
	return npub
}

// PublicKey derives the raw hex public key from a raw hex private key.
func PublicKey(hexPrivKey string) (string, error) {
	return nostr.GetPublicKey(hexPrivKey)
}
