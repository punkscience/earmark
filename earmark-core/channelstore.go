package earmark

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// channelStateTag is the d tag of the self-encrypted addressable event holding
// channel state. It sits alongside the earmark list under the same kind, so it
// syncs across devices with the user's key and nothing else.
const channelStateTag = "earmark-channels"

// ChannelState is everything a client needs to know about its channels. It is
// stored NIP-44 self-encrypted in a kind-30001 event, exactly like the earmark
// list.
type ChannelState struct {
	V        int             `json:"v"`
	Channels []Channel       `json:"channels"`
	Invites  []ChannelInvite `json:"invites,omitempty"`
	// Declined holds channel ids the user turned down, so a re-sent invite is
	// not surfaced again.
	Declined []string     `json:"declined,omitempty"`
	Pins     []ChannelPin `json:"pins,omitempty"`
}

// Find returns the channel with the given id, or nil.
func (s *ChannelState) Find(chanID string) *Channel {
	for i := range s.Channels {
		if s.Channels[i].Descriptor.ID == chanID {
			return &s.Channels[i]
		}
	}
	return nil
}

// FindByName returns the single channel whose name matches, or an error when
// the name is unknown or ambiguous. Channel names are a UI affordance and are
// not unique by construction, so callers may also pass a full or partial id.
func (s *ChannelState) FindByName(nameOrID string) (*Channel, error) {
	var matches []*Channel
	for i := range s.Channels {
		c := &s.Channels[i]
		if c.Descriptor.ID == nameOrID || c.Descriptor.Name == nameOrID {
			return c, nil
		}
		if len(nameOrID) >= 8 && len(c.Descriptor.ID) >= len(nameOrID) &&
			c.Descriptor.ID[:len(nameOrID)] == nameOrID {
			matches = append(matches, c)
		}
	}
	switch len(matches) {
	case 0:
		return nil, fmt.Errorf("no channel named %q", nameOrID)
	case 1:
		return matches[0], nil
	default:
		return nil, fmt.Errorf("%q matches %d channels — use the full id", nameOrID, len(matches))
	}
}

// FindInvite returns the pending invite for the given channel id, or nil.
func (s *ChannelState) FindInvite(chanID string) *ChannelInvite {
	for i := range s.Invites {
		if s.Invites[i].Descriptor.ID == chanID {
			return &s.Invites[i]
		}
	}
	return nil
}

func (s *ChannelState) hasDeclined(chanID string) bool {
	for _, id := range s.Declined {
		if id == chanID {
			return true
		}
	}
	return false
}

// removeChannel drops a channel and every pin belonging to it.
func (s *ChannelState) removeChannel(chanID string) {
	channels := s.Channels[:0]
	for _, c := range s.Channels {
		if c.Descriptor.ID != chanID {
			channels = append(channels, c)
		}
	}
	s.Channels = channels
	pins := s.Pins[:0]
	for _, p := range s.Pins {
		if p.Chan != chanID {
			pins = append(pins, p)
		}
	}
	s.Pins = pins
}

// PinnedChunks returns the set of chunk hashes currently protected from
// deletion: every chunk under a pin younger than ChannelPostMaxAge. Pins past
// that age are not returned, so they fall out of protection naturally.
func (s *ChannelState) PinnedChunks(now time.Time) map[string]struct{} {
	cutoff := now.Add(-ChannelPostMaxAge).Unix()
	pinned := make(map[string]struct{})
	for _, p := range s.Pins {
		if p.PostedAt < cutoff {
			continue
		}
		for _, h := range p.Chunks {
			pinned[h] = struct{}{}
		}
	}
	return pinned
}

// dropExpiredPins removes pins past their window. Returns true when anything
// was removed.
func (s *ChannelState) dropExpiredPins(now time.Time) bool {
	cutoff := now.Add(-ChannelPostMaxAge).Unix()
	kept := s.Pins[:0]
	for _, p := range s.Pins {
		if p.PostedAt >= cutoff {
			kept = append(kept, p)
		}
	}
	changed := len(kept) != len(s.Pins)
	s.Pins = kept
	return changed
}

// LoadChannelState fetches and decrypts the user's channel state. A user with
// no channels yet gets an empty state, not an error.
func LoadChannelState(ctx context.Context, hexPrivKey string) (*ChannelState, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return nil, fmt.Errorf("could not derive public key: %w", err)
	}
	convKey, err := selfConvKey(hexPrivKey)
	if err != nil {
		return nil, err
	}
	ev := fetchEarmarkEvent(ctx, pubHex, channelStateTag)
	if ev == nil {
		return &ChannelState{V: ChannelEnvelopeVersion}, nil
	}
	plaintext, err := nip44.Decrypt(ev.Content, convKey)
	if err != nil {
		return nil, fmt.Errorf("could not decrypt channel state: %w", err)
	}
	var st ChannelState
	if err := json.Unmarshal([]byte(plaintext), &st); err != nil {
		return nil, fmt.Errorf("could not parse channel state: %w", err)
	}
	if st.V == 0 {
		st.V = ChannelEnvelopeVersion
	}
	return &st, nil
}

// SaveChannelState encrypts and publishes the user's channel state.
func SaveChannelState(ctx context.Context, hexPrivKey string, st *ChannelState) error {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return fmt.Errorf("could not derive public key: %w", err)
	}
	convKey, err := selfConvKey(hexPrivKey)
	if err != nil {
		return err
	}
	if st.V == 0 {
		st.V = ChannelEnvelopeVersion
	}
	data, err := json.Marshal(st)
	if err != nil {
		return fmt.Errorf("could not marshal channel state: %w", err)
	}
	ciphertext, err := nip44.Encrypt(string(data), convKey)
	if err != nil {
		return fmt.Errorf("could not encrypt channel state: %w", err)
	}
	ev := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      earmarkKind,
		Content:   ciphertext,
		Tags:      nostr.Tags{{"d", channelStateTag}},
	}
	if err := ev.Sign(hexPrivKey); err != nil {
		return fmt.Errorf("could not sign channel state event: %w", err)
	}
	// Same outbox reasoning as the earmark list: channel state is how a second
	// device recovers its channels, so it has to land where that device looks.
	return PublishToRelays(ctx, UserPublishRelays(pubHex), ev)
}
