package earmark

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// PublishToRelays publishes ev to every relay concurrently. It succeeds if at
// least one relay accepts the event.
func PublishToRelays(ctx context.Context, relays []string, ev nostr.Event) error {
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

// QueryRelays queries every relay concurrently and returns the single newest
// matching event, or nil when none matched.
func QueryRelays(ctx context.Context, relays []string, filter nostr.Filter) *nostr.Event {
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

// QueryRelaysAll queries every relay concurrently and returns the union of all
// matching events, deduplicated by event ID. Unlike QueryRelays it does not
// collapse to a single newest event, so it suits non-addressable kinds where
// every event matters — gift wraps in particular.
func QueryRelaysAll(ctx context.Context, relays []string, filter nostr.Filter) []*nostr.Event {
	ch := make(chan []*nostr.Event, len(relays))
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
			if err != nil {
				ch <- nil
				return
			}
			ch <- evs
		}()
	}
	seen := make(map[string]struct{})
	var out []*nostr.Event
	for range relays {
		for _, ev := range <-ch {
			if ev == nil {
				continue
			}
			if _, dup := seen[ev.ID]; dup {
				continue
			}
			seen[ev.ID] = struct{}{}
			out = append(out, ev)
		}
	}
	return out
}

// UnionStrings merges two lists, preserving order and dropping duplicates.
func UnionStrings(a, b []string) []string {
	return unionStrings(a, b)
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

// FetchUserWriteRelays fetches the user's NIP-65 relay list (kind 10002).
func FetchUserWriteRelays(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{10002},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := QueryRelays(ctx, Relays(), filter)
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

// FetchFollows fetches the user's NIP-02 contact list (kind 3) and returns the
// followed pubkeys. Used only to decide how loudly a channel invite arrives —
// it grants nothing.
func FetchFollows(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{3},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := QueryRelays(ctx, Relays(), filter)
	if ev == nil {
		return nil
	}
	var follows []string
	for _, tag := range ev.Tags {
		if len(tag) >= 2 && tag[0] == "p" {
			follows = append(follows, tag[1])
		}
	}
	return follows
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
	return PublishToRelays(ctx, Relays(), ev)
}
