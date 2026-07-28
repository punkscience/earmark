package earmark

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// inboxRelayListKind is NIP-17's DM relay list: the relays a user wants to
// receive gift-wrapped messages on.
const inboxRelayListKind = 10050

// FetchInboxRelays fetches a user's NIP-17 DM relay list (kind 10050) and
// returns the relays they read gift wraps from. Returns nil when they have
// published none.
//
// The query goes to the configured relays plus the NIP-65 indexer relays, for
// the same reason the kind-10002 lookup does: the list was probably published
// from a different client.
func FetchInboxRelays(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{inboxRelayListKind},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := QueryRelays(ctx, unionStrings(Relays(), nip65IndexRelays), filter)
	if ev == nil {
		return nil
	}
	return ParseInboxRelays(ev)
}

// ParseInboxRelays extracts relay URLs from a kind-10050 event. NIP-17 uses
// bare `relay` tags — there are no read/write markers, every entry is an inbox.
func ParseInboxRelays(ev *nostr.Event) []string {
	var relays []string
	for _, tag := range ev.Tags {
		if len(tag) >= 2 && tag[0] == "relay" {
			relays = append(relays, tag[1])
		}
	}
	return relays
}

// UserInboxRelays returns where to deliver gift wraps addressed to pubHex.
//
// A gift wrap is for its recipient, so it belongs on relays that recipient
// actually reads — not on the sender's outbox and not on whatever this install
// happens to be configured with. Publishing to the sender's relays works only
// when both parties happen to share one, and fails silently otherwise: the
// send succeeds, and the message is never seen.
//
// Falls back to the configured relays when the recipient has published no
// kind-10050, which is the best guess available.
func UserInboxRelays(pubHex string) []string {
	relays := cachedRelayList(inboxRelayListKind, pubHex, func() []string {
		ctx, cancel := context.WithTimeout(context.Background(), relayListLookupTimeout)
		defer cancel()
		return FetchInboxRelays(ctx, pubHex)
	})
	if len(relays) == 0 {
		return Relays()
	}
	// Union with the configured set so a stale or partial list cannot cut
	// delivery off entirely.
	return unionStrings(relays, Relays())
}

// HasInboxRelays reports whether a user has published a NIP-17 DM relay list.
//
// Worth surfacing: without one, everyone sending to this user is guessing, so
// channel messages may never arrive. Callers use it to warn.
func HasInboxRelays(pubHex string) bool {
	return len(cachedRelayList(inboxRelayListKind, pubHex, func() []string {
		ctx, cancel := context.WithTimeout(context.Background(), relayListLookupTimeout)
		defer cancel()
		return FetchInboxRelays(ctx, pubHex)
	})) > 0
}

// PublishInboxRelays publishes the user's NIP-17 DM relay list, telling other
// clients where to send them gift wraps.
//
// This is never done automatically. A kind-10050 may have been curated in the
// user's primary Nostr client, and a music player has no business overwriting
// it — the same reasoning that kept `relay add` from touching kind-10002.
func PublishInboxRelays(ctx context.Context, hexPrivKey string, relays []string) error {
	if len(relays) == 0 {
		return fmt.Errorf("refusing to publish an empty inbox relay list")
	}
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return fmt.Errorf("could not derive public key: %w", err)
	}
	tags := make(nostr.Tags, 0, len(relays))
	for _, r := range relays {
		tags = append(tags, nostr.Tag{"relay", r})
	}
	ev := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      inboxRelayListKind,
		Content:   "",
		Tags:      tags,
	}
	if err := ev.Sign(hexPrivKey); err != nil {
		return fmt.Errorf("could not sign inbox relay list: %w", err)
	}
	// The list itself is a public addressable event about this user, so it
	// belongs on their outbox alongside their other profile metadata.
	if err := PublishToRelays(ctx, UserPublishRelays(pubHex), ev); err != nil {
		return err
	}
	// Our own cache is now wrong.
	invalidateRelayList(inboxRelayListKind, pubHex)
	return nil
}
