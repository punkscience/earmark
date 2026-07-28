package main

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

const (
	earmarkListTag       = "derpy-earmarks"
	legacyEarmarkListTag = "earmark-earmarks"
	earmarkKind          = 30001
	EarmarkMaxAge        = 30 * 24 * time.Hour
)

// Earmark represents a single earmarked track.
type Earmark struct {
	Artist    string           `json:"artist"`
	Album     string           `json:"album"`
	Title     string           `json:"title"`
	Path      string           `json:"path,omitempty"`
	Timestamp int64            `json:"ts"`
	Blossom   *BlossomManifest `json:"blossom,omitempty"`
}

func selfConvKey(hexPrivKey string) ([32]byte, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return [32]byte{}, fmt.Errorf("could not derive public key: %w", err)
	}
	key, err := nip44.GenerateConversationKey(pubHex, hexPrivKey)
	if err != nil {
		return [32]byte{}, fmt.Errorf("could not generate conversation key: %w", err)
	}
	return key, nil
}

// FetchEarmarks fetches and decrypts the private earmark list from Nostr.
func FetchEarmarks(hexPrivKey string) ([]Earmark, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return fetchEarmarksCtx(ctx, hexPrivKey)
}

func fetchEarmarksCtx(ctx context.Context, hexPrivKey string) ([]Earmark, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return nil, fmt.Errorf("could not derive public key: %w", err)
	}
	convKey, err := selfConvKey(hexPrivKey)
	if err != nil {
		return nil, err
	}
	latest := fetchEarmarkEvent(ctx, pubHex, earmarkListTag)
	if latest == nil {
		return []Earmark{}, nil
	}
	return decryptEarmarks(latest, convKey)
}

// fetchEarmarkEvent fetches the latest kind-30001 earmark event with the given d tag.
func fetchEarmarkEvent(ctx context.Context, pubHex, dTag string) *nostr.Event {
	filter := nostr.Filter{
		Kinds:   []int{earmarkKind},
		Authors: []string{pubHex},
		Tags:    nostr.TagMap{"d": []string{dTag}},
		Limit:   1,
	}
	return queryRelays(ctx, LoadNostrRelays(), filter)
}

func decryptEarmarks(ev *nostr.Event, convKey [32]byte) ([]Earmark, error) {
	plaintext, err := nip44.Decrypt(ev.Content, convKey)
	if err != nil {
		return nil, fmt.Errorf("could not decrypt earmark list: %w", err)
	}
	var earmarks []Earmark
	if err := json.Unmarshal([]byte(plaintext), &earmarks); err != nil {
		return nil, fmt.Errorf("could not parse earmark list: %w", err)
	}
	return earmarks, nil
}

// MigrateLegacyEarmarks merges any list published under the legacy d tag into
// the current list, republishes it, and issues a NIP-09 deletion for the
// legacy event. Returns the number of earmarks merged in.
// Each step gets its own timeout: one unresponsive relay holds queryRelays
// until the deadline, and a shared context would leave later steps no time —
// worse, an expired fetch of the current list looks like an empty list.
func MigrateLegacyEarmarks(hexPrivKey string) (int, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return 0, fmt.Errorf("could not derive public key: %w", err)
	}
	legacyCtx, legacyCancel := context.WithTimeout(context.Background(), 15*time.Second)
	legacyEv := fetchEarmarkEvent(legacyCtx, pubHex, legacyEarmarkListTag)
	legacyCancel()
	if legacyEv == nil {
		return 0, nil
	}
	convKey, err := selfConvKey(hexPrivKey)
	if err != nil {
		return 0, err
	}
	legacy, err := decryptEarmarks(legacyEv, convKey)
	if err != nil {
		return 0, fmt.Errorf("could not read legacy earmark list: %w", err)
	}
	current, err := FetchEarmarks(hexPrivKey)
	if err != nil {
		return 0, fmt.Errorf("could not fetch current earmark list: %w", err)
	}
	merged := current
	added := 0
	for _, e := range legacy {
		if !isDuplicateEarmark(merged, e) {
			merged = append(merged, e)
			added++
		}
	}
	if added > 0 {
		publishCtx, publishCancel := context.WithTimeout(context.Background(), 15*time.Second)
		err := publishEarmarks(publishCtx, hexPrivKey, merged)
		publishCancel()
		if err != nil {
			return 0, fmt.Errorf("could not publish merged earmark list: %w", err)
		}
	}
	// Legacy data is safe under the current tag; delete the legacy event (NIP-09).
	del := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      5,
		Content:   "migrated to " + earmarkListTag,
		Tags: nostr.Tags{
			{"e", legacyEv.ID},
			{"a", fmt.Sprintf("%d:%s:%s", earmarkKind, pubHex, legacyEarmarkListTag)},
			{"k", fmt.Sprintf("%d", earmarkKind)},
		},
	}
	if err := del.Sign(hexPrivKey); err != nil {
		return added, fmt.Errorf("could not sign deletion event: %w", err)
	}
	delCtx, delCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer delCancel()
	if err := publishToRelays(delCtx, LoadNostrRelays(), del); err != nil {
		return added, fmt.Errorf("could not publish deletion of legacy list: %w", err)
	}
	return added, nil
}

func isDuplicateEarmark(existing []Earmark, e Earmark) bool {
	for _, x := range existing {
		if e.Path != "" && x.Path == e.Path {
			return true
		}
		if e.Path == "" && x.Artist == e.Artist && x.Album == e.Album && x.Title == e.Title {
			return true
		}
	}
	return false
}

// AddEarmark appends a new earmark and republishes the encrypted list.
func AddEarmark(hexPrivKey string, e Earmark) error {
	fetchCtx, fetchCancel := context.WithTimeout(context.Background(), 15*time.Second)
	existing, _ := fetchEarmarksCtx(fetchCtx, hexPrivKey)
	fetchCancel()

	if isDuplicateEarmark(existing, e) {
		return nil
	}

	publishCtx, publishCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer publishCancel()
	return publishEarmarks(publishCtx, hexPrivKey, append(existing, e))
}

// UpdateEarmark replaces an existing earmark (matched by Timestamp).
func UpdateEarmark(hexPrivKey string, updated Earmark) error {
	fetchCtx, fetchCancel := context.WithTimeout(context.Background(), 15*time.Second)
	existing, err := fetchEarmarksCtx(fetchCtx, hexPrivKey)
	fetchCancel()
	if err != nil {
		return fmt.Errorf("could not fetch earmarks: %w", err)
	}
	found := false
	for i, e := range existing {
		if e.Timestamp == updated.Timestamp {
			existing[i] = updated
			found = true
			break
		}
	}
	if !found {
		return fmt.Errorf("no earmark with timestamp %d found", updated.Timestamp)
	}
	publishCtx, publishCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer publishCancel()
	return publishEarmarks(publishCtx, hexPrivKey, existing)
}

// CleanupOldEarmarks removes earmarks older than EarmarkMaxAge.
func CleanupOldEarmarks(hexPrivKey string) (int, error) {
	earmarks, err := FetchEarmarks(hexPrivKey)
	if err != nil {
		return 0, fmt.Errorf("could not fetch earmarks for cleanup: %w", err)
	}
	cutoff := time.Now().Add(-EarmarkMaxAge).Unix()
	var keep, remove []Earmark
	for _, e := range earmarks {
		if e.Timestamp < cutoff {
			remove = append(remove, e)
		} else {
			keep = append(keep, e)
		}
	}
	if len(remove) == 0 {
		return 0, nil
	}
	blossomCtx, blossomCancel := context.WithTimeout(context.Background(), 2*time.Minute)
	var wg sync.WaitGroup
	for _, e := range remove {
		if e.Blossom != nil {
			e := e
			wg.Add(1)
			go func() {
				defer wg.Done()
				DeleteManifestChunks(blossomCtx, hexPrivKey, e.Blossom)
			}()
		}
	}
	wg.Wait()
	blossomCancel()

	publishCtx, publishCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer publishCancel()
	if err := publishEarmarks(publishCtx, hexPrivKey, keep); err != nil {
		return 0, fmt.Errorf("could not republish after cleanup: %w", err)
	}
	return len(remove), nil
}

func publishEarmarks(ctx context.Context, hexPrivKey string, earmarks []Earmark) error {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return fmt.Errorf("could not derive public key: %w", err)
	}
	convKey, err := selfConvKey(hexPrivKey)
	if err != nil {
		return err
	}
	data, err := json.Marshal(earmarks)
	if err != nil {
		return fmt.Errorf("could not marshal earmarks: %w", err)
	}
	ciphertext, err := nip44.Encrypt(string(data), convKey)
	if err != nil {
		return fmt.Errorf("could not encrypt earmarks: %w", err)
	}
	ev := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      earmarkKind,
		Content:   ciphertext,
		Tags:      nostr.Tags{{"d", earmarkListTag}},
	}
	if err := ev.Sign(hexPrivKey); err != nil {
		return fmt.Errorf("could not sign earmark event: %w", err)
	}
	return publishToRelays(ctx, LoadNostrRelays(), ev)
}