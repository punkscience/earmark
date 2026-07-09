package main

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
)

var defaultNostrRelays = []string{
	"wss://relay.towerofsong.ca",
	"wss://relay.damus.io",
	"wss://relay.primal.net",
	"wss://nostr.wine",
}

// LoadNostrRelays returns the user-configured relay list, falling back to defaults.
func LoadNostrRelays() []string {
	cfg, err := LoadConfig()
	if err == nil && len(cfg.NostrRelays) > 0 {
		return cfg.NostrRelays
	}
	return defaultNostrRelays
}

func publishToRelays(ctx context.Context, relays []string, ev nostr.Event) error {
	type result struct{ err error }
	ch := make(chan result, len(relays))
	for _, u := range relays {
		u := u
		go func() {
			relay, err := nostr.RelayConnect(ctx, u)
			if err != nil {
				ch <- result{err}
				return
			}
			defer relay.Close()
			ch <- result{relay.Publish(ctx, ev)}
		}()
	}
	published := 0
	var lastErr error
	for range relays {
		r := <-ch
		if r.err != nil {
			lastErr = r.err
		} else {
			published++
		}
	}
	if published == 0 {
		if lastErr != nil {
			return fmt.Errorf("failed to publish to any relay: %w", lastErr)
		}
		return fmt.Errorf("failed to publish to any relay")
	}
	return nil
}

func queryRelays(ctx context.Context, relays []string, filter nostr.Filter) *nostr.Event {
	ch := make(chan *nostr.Event, len(relays))
	for _, u := range relays {
		u := u
		go func() {
			relay, err := nostr.RelayConnect(ctx, u)
			if err != nil {
				ch <- nil
				return
			}
			evs, err := relay.QuerySync(ctx, filter)
			relay.Close()
			if err != nil || len(evs) == 0 {
				ch <- nil
				return
			}
			ch <- evs[0]
		}()
	}
	var latest *nostr.Event
	for range relays {
		ev := <-ch
		if ev != nil && (latest == nil || ev.CreatedAt > latest.CreatedAt) {
			latest = ev
		}
	}
	return latest
}

// npubFromPrivateKey derives a bech32-encoded npub from a raw hex private key.
func npubFromPrivateKey(hexPrivKey string) (string, error) {
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

func unionStrings(a, b []string) []string {
	seen := make(map[string]struct{}, len(a)+len(b))
	out := make([]string, 0, len(a)+len(b))
	for _, r := range append(a, b...) {
		if _, ok := seen[r]; !ok {
			seen[r] = struct{}{}
			out = append(out, r)
		}
	}
	return out
}

// fetchUserWriteRelays fetches the user's NIP-65 relay list (kind 10002).
func fetchUserWriteRelays(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{10002},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := queryRelays(ctx, LoadNostrRelays(), filter)
	if ev == nil {
		return nil
	}
	var relays []string
	for _, tag := range ev.Tags {
		if len(tag) < 2 || tag[0] != "r" {
			continue
		}
		if len(tag) == 2 || tag[2] == "write" {
			relays = append(relays, tag[1])
		}
	}
	return relays
}

// PublishNote publishes a kind-1 text note to Nostr.
func PublishNote(hexPrivKey, content string) error {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return fmt.Errorf("could not derive public key: %w", err)
	}
	ev := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      nostr.KindTextNote,
		Content:   content,
		Tags:      nostr.Tags{},
	}
	if err := ev.Sign(hexPrivKey); err != nil {
		return fmt.Errorf("could not sign event: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return publishToRelays(ctx, LoadNostrRelays(), ev)
}