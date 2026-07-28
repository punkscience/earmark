package earmark

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"
)

// ChannelEnvelopeVersion is the current channel message format version.
// Receivers ignore envelopes they do not recognise rather than erroring, so
// bumping this stays backward-compatible.
const ChannelEnvelopeVersion = 1

// ChannelPostMaxAge is how long a channel post is live, measured from its
// posted_at. It matches EarmarkMaxAge: a post is a loan of the sender's stash
// for exactly as long as the sender keeps that stash.
const ChannelPostMaxAge = EarmarkMaxAge

// Envelope types.
const (
	EnvelopeInvite = "invite"
	EnvelopeRoster = "roster"
	EnvelopePost   = "post"
	EnvelopeLeave  = "leave"
)

// Envelope is a channel message. It travels as the content of a NIP-59 rumor,
// so it is only ever seen by one recipient at a time, after two layers of
// decryption. One struct covers all four message types; the type field says
// which fields are meaningful.
type Envelope struct {
	V    int    `json:"v"`
	Chan string `json:"chan"`
	Type string `json:"type"`

	// invite, roster
	Name    string   `json:"name,omitempty"`
	Members []string `json:"members,omitempty"`

	// invite
	Creator   string `json:"creator,omitempty"`
	CreatedAt int64  `json:"created_at,omitempty"`

	// roster
	Seq       int   `json:"seq,omitempty"`
	UpdatedAt int64 `json:"updated_at,omitempty"`

	// post
	PostedAt int64    `json:"posted_at,omitempty"`
	Earmark  *Earmark `json:"earmark,omitempty"`

	// leave
	LeftAt int64 `json:"left_at,omitempty"`
}

// ChannelDescriptor identifies a channel. The ID is 32 random bytes and is
// never derived from the name — it appears only inside encrypted payloads.
type ChannelDescriptor struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Creator   string `json:"creator"`
	CreatedAt int64  `json:"created_at"`
}

// Channel is a channel the user has joined, with the roster as they last saw
// it. Seq is the highest roster sequence applied, so a replayed or out-of-order
// roster update can be recognised and dropped.
type Channel struct {
	Descriptor ChannelDescriptor `json:"descriptor"`
	Members    []string          `json:"members"`
	Seq        int               `json:"seq"`
	JoinedAt   int64             `json:"joined_at"`
}

// IsCreator reports whether the given pubkey created this channel, and so may
// change its roster.
func (c *Channel) IsCreator(pubHex string) bool {
	return c.Descriptor.Creator == pubHex
}

// HasMember reports whether pubHex is on the roster.
func (c *Channel) HasMember(pubHex string) bool {
	for _, m := range c.Members {
		if m == pubHex {
			return true
		}
	}
	return false
}

// ChannelInvite is an invite awaiting the user's decision. Trusted records
// whether the sender was in the user's NIP-02 follow list when it arrived —
// it affects how loudly the invite is surfaced and nothing else.
type ChannelInvite struct {
	Descriptor ChannelDescriptor `json:"descriptor"`
	Members    []string          `json:"members"`
	From       string            `json:"from"`
	ReceivedAt int64             `json:"received_at"`
	Trusted    bool              `json:"trusted"`
}

// ChannelPin records chunks this user posted to a channel and must therefore
// keep alive. Purging skips any chunk under a pin younger than
// ChannelPostMaxAge, so cleaning up your own stash cannot break a recipient's
// copy mid-window.
type ChannelPin struct {
	Chan     string   `json:"chan"`
	Chunks   []string `json:"chunks"`
	PostedAt int64    `json:"posted_at"`
}

// ChannelPost is a post received from a channel. Sender is authenticated — it
// is the seal's pubkey, not anything the payload claimed.
type ChannelPost struct {
	Chan     string  `json:"chan"`
	Sender   string  `json:"sender"`
	PostedAt int64   `json:"posted_at"`
	Earmark  Earmark `json:"earmark"`
}

// ID returns a stable identifier for a post, used to dedupe the same message
// arriving from several relays and to address it from the CLI.
func (p *ChannelPost) ID() string {
	return fmt.Sprintf("%s:%s:%d", p.Chan[:8], p.Sender[:8], p.PostedAt)
}

// Expired reports whether the post has aged out of its 30-day window.
func (p *ChannelPost) Expired(now time.Time) bool {
	return p.PostedAt < now.Add(-ChannelPostMaxAge).Unix()
}

// newChannelID returns a fresh 32-byte channel identifier as hex.
func newChannelID() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("could not generate channel id: %w", err)
	}
	return hex.EncodeToString(b), nil
}

func (e *Envelope) encode() ([]byte, error) {
	data, err := json.Marshal(e)
	if err != nil {
		return nil, fmt.Errorf("could not encode channel envelope: %w", err)
	}
	return data, nil
}

func decodeEnvelope(payload []byte) (*Envelope, error) {
	var env Envelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return nil, fmt.Errorf("could not decode channel envelope: %w", err)
	}
	if env.Chan == "" {
		return nil, fmt.Errorf("channel envelope has no channel id")
	}
	return &env, nil
}
