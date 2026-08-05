package earmark

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// nip65IndexRelays are read-only indexer relays that aggregate kind-10002
// relay lists from across the network. They are queried in addition to the
// configured relays when looking up a user's NIP-65 relay list, because the
// user may have published that list from another client to relays this app
// knows nothing about. purplepag.es is the canonical NIP-65 index.
var nip65IndexRelays = []string{
	"wss://purplepag.es",
	"wss://relay.nostr.band",
}

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

// queryStragglerGrace is how long QueryRelays keeps waiting for slower relays
// once it already has an answer.
const queryStragglerGrace = 2 * time.Second

// QueryRelays queries every relay concurrently and returns the single newest
// matching event, or nil when none matched.
//
// It does not wait for every relay. Draining all of them means one dead or
// slow relay costs the caller the entire context timeout on every single
// query — which is exactly what happens with the NIP-65 indexer relays. Once
// an answer is in hand, stragglers get queryStragglerGrace to beat it and are
// then abandoned.
//
// The trade is that a newer event sitting only on a very slow relay can be
// missed. For addressable events that resolves itself on the next query, and
// it is a far better failure mode than every command blocking for 20 seconds.
func QueryRelays(ctx context.Context, relays []string, filter nostr.Filter) *nostr.Event {
	ev, _ := queryRelaysResolved(ctx, relays, filter)
	return ev
}

// relayReply is one relay's answer. A nil event with answered set is a relay
// that holds nothing; answered unset is a relay that never spoke — it failed to
// connect, or the subscription itself errored.
type relayReply struct {
	event    *nostr.Event
	answered bool
}

// queryRelaysResolved is QueryRelays plus whether the answer can be trusted as
// the current state.
//
// resolved is false in exactly one case: no event, and at least one relay never
// answered. Nothing else distinguishes "you have no list" from "nobody could be
// asked", and a caller that reads the second as the first and then publishes
// replaces a list it never saw. That is how four earmarks were lost.
//
// A found event is always resolved. It may still be a stale copy from a fast
// relay while a slower one holds newer — see the straggler note on QueryRelays
// — but that is a different failure with a different fix.
func queryRelaysResolved(ctx context.Context, relays []string, filter nostr.Filter) (*nostr.Event, bool) {
	if len(relays) == 0 {
		// Nowhere to ask is not the same as nothing to find.
		return nil, false
	}
	ch := make(chan relayReply, len(relays))
	for _, u := range relays {
		u := u
		go func() {
			relay, err := nostr.RelayConnect(ctx, u)
			if err != nil {
				ch <- relayReply{}
				return
			}
			evs, err := relay.QuerySync(ctx, filter)
			relay.Close()
			if err != nil {
				ch <- relayReply{}
				return
			}
			if len(evs) == 0 {
				ch <- relayReply{answered: true}
				return
			}
			ch <- relayReply{event: evs[0], answered: true}
		}()
	}

	var latest *nostr.Event
	unreachable := 0
	var settle <-chan time.Time
	for replies := 0; replies < len(relays); {
		select {
		case r := <-ch:
			replies++
			if !r.answered {
				unreachable++
			}
			if r.event != nil && (latest == nil || r.event.CreatedAt > latest.CreatedAt) {
				latest = r.event
			}
			// Start the grace clock on the first usable answer.
			if latest != nil && settle == nil {
				timer := time.NewTimer(queryStragglerGrace)
				defer timer.Stop()
				settle = timer.C
			}
		// Abandoning stragglers leaves relays unheard from, so absence can
		// never be affirmed on these paths — only a found event can.
		case <-settle:
			return latest, latest != nil
		case <-ctx.Done():
			return latest, latest != nil
		}
	}
	return latest, latest != nil || unreachable == 0
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

// FetchUserWriteRelays fetches the user's NIP-65 relay list (kind 10002) and
// returns the URLs they have marked write (or read+write).
//
// The query goes to the configured relays plus the NIP-65 indexer relays, so a
// relay list published from another client is still found. Returns nil when no
// relay list exists, so callers can fall back.
func FetchUserWriteRelays(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{10002},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := QueryRelays(ctx, unionStrings(Relays(), nip65IndexRelays), filter)
	if ev == nil {
		return nil
	}
	return ParseWriteRelays(ev)
}

// ParseWriteRelays extracts the write (or read+write) relay URLs from a
// kind-10002 NIP-65 relay list event.
func ParseWriteRelays(ev *nostr.Event) []string {
	var relays []string
	for _, tag := range ev.Tags {
		// NIP-65 relay tag: ["r", "wss://...", ("read"|"write")?]
		if len(tag) < 2 || tag[0] != "r" {
			continue
		}
		if len(tag) == 2 || tag[2] == "write" {
			relays = append(relays, tag[1])
		}
	}
	return relays
}

// UserPublishRelays returns the outbox relay set for the user's own events:
// their NIP-65 write relays unioned with the configured relay list.
//
// This is the outbox model. A user's own addressable events belong wherever
// their profile is read from, not just on whichever relays this install
// happens to be configured with — otherwise another client, or the same app on
// another machine, cannot find them.
//
// The lookup is bounded and cached; see relaycache.go.
func UserPublishRelays(pubHex string) []string {
	writeRelays := cachedRelayList(10002, pubHex, func() []string {
		ctx, cancel := context.WithTimeout(context.Background(), relayListLookupTimeout)
		defer cancel()
		return FetchUserWriteRelays(ctx, pubHex)
	})
	return unionStrings(writeRelays, Relays())
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
