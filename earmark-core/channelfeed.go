package earmark

import (
	"context"
	"fmt"
	"sort"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// PostToChannel sends an earmark to every other member of a channel and pins
// its chunks so the caller's own purge cannot break the recipients' copies.
//
// Nothing is uploaded: the earmark already lives on Blossom, and the post hands
// over the same chunk hashes and the same file key. Cost is one gift wrap per
// recipient.
func PostToChannel(ctx context.Context, hexPrivKey, nameOrID string, e Earmark) error {
	if e.Blossom == nil {
		return fmt.Errorf("%q was never uploaded — there is nothing to share", e.Title)
	}
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return fmt.Errorf("could not derive public key: %w", err)
	}
	st, err := LoadChannelState(ctx, hexPrivKey)
	if err != nil {
		return err
	}
	ch, err := st.FindByName(nameOrID)
	if err != nil {
		return err
	}
	recipients := make([]string, 0, len(ch.Members))
	for _, m := range ch.Members {
		if m != pubHex {
			recipients = append(recipients, m)
		}
	}
	if len(recipients) == 0 {
		return fmt.Errorf("%q has no other members yet — invite someone first", ch.Descriptor.Name)
	}

	// The originating file path is meaningless to anyone else, and naming a
	// directory on your own machine is not something to hand out.
	shared := e
	shared.Path = ""

	now := time.Now().Unix()
	env := Envelope{
		V: ChannelEnvelopeVersion, Chan: ch.Descriptor.ID, Type: EnvelopePost,
		PostedAt: now, Earmark: &shared,
	}
	payload, err := env.encode()
	if err != nil {
		return err
	}
	var events []nostr.Event
	for _, m := range recipients {
		gw, err := wrapPayload(hexPrivKey, m, payload)
		if err != nil {
			return fmt.Errorf("could not wrap for %s: %w", NpubFromPublicKey(m), err)
		}
		events = append(events, gw)
	}
	if err := publishGiftWraps(ctx, events); err != nil {
		return err
	}

	chunks := make([]string, 0, len(shared.Blossom.Chunks))
	for _, c := range shared.Blossom.Chunks {
		chunks = append(chunks, c.SHA256)
	}
	st.Pins = append(st.Pins, ChannelPin{Chan: ch.Descriptor.ID, Chunks: chunks, PostedAt: now})
	st.dropExpiredPins(time.Now())
	return SaveChannelState(ctx, hexPrivKey, st)
}

// SyncChannels fetches gift wraps addressed to the user, applies every roster,
// invite and leave message to channel state, and returns the live posts.
//
// The updated state is returned alongside the posts so callers do not have to
// re-fetch it. State is saved only when something actually changed, so a poll
// that finds nothing new costs no relay writes.
func SyncChannels(ctx context.Context, hexPrivKey string) ([]ChannelPost, *ChannelState, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return nil, nil, fmt.Errorf("could not derive public key: %w", err)
	}
	st, err := LoadChannelState(ctx, hexPrivKey)
	if err != nil {
		return nil, nil, err
	}

	now := time.Now()
	since := nostr.Timestamp(now.Add(-ChannelPostMaxAge).Unix() - GiftWrapQueryBackdate)
	filter := nostr.Filter{
		Kinds: []int{nostr.KindGiftWrap},
		Tags:  nostr.TagMap{"p": []string{pubHex}},
		Since: &since,
	}
	// Gift wraps addressed to us land on the relays we advertise as our inbox,
	// which is where other people's clients were told to send them.
	wraps := QueryRelaysAll(ctx, UserInboxRelays(pubHex), filter)

	// Follows only decide how loudly an invite arrives. Failing to fetch them
	// must not block the sync, so an empty list simply means "nothing trusted".
	trusted := make(map[string]bool)
	for _, f := range FetchFollows(ctx, pubHex) {
		trusted[f] = true
	}

	changed := st.dropExpiredPins(now)
	seen := make(map[string]struct{})
	var posts []ChannelPost

	// Roster updates must land before posts are judged, or a post from a member
	// added in the same batch would be rejected as coming from a stranger.
	type pending struct {
		sender string
		env    *Envelope
	}
	var latePosts []pending

	for _, gw := range wraps {
		sender, payload, err := unwrapPayload(hexPrivKey, gw)
		if err != nil {
			// Someone else's mail, spam, or a forgery. None of these are ours
			// to report.
			continue
		}
		env, err := decodeEnvelope(payload)
		if err != nil || env.V > ChannelEnvelopeVersion {
			continue
		}
		switch env.Type {
		case EnvelopeRoster:
			if applyRoster(st, pubHex, sender, env) {
				changed = true
			}
		case EnvelopeInvite:
			if applyInvite(st, sender, env, trusted[sender]) {
				changed = true
			}
		case EnvelopeLeave:
			if applyLeave(st, sender, env) {
				changed = true
			}
		case EnvelopePost:
			latePosts = append(latePosts, pending{sender, env})
		}
	}

	for _, p := range latePosts {
		env := p.env
		if env.Earmark == nil || env.Earmark.Blossom == nil {
			continue
		}
		ch := st.Find(env.Chan)
		// A post for a channel we are not in, or from someone not on its
		// roster, is dropped without a word. This is the spam defence.
		if ch == nil || !ch.HasMember(p.sender) {
			continue
		}
		post := ChannelPost{
			Chan:     env.Chan,
			Sender:   p.sender,
			PostedAt: env.PostedAt,
			Earmark:  *env.Earmark,
		}
		if post.Expired(now) {
			continue
		}
		// The same post arrives once per relay.
		id := post.ID()
		if _, dup := seen[id]; dup {
			continue
		}
		seen[id] = struct{}{}
		posts = append(posts, post)
	}

	sort.Slice(posts, func(i, j int) bool { return posts[i].PostedAt > posts[j].PostedAt })

	if changed {
		if err := SaveChannelState(ctx, hexPrivKey, st); err != nil {
			return posts, st, fmt.Errorf("could not save channel state: %w", err)
		}
	}
	return posts, st, nil
}

// KeepPost adopts a channel post into the user's own stash: the chunks are
// copied to the user's own Blossom servers and the earmark is recorded in their
// personal list with a fresh timestamp, starting a new 30-day clock they own.
//
// The copy is a server-side mirror where possible, so no audio moves through
// this machine. Key and chunk hashes are unchanged — this is a change of
// hosting, not of encryption.
func KeepPost(ctx context.Context, hexPrivKey string, post *ChannelPost) error {
	if post.Earmark.Blossom == nil {
		return fmt.Errorf("post has nothing to keep")
	}
	servers, err := ResolveBlossomServers(hexPrivKey)
	if err != nil {
		return err
	}
	adopted := post.Earmark
	adopted.Path = ""
	adopted.Timestamp = time.Now().Unix()

	manifest := *post.Earmark.Blossom
	chunks := make([]BlossomChunk, len(manifest.Chunks))
	for i, c := range manifest.Chunks {
		hosted, err := MirrorChunk(ctx, hexPrivKey, c, servers)
		if err != nil {
			return fmt.Errorf("could not keep chunk %d: %w", c.Index, err)
		}
		chunk := c
		chunk.Servers = unionStrings(hosted, c.Servers)
		chunks[i] = chunk
	}
	manifest.Chunks = chunks
	adopted.Blossom = &manifest

	return AddEarmark(hexPrivKey, adopted)
}

// publishGiftWraps publishes each wrap to the relays its recipient actually
// reads (NIP-17 kind 10050), read off the wrap's own p tag.
//
// Publishing to the sender's relays instead only works when both parties
// happen to share one. When they do not it fails silently — the publish
// succeeds and the recipient never sees the message — which makes channels
// unusable between two people on disjoint relay sets.
//
// Wraps go out one at a time. Relays rate-limit bursts, and a channel post
// fans out to every member at once, so this deliberately does not parallelise.
func publishGiftWraps(ctx context.Context, events []nostr.Event) error {
	for _, ev := range events {
		relays := Relays()
		if p := ev.Tags.GetFirst([]string{"p"}); p != nil && p.Value() != "" {
			relays = UserInboxRelays(p.Value())
		}
		if err := PublishToRelays(ctx, relays, ev); err != nil {
			return err
		}
	}
	return nil
}
