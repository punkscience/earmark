package earmark

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
)

// testRelay is a minimal NIP-01 relay: it answers REQ with whatever events it
// was given and then EOSE, and records the events published to it.
//
// The whole point of these tests is the difference between a relay that
// answered holding nothing and one that never answered, and that difference
// does not exist without something on the other end actually saying EOSE.
type testRelay struct {
	url  string
	mu   sync.Mutex
	held []nostr.Event
	got  []nostr.Event
}

func newTestRelay(t *testing.T, held ...nostr.Event) *testRelay {
	t.Helper()
	r := &testRelay{held: held}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		c, err := websocket.Accept(w, req, nil)
		if err != nil {
			return
		}
		defer c.CloseNow()
		ctx := req.Context()
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				return
			}
			var msg []json.RawMessage
			if json.Unmarshal(data, &msg) != nil || len(msg) == 0 {
				continue
			}
			var kind string
			if json.Unmarshal(msg[0], &kind) != nil {
				continue
			}
			switch kind {
			case "REQ":
				var subID string
				json.Unmarshal(msg[1], &subID)
				r.mu.Lock()
				held := append([]nostr.Event{}, r.held...)
				r.mu.Unlock()
				for _, ev := range held {
					out, _ := json.Marshal([]any{"EVENT", subID, ev})
					if c.Write(ctx, websocket.MessageText, out) != nil {
						return
					}
				}
				out, _ := json.Marshal([]any{"EOSE", subID})
				if c.Write(ctx, websocket.MessageText, out) != nil {
					return
				}
			case "EVENT":
				var ev nostr.Event
				if json.Unmarshal(msg[1], &ev) == nil {
					r.mu.Lock()
					r.got = append(r.got, ev)
					r.mu.Unlock()
					out, _ := json.Marshal([]any{"OK", ev.ID, true, ""})
					c.Write(ctx, websocket.MessageText, out)
				}
			}
		}
	}))
	t.Cleanup(srv.Close)
	r.url = "ws" + strings.TrimPrefix(srv.URL, "http")
	return r
}

func (r *testRelay) published() []nostr.Event {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]nostr.Event{}, r.got...)
}

// deadRelayURL is a port nothing listens on, so connecting fails fast.
const deadRelayURL = "ws://127.0.0.1:1"

// useRelays points both the configured set and the user's cached NIP-65 write
// list at the given relays, so no lookup escapes to the network.
func useRelays(t *testing.T, pubHex string, relays ...string) {
	t.Helper()
	Configure(Settings{Relays: relays})
	seedRelayListCache(10002, pubHex, relays)
	t.Cleanup(func() { Configure(Settings{}) })
}

func TestQueryRelaysResolved_AffirmedAbsence(t *testing.T) {
	relay := newTestRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	ev, resolved := queryRelaysResolved(ctx, []string{relay.url}, nostr.Filter{Kinds: []int{30001}})
	if ev != nil {
		t.Fatalf("relay holds nothing, got an event: %v", ev)
	}
	if !resolved {
		t.Error("a relay that answered EOSE holding nothing is affirmed absence")
	}
}

func TestQueryRelaysResolved_UnreachableIsNotAbsence(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	ev, resolved := queryRelaysResolved(ctx, []string{deadRelayURL}, nostr.Filter{Kinds: []int{30001}})
	if ev != nil {
		t.Fatalf("nothing could answer, got an event: %v", ev)
	}
	if resolved {
		t.Error("a relay that never answered must not read as absence")
	}
}

// TestQueryRelaysResolved_OneSilentRelayPoisonsAbsence is the shape that lost
// the earmarks: one relay answers holding nothing, another is down. The list
// may well be on the one that is down.
func TestQueryRelaysResolved_OneSilentRelayPoisonsAbsence(t *testing.T) {
	relay := newTestRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, resolved := queryRelaysResolved(ctx, []string{relay.url, deadRelayURL}, nostr.Filter{Kinds: []int{30001}})
	if resolved {
		t.Error("absence needs every relay to have answered, not just one")
	}
}

func TestQueryRelaysResolved_NoRelaysIsNotAbsence(t *testing.T) {
	ev, resolved := queryRelaysResolved(context.Background(), nil, nostr.Filter{})
	if ev != nil || resolved {
		t.Error("nowhere to ask is not the same as nothing to find")
	}
}

func TestFetchEarmarks_AffirmedEmptyList(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(priv)
	relay := newTestRelay(t)
	useRelays(t, pub, relay.url)

	earmarks, err := FetchEarmarks(priv)
	if err != nil {
		t.Fatalf("a relay holding nothing is a genuinely empty list: %v", err)
	}
	if len(earmarks) != 0 {
		t.Errorf("got %d earmarks, want none", len(earmarks))
	}
}

func TestFetchEarmarks_UnreachableRelayIsAnError(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(priv)
	useRelays(t, pub, deadRelayURL)

	if _, err := FetchEarmarks(priv); err == nil {
		t.Fatal("an unreadable list must not come back as an empty one")
	}
}

// TestAddEarmarkRefusesWhenTheListCannotBeRead is the regression that matters:
// appending to a list that failed to load used to publish a list of one over
// everything the user had.
func TestAddEarmarkRefusesWhenTheListCannotBeRead(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(priv)
	// Reachable for publishing, but the read goes to a relay that is down —
	// so a publish here would be a publish over an unread list.
	live := newTestRelay(t)
	useRelays(t, pub, deadRelayURL)

	err := AddEarmark(priv, Earmark{Artist: "TNNL", Title: "The Vessel", Timestamp: 1785466477})
	if err == nil {
		t.Fatal("AddEarmark must refuse when the current list could not be read")
	}
	if len(live.published()) != 0 {
		t.Errorf("nothing may be published on a failed read, got %d events", len(live.published()))
	}
}

// TestAddEarmarkPublishesOnAnAffirmedEmptyList keeps the fix from locking out
// the first earmark on a fresh account, where the list really is empty.
func TestAddEarmarkPublishesOnAnAffirmedEmptyList(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(priv)
	relay := newTestRelay(t)
	useRelays(t, pub, relay.url)

	if err := AddEarmark(priv, Earmark{Artist: "Quadrant", Title: "Form Constant"}); err != nil {
		t.Fatalf("a first earmark on an empty list must still publish: %v", err)
	}
	published := relay.published()
	if len(published) != 1 {
		t.Fatalf("want one published list, got %d", len(published))
	}
	if published[0].Kind != earmarkKind {
		t.Errorf("published kind %d, want %d", published[0].Kind, earmarkKind)
	}
}

// TestLoadChannelStateUnreachableIsAnError guards the pin protection in
// CleanupOldEarmarks: it derives the chunks it must not delete from channel
// state, and empty-because-offline state protects nothing.
func TestLoadChannelStateUnreachableIsAnError(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(priv)
	useRelays(t, pub, deadRelayURL)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := LoadChannelState(ctx, priv); err == nil {
		t.Fatal("unreadable channel state must not come back as an empty roster")
	}
}
