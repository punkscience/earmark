package earmark

import (
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// TestParseInboxRelays verifies NIP-17 kind-10050 tag parsing. Unlike NIP-65
// there are no read/write markers — every relay tag is an inbox.
func TestParseInboxRelays(t *testing.T) {
	ev := &nostr.Event{
		Kind: 10050,
		Tags: nostr.Tags{
			{"relay", "wss://inbox-one.example.com"},
			{"relay", "wss://inbox-two.example.com"},
			{"r", "wss://not-a-10050-tag.example.com"}, // NIP-65 tag, ignored here
			{"relay"},                                  // malformed → ignored
			{"p", "somepubkey"},                        // ignored
		},
	}
	got := ParseInboxRelays(ev)
	want := []string{"wss://inbox-one.example.com", "wss://inbox-two.example.com"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("[%d] got %q, want %q", i, got[i], want[i])
		}
	}
}

// TestUserInboxRelaysFallsBackToConfigured verifies that a recipient with no
// published kind-10050 still gets delivery attempted somewhere, rather than
// the message being dropped.
func TestUserInboxRelaysFallsBackToConfigured(t *testing.T) {
	Configure(Settings{Relays: []string{"wss://configured.example.com"}})
	defer Configure(Settings{})

	pub := "0000000000000000000000000000000000000000000000000000000000000001"
	seedRelayListCache(inboxRelayListKind, pub, nil)

	got := UserInboxRelays(pub)
	if len(got) != 1 || got[0] != "wss://configured.example.com" {
		t.Errorf("got %v, want the configured relay as fallback", got)
	}
}

// TestUserInboxRelaysUnionsConfigured verifies a published inbox list is used
// but the configured relays stay in play, so a stale or partial list cannot cut
// delivery off entirely.
func TestUserInboxRelaysUnionsConfigured(t *testing.T) {
	Configure(Settings{Relays: []string{"wss://configured.example.com"}})
	defer Configure(Settings{})

	pub := "0000000000000000000000000000000000000000000000000000000000000002"
	seedRelayListCache(inboxRelayListKind, pub, []string{"wss://their-inbox.example.com"})

	got := UserInboxRelays(pub)
	if len(got) != 2 {
		t.Fatalf("got %v, want the inbox relay plus the configured one", got)
	}
	if got[0] != "wss://their-inbox.example.com" {
		t.Errorf("the recipient's own inbox should come first, got %v", got)
	}
}

// TestGiftWrapsGoToTheRecipientsInbox is the point of the whole change: a wrap
// must be published where its recipient reads, not where the sender happens to
// be configured. Two people on disjoint relays could not otherwise talk.
func TestGiftWrapsGoToTheRecipientsInbox(t *testing.T) {
	Configure(Settings{Relays: []string{"wss://senders-relay.example.com"}})
	defer Configure(Settings{})

	sender := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	recipientPub, _ := nostr.GetPublicKey(recipient)

	seedRelayListCache(inboxRelayListKind, recipientPub,
		[]string{"wss://recipients-inbox.example.com"})

	gw, err := wrapPayload(sender, recipientPub, []byte(`{"v":1,"chan":"a","type":"post"}`))
	if err != nil {
		t.Fatalf("wrapPayload: %v", err)
	}

	// publishGiftWraps derives the target from the wrap's own p tag.
	p := gw.Tags.GetFirst([]string{"p"})
	if p == nil || p.Value() != recipientPub {
		t.Fatalf("gift wrap p tag = %v, want the recipient", p)
	}
	targets := UserInboxRelays(p.Value())
	if !containsStr(targets, "wss://recipients-inbox.example.com") {
		t.Errorf("targets %v do not include the recipient's inbox", targets)
	}
}

// TestPublishInboxRelaysRejectsEmpty guards against wiping a user's relay list
// with an empty event, which would make them unreachable.
func TestPublishInboxRelaysRejectsEmpty(t *testing.T) {
	if err := PublishInboxRelays(nil, nostr.GeneratePrivateKey(), nil); err == nil {
		t.Error("publishing an empty inbox relay list should be refused")
	}
}

// TestRelayListCacheHonoursTTL verifies fresh entries are served and stale ones
// trigger a refetch.
func TestRelayListCacheHonoursTTL(t *testing.T) {
	Configure(Settings{})
	defer Configure(Settings{})
	pub := "0000000000000000000000000000000000000000000000000000000000000003"

	seedRelayListCache(10002, pub, []string{"wss://fresh.example.com"})
	calls := 0
	got := cachedRelayList(10002, pub, func() []string { calls++; return nil })
	if calls != 0 {
		t.Error("a fresh cache entry should not trigger a fetch")
	}
	if len(got) != 1 || got[0] != "wss://fresh.example.com" {
		t.Errorf("got %v", got)
	}

	// Age the entry past the TTL.
	relayListMem.Lock()
	relayListMem.entries[relayListKey(10002, pub)] = relayListEntry{
		Relays: []string{"wss://stale.example.com"}, FetchedAt: time.Now().Add(-2 * relayListTTL),
	}
	relayListMem.Unlock()

	got = cachedRelayList(10002, pub, func() []string { calls++; return []string{"wss://refetched.example.com"} })
	if calls != 1 {
		t.Errorf("a stale entry should trigger exactly one fetch, got %d", calls)
	}
	if len(got) != 1 || got[0] != "wss://refetched.example.com" {
		t.Errorf("got %v, want the refetched value", got)
	}
}

// TestNegativeResultsAreCached verifies a user with no relay list does not cost
// a lookup on every single call — the common case, and the one that made every
// CLI command slow.
func TestNegativeResultsAreCached(t *testing.T) {
	Configure(Settings{})
	defer Configure(Settings{})
	pub := "0000000000000000000000000000000000000000000000000000000000000004"
	invalidateRelayList(inboxRelayListKind, pub)

	calls := 0
	fetch := func() []string { calls++; return nil }
	cachedRelayList(inboxRelayListKind, pub, fetch)
	cachedRelayList(inboxRelayListKind, pub, fetch)
	cachedRelayList(inboxRelayListKind, pub, fetch)
	if calls != 1 {
		t.Errorf("empty result was not cached: %d fetches", calls)
	}
}

// seedRelayListCache primes the in-memory cache so tests never touch a relay.
func seedRelayListCache(kind int, pubHex string, relays []string) {
	relayListMem.Lock()
	relayListMem.entries[relayListKey(kind, pubHex)] = relayListEntry{
		Relays: relays, FetchedAt: time.Now(),
	}
	relayListMem.Unlock()
}

func containsStr(haystack []string, needle string) bool {
	for _, s := range haystack {
		if s == needle {
			return true
		}
	}
	return false
}
