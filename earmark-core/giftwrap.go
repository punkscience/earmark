package earmark

import (
	"encoding/json"
	"fmt"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
	"github.com/nbd-wtf/go-nostr/nip59"
)

// ChannelRumorKind is the event kind of a channel rumor. A rumor is unsigned
// and is only ever seen after two layers of decryption, so this value never
// reaches a relay in the clear — it is an internal discriminator, nothing more.
const ChannelRumorKind = 1737

// GiftWrapQueryBackdate is how far past the content window a gift wrap query
// must reach. NIP-59 allows a seal or wrap to be backdated by up to two days,
// so a query for the last 30 days of content must ask for 32.
const GiftWrapQueryBackdate = 2 * 24 * 60 * 60

// wrapPayload gift-wraps payload for exactly one recipient, per NIP-59:
// an unsigned rumor, sealed under the sender's real key, then wrapped under a
// throwaway key. The only thing a relay learns is that some ephemeral pubkey
// sent something to the recipient.
func wrapPayload(senderPriv, recipientPub string, payload []byte) (nostr.Event, error) {
	senderPub, err := nostr.GetPublicKey(senderPriv)
	if err != nil {
		return nostr.Event{}, fmt.Errorf("could not derive public key: %w", err)
	}
	convKey, err := nip44.GenerateConversationKey(recipientPub, senderPriv)
	if err != nil {
		return nostr.Event{}, fmt.Errorf("could not generate conversation key: %w", err)
	}

	rumor := nostr.Event{
		PubKey:    senderPub,
		CreatedAt: nostr.Now(),
		Kind:      ChannelRumorKind,
		Content:   string(payload),
		Tags:      nostr.Tags{},
	}
	rumor.ID = rumor.GetID()

	gw, err := nip59.GiftWrap(
		rumor,
		recipientPub,
		func(plaintext string) (string, error) { return nip44.Encrypt(plaintext, convKey) },
		func(ev *nostr.Event) error { return ev.Sign(senderPriv) },
		nil,
	)
	if err != nil {
		return nostr.Event{}, fmt.Errorf("could not gift wrap: %w", err)
	}
	return gw, nil
}

// unwrapPayload reverses wrapPayload. It returns the authenticated sender —
// the *seal's* pubkey — and the rumor content.
//
// This is hand-rolled rather than delegated to nip59.GiftUnwrap for one
// reason: GiftUnwrap silently overwrites the rumor's pubkey with the seal's,
// which is safe but leaves nothing to assert. Doing it here lets a forged
// rumor.pubkey be a hard error, which is a rule worth being able to test.
func unwrapPayload(myPriv string, gw *nostr.Event) (sender string, payload []byte, err error) {
	if gw.Kind != nostr.KindGiftWrap {
		return "", nil, fmt.Errorf("not a gift wrap: kind %d", gw.Kind)
	}
	if ok, err := gw.CheckSignature(); !ok {
		return "", nil, fmt.Errorf("gift wrap signature is invalid: %w", err)
	}

	seal, err := decryptEventFrom(myPriv, gw.PubKey, gw.Content)
	if err != nil {
		return "", nil, fmt.Errorf("could not decrypt seal: %w", err)
	}
	if seal.Kind != nostr.KindSeal {
		return "", nil, fmt.Errorf("not a seal: kind %d", seal.Kind)
	}
	if ok, err := seal.CheckSignature(); !ok {
		return "", nil, fmt.Errorf("seal signature is invalid: %w", err)
	}

	rumor, err := decryptEventFrom(myPriv, seal.PubKey, seal.Content)
	if err != nil {
		return "", nil, fmt.Errorf("could not decrypt rumor: %w", err)
	}
	if rumor.Kind != ChannelRumorKind {
		return "", nil, fmt.Errorf("unexpected rumor kind %d", rumor.Kind)
	}
	// The rumor is unsigned, so its pubkey is whatever the sender typed. Only
	// the seal is signed, so only the seal says who this is from.
	if rumor.PubKey != "" && rumor.PubKey != seal.PubKey {
		return "", nil, fmt.Errorf("rumor claims author %s but seal is signed by %s",
			rumor.PubKey[:8], seal.PubKey[:8])
	}
	return seal.PubKey, []byte(rumor.Content), nil
}

// decryptEventFrom NIP-44 decrypts ciphertext sent by otherPub and parses the
// plaintext as a Nostr event.
func decryptEventFrom(myPriv, otherPub, ciphertext string) (*nostr.Event, error) {
	convKey, err := nip44.GenerateConversationKey(otherPub, myPriv)
	if err != nil {
		return nil, fmt.Errorf("could not generate conversation key: %w", err)
	}
	plaintext, err := nip44.Decrypt(ciphertext, convKey)
	if err != nil {
		return nil, err
	}
	var ev nostr.Event
	if err := json.Unmarshal([]byte(plaintext), &ev); err != nil {
		return nil, fmt.Errorf("not a valid event: %w", err)
	}
	return &ev, nil
}
