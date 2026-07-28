package earmark

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// CreateChannel creates a channel owned by the caller and saves it to channel
// state. The creator is its only member until they invite someone; nothing is
// published to a relay beyond the (self-encrypted) state event, because a
// channel has no public existence.
func CreateChannel(ctx context.Context, hexPrivKey, name string) (*Channel, error) {
	if name == "" {
		return nil, fmt.Errorf("channel name cannot be empty")
	}
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return nil, fmt.Errorf("could not derive public key: %w", err)
	}
	id, err := newChannelID()
	if err != nil {
		return nil, err
	}
	st, err := LoadChannelState(ctx, hexPrivKey)
	if err != nil {
		return nil, err
	}
	now := time.Now().Unix()
	ch := Channel{
		Descriptor: ChannelDescriptor{ID: id, Name: name, Creator: pubHex, CreatedAt: now},
		Members:    []string{pubHex},
		Seq:        1,
		JoinedAt:   now,
	}
	st.Channels = append(st.Channels, ch)
	if err := SaveChannelState(ctx, hexPrivKey, st); err != nil {
		return nil, err
	}
	return &ch, nil
}

// InviteToChannel adds a member to a channel the caller created, then tells
// everyone: an invite to the new member and a roster update to the rest.
//
// Only the creator may do this. That is enforced here and again on every
// receiver, so a client that ignores the rule achieves nothing.
func InviteToChannel(ctx context.Context, hexPrivKey, nameOrID, memberPub string) error {
	return mutateRoster(ctx, hexPrivKey, nameOrID, func(ch *Channel) ([]string, error) {
		if ch.HasMember(memberPub) {
			return nil, fmt.Errorf("%s is already a member of %q",
				NpubFromPublicKey(memberPub), ch.Descriptor.Name)
		}
		return append(append([]string{}, ch.Members...), memberPub), nil
	}, memberPub)
}

// RemoveFromChannel drops a member and sends the new roster to everyone,
// including the person removed — their copy shows them absent from a roster
// their creator signed, which is how their client learns to drop the channel.
// Skipping them would leave a dead channel on their screen forever, receiving
// nothing and never saying why.
//
// Removal takes effect because senders stop encrypting to them. Anything they
// already received stays readable; that cannot be undone and the protocol does
// not pretend otherwise.
func RemoveFromChannel(ctx context.Context, hexPrivKey, nameOrID, memberPub string) error {
	return mutateRoster(ctx, hexPrivKey, nameOrID, func(ch *Channel) ([]string, error) {
		if !ch.HasMember(memberPub) {
			return nil, fmt.Errorf("%s is not a member of %q",
				NpubFromPublicKey(memberPub), ch.Descriptor.Name)
		}
		if memberPub == ch.Descriptor.Creator {
			return nil, fmt.Errorf("the creator cannot be removed from their own channel")
		}
		members := make([]string, 0, len(ch.Members))
		for _, m := range ch.Members {
			if m != memberPub {
				members = append(members, m)
			}
		}
		return members, nil
	})
}

// mutateRoster applies change to a channel the caller created, bumps the
// sequence, saves, and distributes the result: a full invite to each pubkey in
// invitees, a roster update to every other member.
func mutateRoster(ctx context.Context, hexPrivKey, nameOrID string,
	change func(*Channel) ([]string, error), invitees ...string) error {

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
	if !ch.IsCreator(pubHex) {
		return fmt.Errorf("only %s can change the roster of %q",
			NpubFromPublicKey(ch.Descriptor.Creator), ch.Descriptor.Name)
	}
	before := append([]string{}, ch.Members...)
	members, err := change(ch)
	if err != nil {
		return err
	}
	ch.Members = members
	ch.Seq++
	if err := SaveChannelState(ctx, hexPrivKey, st); err != nil {
		return err
	}

	now := time.Now().Unix()
	isInvitee := make(map[string]bool, len(invitees))
	for _, p := range invitees {
		isInvitee[p] = true
	}

	roster := Envelope{
		V: ChannelEnvelopeVersion, Chan: ch.Descriptor.ID, Type: EnvelopeRoster,
		Name: ch.Descriptor.Name, Members: ch.Members, Seq: ch.Seq, UpdatedAt: now,
	}
	invite := Envelope{
		V: ChannelEnvelopeVersion, Chan: ch.Descriptor.ID, Type: EnvelopeInvite,
		Name: ch.Descriptor.Name, Members: ch.Members,
		Creator: ch.Descriptor.Creator, CreatedAt: ch.Descriptor.CreatedAt,
	}

	// Anyone dropped by this change still gets the roster: it is the only
	// signal that tells their client to let the channel go.
	recipients := append([]string{}, ch.Members...)
	for _, m := range before {
		if !containsString(recipients, m) {
			recipients = append(recipients, m)
		}
	}

	var events []nostr.Event
	for _, m := range recipients {
		if m == pubHex {
			continue
		}
		env := roster
		if isInvitee[m] {
			env = invite
		}
		payload, err := env.encode()
		if err != nil {
			return err
		}
		gw, err := wrapPayload(hexPrivKey, m, payload)
		if err != nil {
			return fmt.Errorf("could not wrap for %s: %w", NpubFromPublicKey(m), err)
		}
		events = append(events, gw)
	}
	return publishGiftWraps(ctx, events)
}

func containsString(haystack []string, needle string) bool {
	for _, s := range haystack {
		if s == needle {
			return true
		}
	}
	return false
}

// LeaveChannel removes the caller from a channel and tells the other members
// to stop encrypting to them. The creator cannot leave — the roster would have
// no authority left — so for them this deletes the channel locally instead.
func LeaveChannel(ctx context.Context, hexPrivKey, nameOrID string) error {
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

	env := Envelope{
		V: ChannelEnvelopeVersion, Chan: ch.Descriptor.ID,
		Type: EnvelopeLeave, LeftAt: time.Now().Unix(),
	}
	payload, err := env.encode()
	if err != nil {
		return err
	}
	var events []nostr.Event
	for _, m := range ch.Members {
		if m == pubHex {
			continue
		}
		gw, err := wrapPayload(hexPrivKey, m, payload)
		if err != nil {
			return fmt.Errorf("could not wrap for %s: %w", NpubFromPublicKey(m), err)
		}
		events = append(events, gw)
	}
	if err := publishGiftWraps(ctx, events); err != nil {
		return err
	}

	st.removeChannel(ch.Descriptor.ID)
	return SaveChannelState(ctx, hexPrivKey, st)
}

// AcceptInvite moves a pending invite into the user's channel list. Nothing is
// sent back: the creator already added them to the roster, and acceptance only
// governs whether this client bothers to read the channel.
func AcceptInvite(ctx context.Context, hexPrivKey, chanID string) (*Channel, error) {
	st, err := LoadChannelState(ctx, hexPrivKey)
	if err != nil {
		return nil, err
	}
	inv := st.FindInvite(chanID)
	if inv == nil {
		return nil, fmt.Errorf("no pending invite for channel %s", chanID)
	}
	if existing := st.Find(chanID); existing != nil {
		return existing, nil
	}
	ch := Channel{
		Descriptor: inv.Descriptor,
		Members:    inv.Members,
		JoinedAt:   time.Now().Unix(),
	}
	st.Channels = append(st.Channels, ch)
	st.Invites = removeInvite(st.Invites, chanID)
	if err := SaveChannelState(ctx, hexPrivKey, st); err != nil {
		return nil, err
	}
	return &ch, nil
}

// DeclineInvite drops an invite and remembers the decision, so a re-sent
// invite for the same channel is not surfaced again.
func DeclineInvite(ctx context.Context, hexPrivKey, chanID string) error {
	st, err := LoadChannelState(ctx, hexPrivKey)
	if err != nil {
		return err
	}
	if st.FindInvite(chanID) == nil {
		return fmt.Errorf("no pending invite for channel %s", chanID)
	}
	st.Invites = removeInvite(st.Invites, chanID)
	if !st.hasDeclined(chanID) {
		st.Declined = append(st.Declined, chanID)
	}
	return SaveChannelState(ctx, hexPrivKey, st)
}

func removeInvite(invites []ChannelInvite, chanID string) []ChannelInvite {
	kept := invites[:0]
	for _, inv := range invites {
		if inv.Descriptor.ID != chanID {
			kept = append(kept, inv)
		}
	}
	return kept
}

// applyRoster applies a roster message to state. It returns true when state
// changed.
//
// Two rules, both load-bearing: the message must be sealed by the channel's
// creator, and its sequence must advance. The first stops a member widening
// the audience for music someone else posts; the second stops a replayed old
// roster resurrecting a removed member.
func applyRoster(st *ChannelState, self, sender string, env *Envelope) bool {
	ch := st.Find(env.Chan)
	if ch == nil {
		return false
	}
	if sender != ch.Descriptor.Creator {
		return false
	}
	if env.Seq <= ch.Seq {
		return false
	}
	// Being absent from a roster the creator signed is how removal arrives —
	// there is no separate "you're out" message. Drop the channel and its pins.
	stillMember := false
	for _, m := range env.Members {
		if m == self {
			stillMember = true
			break
		}
	}
	if !stillMember {
		st.removeChannel(env.Chan)
		return true
	}
	ch.Members = env.Members
	ch.Seq = env.Seq
	if env.Name != "" {
		ch.Descriptor.Name = env.Name
	}
	return true
}

// applyLeave removes a self-departing member. A leave can only ever remove its
// own sender; one naming anyone else is meaningless and ignored.
func applyLeave(st *ChannelState, sender string, env *Envelope) bool {
	ch := st.Find(env.Chan)
	if ch == nil || !ch.HasMember(sender) {
		return false
	}
	members := make([]string, 0, len(ch.Members))
	for _, m := range ch.Members {
		if m != sender {
			members = append(members, m)
		}
	}
	ch.Members = members
	return true
}

// applyInvite records a pending invite. Invites for channels already joined or
// previously declined are dropped.
func applyInvite(st *ChannelState, sender string, env *Envelope, trusted bool) bool {
	if st.Find(env.Chan) != nil || st.hasDeclined(env.Chan) {
		return false
	}
	// The creator named in the invite must be the one who sent it. Anyone can
	// send an invite; nobody can send one on someone else's behalf.
	if env.Creator != sender {
		return false
	}
	if existing := st.FindInvite(env.Chan); existing != nil {
		existing.Members = env.Members
		existing.Trusted = trusted
		return true
	}
	st.Invites = append(st.Invites, ChannelInvite{
		Descriptor: ChannelDescriptor{
			ID: env.Chan, Name: env.Name,
			Creator: env.Creator, CreatedAt: env.CreatedAt,
		},
		Members:    env.Members,
		From:       sender,
		ReceivedAt: time.Now().Unix(),
		Trusted:    trusted,
	})
	return true
}
