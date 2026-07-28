package earmark

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// --- Gift wrap ------------------------------------------------------------

// TestGiftWrapRoundTrip verifies that a wrapped payload comes back byte-identical
// to the recipient, and that the sender it reports is the real signer.
func TestGiftWrapRoundTrip(t *testing.T) {
	sender := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	senderPub, _ := nostr.GetPublicKey(sender)
	recipientPub, _ := nostr.GetPublicKey(recipient)

	payload := []byte(`{"v":1,"chan":"abc","type":"post"}`)
	gw, err := wrapPayload(sender, recipientPub, payload)
	if err != nil {
		t.Fatalf("wrapPayload: %v", err)
	}

	if gw.Kind != nostr.KindGiftWrap {
		t.Errorf("gift wrap kind = %d, want %d", gw.Kind, nostr.KindGiftWrap)
	}
	if gw.PubKey == senderPub {
		t.Error("gift wrap is signed by the sender's real key — it must use a throwaway")
	}
	if p := gw.Tags.GetFirst([]string{"p"}); p == nil || p.Value() != recipientPub {
		t.Errorf("gift wrap p tag = %v, want %s", p, recipientPub)
	}

	got, gotPayload, err := unwrapPayload(recipient, &gw)
	if err != nil {
		t.Fatalf("unwrapPayload: %v", err)
	}
	if got != senderPub {
		t.Errorf("sender = %s, want %s", got, senderPub)
	}
	if string(gotPayload) != string(payload) {
		t.Errorf("payload = %s, want %s", gotPayload, payload)
	}
}

// TestGiftWrapLeaksNothing verifies that neither the real sender nor the
// channel appears anywhere in the event a relay actually stores. This is the
// whole reason channels use gift wrap, so it is asserted rather than assumed.
func TestGiftWrapLeaksNothing(t *testing.T) {
	sender := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	senderPub, _ := nostr.GetPublicKey(sender)
	recipientPub, _ := nostr.GetPublicKey(recipient)

	chanID := "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
	env := Envelope{V: 1, Chan: chanID, Type: EnvelopePost}
	payload, _ := env.encode()

	gw, err := wrapPayload(sender, recipientPub, payload)
	if err != nil {
		t.Fatalf("wrapPayload: %v", err)
	}
	wire, err := json.Marshal(gw)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	for _, secret := range []struct{ name, value string }{
		{"sender pubkey", senderPub},
		{"channel id", chanID},
	} {
		if containsSub(string(wire), secret.value) {
			t.Errorf("%s appears in the published event", secret.name)
		}
	}
}

// TestUnwrapRejectsForgedRumorAuthor verifies the NIP-59 impersonation guard:
// a rumor claiming an author other than the seal's signer is rejected outright.
func TestUnwrapRejectsForgedRumorAuthor(t *testing.T) {
	attacker := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	recipientPub, _ := nostr.GetPublicKey(recipient)
	victimPub, _ := nostr.GetPublicKey(nostr.GeneratePrivateKey())

	// A rumor that claims to be from the victim, sealed by the attacker.
	rumor := nostr.Event{
		PubKey:    victimPub,
		CreatedAt: nostr.Now(),
		Kind:      ChannelRumorKind,
		Content:   `{"v":1,"chan":"abc","type":"post"}`,
		Tags:      nostr.Tags{},
	}
	rumor.ID = rumor.GetID()

	gw := sealAndWrap(t, attacker, recipientPub, rumor)

	if _, _, err := unwrapPayload(recipient, &gw); err == nil {
		t.Fatal("expected forged rumor author to be rejected, got no error")
	}
}

// TestUnwrapAcceptsEmptyRumorAuthor verifies that a rumor with no pubkey at all
// is still accepted — absence is not a forgery, and the seal supplies identity.
func TestUnwrapAcceptsEmptyRumorAuthor(t *testing.T) {
	sender := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	senderPub, _ := nostr.GetPublicKey(sender)
	recipientPub, _ := nostr.GetPublicKey(recipient)

	rumor := nostr.Event{
		CreatedAt: nostr.Now(),
		Kind:      ChannelRumorKind,
		Content:   `{"v":1,"chan":"abc","type":"post"}`,
		Tags:      nostr.Tags{},
	}
	gw := sealAndWrap(t, sender, recipientPub, rumor)

	got, _, err := unwrapPayload(recipient, &gw)
	if err != nil {
		t.Fatalf("unwrapPayload: %v", err)
	}
	if got != senderPub {
		t.Errorf("sender = %s, want %s", got, senderPub)
	}
}

// TestUnwrapWrongRecipient verifies that a wrap addressed elsewhere simply
// fails to decrypt rather than leaking anything.
func TestUnwrapWrongRecipient(t *testing.T) {
	sender := nostr.GeneratePrivateKey()
	recipient := nostr.GeneratePrivateKey()
	stranger := nostr.GeneratePrivateKey()
	recipientPub, _ := nostr.GetPublicKey(recipient)

	gw, err := wrapPayload(sender, recipientPub, []byte(`{"v":1,"chan":"a","type":"post"}`))
	if err != nil {
		t.Fatalf("wrapPayload: %v", err)
	}
	if _, _, err := unwrapPayload(stranger, &gw); err == nil {
		t.Fatal("a stranger decrypted a gift wrap addressed to someone else")
	}
}

// sealAndWrap builds a NIP-59 seal and gift wrap around an arbitrary rumor,
// bypassing wrapPayload so tests can construct malformed inputs.
func sealAndWrap(t *testing.T, senderPriv, recipientPub string, rumor nostr.Event) nostr.Event {
	t.Helper()
	convKey, err := nip44.GenerateConversationKey(recipientPub, senderPriv)
	if err != nil {
		t.Fatalf("conversation key: %v", err)
	}
	rumorJSON, _ := json.Marshal(rumor)
	sealed, err := nip44.Encrypt(string(rumorJSON), convKey)
	if err != nil {
		t.Fatalf("encrypt rumor: %v", err)
	}
	seal := nostr.Event{
		CreatedAt: nostr.Now(),
		Kind:      nostr.KindSeal,
		Content:   sealed,
		Tags:      nostr.Tags{},
	}
	if err := seal.Sign(senderPriv); err != nil {
		t.Fatalf("sign seal: %v", err)
	}
	ephemeral := nostr.GeneratePrivateKey()
	ephKey, err := nip44.GenerateConversationKey(recipientPub, ephemeral)
	if err != nil {
		t.Fatalf("ephemeral conversation key: %v", err)
	}
	sealJSON, _ := json.Marshal(seal)
	wrapped, err := nip44.Encrypt(string(sealJSON), ephKey)
	if err != nil {
		t.Fatalf("encrypt seal: %v", err)
	}
	gw := nostr.Event{
		CreatedAt: nostr.Now(),
		Kind:      nostr.KindGiftWrap,
		Content:   wrapped,
		Tags:      nostr.Tags{{"p", recipientPub}},
	}
	if err := gw.Sign(ephemeral); err != nil {
		t.Fatalf("sign gift wrap: %v", err)
	}
	return gw
}

func containsSub(haystack, needle string) bool {
	if needle == "" {
		return false
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}

// --- Roster ---------------------------------------------------------------

func testChannel(creator string, members ...string) *ChannelState {
	return &ChannelState{
		V: 1,
		Channels: []Channel{{
			Descriptor: ChannelDescriptor{ID: "chan1", Name: "Family Jams", Creator: creator},
			Members:    members,
			Seq:        1,
		}},
	}
}

// TestRosterRequiresCreator verifies that only the creator can change a roster.
// Without this, any member could widen the audience for music someone else
// posts next.
func TestRosterRequiresCreator(t *testing.T) {
	st := testChannel("creator", "creator", "self")
	env := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeRoster, Seq: 2,
		Members: []string{"creator", "self", "intruder"}}

	if applyRoster(st, "self", "member", env) {
		t.Error("a non-creator roster update was applied")
	}
	if len(st.Channels[0].Members) != 2 {
		t.Errorf("members changed to %v", st.Channels[0].Members)
	}

	if !applyRoster(st, "self", "creator", env) {
		t.Fatal("the creator's roster update was rejected")
	}
	if len(st.Channels[0].Members) != 3 {
		t.Errorf("members = %v, want 3 entries", st.Channels[0].Members)
	}
}

// TestRosterSeqMustAdvance verifies that a replayed or stale roster is ignored,
// so a removed member cannot be resurrected by rebroadcasting an old update.
func TestRosterSeqMustAdvance(t *testing.T) {
	st := testChannel("creator", "creator", "self", "bob")
	st.Channels[0].Seq = 5

	stale := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeRoster, Seq: 4,
		Members: []string{"creator", "self", "bob", "mallory"}}
	if applyRoster(st, "self", "creator", stale) {
		t.Error("a stale roster (seq 4 < 5) was applied")
	}
	same := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeRoster, Seq: 5,
		Members: []string{"creator", "self", "bob", "mallory"}}
	if applyRoster(st, "self", "creator", same) {
		t.Error("a replayed roster (seq 5 == 5) was applied")
	}
	if len(st.Channels[0].Members) != 3 {
		t.Errorf("members = %v, want 3 entries", st.Channels[0].Members)
	}
}

// TestRosterDroppingSelfRemovesChannel verifies that being absent from a roster
// the creator signed removes the channel locally — that is how removal arrives,
// there is no separate message.
func TestRosterDroppingSelfRemovesChannel(t *testing.T) {
	st := testChannel("creator", "creator", "self")
	st.Pins = []ChannelPin{{Chan: "chan1", Chunks: []string{"aa"}, PostedAt: time.Now().Unix()}}

	env := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeRoster, Seq: 2,
		Members: []string{"creator"}}
	if !applyRoster(st, "self", "creator", env) {
		t.Fatal("removal roster was not applied")
	}
	if st.Find("chan1") != nil {
		t.Error("channel survived our removal from it")
	}
	if len(st.Pins) != 0 {
		t.Errorf("pins = %v, want none after leaving the channel", st.Pins)
	}
}

// TestLeaveRemovesOnlySender verifies self-removal works and that a leave
// cannot evict anyone else.
func TestLeaveRemovesOnlySender(t *testing.T) {
	st := testChannel("creator", "creator", "bob", "carol")
	env := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeLeave}

	if !applyLeave(st, "bob", env) {
		t.Fatal("bob's leave was not applied")
	}
	if st.Channels[0].HasMember("bob") {
		t.Error("bob is still a member after leaving")
	}
	if !st.Channels[0].HasMember("carol") {
		t.Error("carol was removed by bob's leave")
	}
	if applyLeave(st, "stranger", env) {
		t.Error("a non-member's leave was applied")
	}
}

// TestInviteMustComeFromItsCreator verifies nobody can invite on someone else's
// behalf, and that declined channels stay declined.
func TestInviteMustComeFromItsCreator(t *testing.T) {
	st := &ChannelState{V: 1}
	env := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeInvite,
		Name: "Family Jams", Creator: "creator", Members: []string{"creator", "self"}}

	if applyInvite(st, "someone-else", env, true) {
		t.Error("an invite forged on the creator's behalf was accepted")
	}
	if !applyInvite(st, "creator", env, true) {
		t.Fatal("a genuine invite was rejected")
	}
	if len(st.Invites) != 1 || !st.Invites[0].Trusted {
		t.Errorf("invites = %+v", st.Invites)
	}

	st.Invites = nil
	st.Declined = []string{"chan1"}
	if applyInvite(st, "creator", env, true) {
		t.Error("an invite to a previously declined channel was re-surfaced")
	}
}

// TestInviteUntrustedIsStillRecorded verifies that an invite from someone
// outside the follow list is kept but flagged, rather than dropped. Web of
// trust filters how loudly it arrives, nothing more.
func TestInviteUntrustedIsStillRecorded(t *testing.T) {
	st := &ChannelState{V: 1}
	env := &Envelope{V: 1, Chan: "chan1", Type: EnvelopeInvite,
		Name: "Strangers", Creator: "creator", Members: []string{"creator", "self"}}

	if !applyInvite(st, "creator", env, false) {
		t.Fatal("an untrusted invite was dropped entirely")
	}
	if st.Invites[0].Trusted {
		t.Error("invite from a non-followed pubkey is marked trusted")
	}
}

// --- Pins -----------------------------------------------------------------

// TestPinnedChunksWindow verifies that pins protect chunks for exactly the post
// lifetime and no longer.
func TestPinnedChunksWindow(t *testing.T) {
	now := time.Now()
	st := &ChannelState{
		V: 1,
		Pins: []ChannelPin{
			{Chan: "c", Chunks: []string{"fresh"}, PostedAt: now.Add(-24 * time.Hour).Unix()},
			{Chan: "c", Chunks: []string{"stale"}, PostedAt: now.Add(-40 * 24 * time.Hour).Unix()},
		},
	}
	pinned := st.PinnedChunks(now)
	if _, ok := pinned["fresh"]; !ok {
		t.Error("a chunk posted yesterday is not pinned")
	}
	if _, ok := pinned["stale"]; ok {
		t.Error("a chunk posted 40 days ago is still pinned")
	}
}

// TestDropExpiredPins verifies expired pins are cleaned out of state.
func TestDropExpiredPins(t *testing.T) {
	now := time.Now()
	st := &ChannelState{
		V: 1,
		Pins: []ChannelPin{
			{Chan: "c", Chunks: []string{"fresh"}, PostedAt: now.Unix()},
			{Chan: "c", Chunks: []string{"stale"}, PostedAt: now.Add(-40 * 24 * time.Hour).Unix()},
		},
	}
	if !st.dropExpiredPins(now) {
		t.Error("dropExpiredPins reported no change but one pin was expired")
	}
	if len(st.Pins) != 1 || st.Pins[0].Chunks[0] != "fresh" {
		t.Errorf("pins = %+v, want only the fresh one", st.Pins)
	}
	if st.dropExpiredPins(now) {
		t.Error("dropExpiredPins reported a change on a second pass")
	}
}

// --- Channel lookup -------------------------------------------------------

// TestFindByName covers exact name, exact id, id prefix, unknown and ambiguous.
func TestFindByName(t *testing.T) {
	st := &ChannelState{V: 1, Channels: []Channel{
		{Descriptor: ChannelDescriptor{ID: "aaaaaaaa1111", Name: "Family Jams"}},
		{Descriptor: ChannelDescriptor{ID: "aaaaaaaa2222", Name: "Work"}},
	}}

	if c, err := st.FindByName("Family Jams"); err != nil || c.Descriptor.ID != "aaaaaaaa1111" {
		t.Errorf("by name: %v %v", c, err)
	}
	if c, err := st.FindByName("aaaaaaaa2222"); err != nil || c.Descriptor.Name != "Work" {
		t.Errorf("by full id: %v %v", c, err)
	}
	if _, err := st.FindByName("aaaaaaaa"); err == nil {
		t.Error("an ambiguous id prefix resolved to a single channel")
	}
	if _, err := st.FindByName("nope"); err == nil {
		t.Error("an unknown name resolved")
	}
}

// --- Envelope -------------------------------------------------------------

// TestEnvelopeRoundTrip verifies a post envelope survives encode/decode with
// its manifest and file key intact — the key is the whole point of the message.
func TestEnvelopeRoundTrip(t *testing.T) {
	env := Envelope{
		V: 1, Chan: "chan1", Type: EnvelopePost, PostedAt: 1712350000,
		Earmark: &Earmark{
			Artist: "John Coltrane", Album: "A Love Supreme", Title: "Resolution",
			Timestamp: 1712345678,
			Blossom: &BlossomManifest{
				Key: "c2VjcmV0", Ext: ".flac",
				Chunks: []BlossomChunk{{Index: 0, SHA256: "abc", Size: 10, Servers: []string{"https://s"}}},
			},
		},
	}
	data, err := env.encode()
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	got, err := decodeEnvelope(data)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Earmark == nil || got.Earmark.Blossom == nil {
		t.Fatal("manifest lost in round trip")
	}
	if got.Earmark.Blossom.Key != "c2VjcmV0" {
		t.Errorf("file key = %q, want c2VjcmV0", got.Earmark.Blossom.Key)
	}
	if got.Earmark.Title != "Resolution" {
		t.Errorf("title = %q", got.Earmark.Title)
	}
}

// TestDecodeEnvelopeRejectsChannelless verifies a message with no channel id is
// rejected rather than silently treated as belonging to some channel.
func TestDecodeEnvelopeRejectsChannelless(t *testing.T) {
	if _, err := decodeEnvelope([]byte(`{"v":1,"type":"post"}`)); err == nil {
		t.Error("an envelope with no channel id was accepted")
	}
}

// TestNewChannelIDIsUnique guards against a constant or short id.
func TestNewChannelIDIsUnique(t *testing.T) {
	seen := make(map[string]struct{})
	for i := 0; i < 100; i++ {
		id, err := newChannelID()
		if err != nil {
			t.Fatalf("newChannelID: %v", err)
		}
		if len(id) != 64 {
			t.Fatalf("channel id length = %d, want 64", len(id))
		}
		if _, dup := seen[id]; dup {
			t.Fatal("newChannelID returned a duplicate")
		}
		seen[id] = struct{}{}
	}
}

// TestPostExpiry verifies the 30-day window on posts.
func TestPostExpiry(t *testing.T) {
	now := time.Now()
	fresh := ChannelPost{PostedAt: now.Add(-29 * 24 * time.Hour).Unix()}
	stale := ChannelPost{PostedAt: now.Add(-31 * 24 * time.Hour).Unix()}
	if fresh.Expired(now) {
		t.Error("a 29-day-old post is expired")
	}
	if !stale.Expired(now) {
		t.Error("a 31-day-old post is not expired")
	}
}

// TestPostRecipientsIncludeSender verifies a post fans out to every member,
// the sender included — a channel is a group the sender participates in, so
// their own other devices must receive the post too.
func TestPostRecipientsIncludeSender(t *testing.T) {
	ch := &Channel{
		Descriptor: ChannelDescriptor{ID: "chan1", Name: "Family Jams", Creator: "self"},
		Members:    []string{"self", "friend"},
	}
	got, err := postRecipients(ch, "self")
	if err != nil {
		t.Fatalf("postRecipients: %v", err)
	}
	if len(got) != 2 || got[0] != "self" || got[1] != "friend" {
		t.Errorf("recipients = %v, want [self friend]", got)
	}
}

// TestPostRecipientsRejectSoloChannel verifies posting into a room with nobody
// else is still an error — the personal earmark list already covers that.
func TestPostRecipientsRejectSoloChannel(t *testing.T) {
	ch := &Channel{
		Descriptor: ChannelDescriptor{ID: "chan1", Name: "Just Me", Creator: "self"},
		Members:    []string{"self"},
	}
	if _, err := postRecipients(ch, "self"); err == nil {
		t.Error("posting into a members-of-one channel was allowed")
	}
}
