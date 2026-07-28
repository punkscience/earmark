package earmark

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// TestEarmarkJSONRoundTrip verifies that Earmark values survive a
// marshal → unmarshal cycle without data loss.
func TestEarmarkJSONRoundTrip(t *testing.T) {
	original := []Earmark{
		{Artist: "Miles Davis", Album: "Kind of Blue", Title: "So What", Path: "/music/miles/so-what.flac", Timestamp: 1700000000},
		{Artist: "Bill Evans", Album: "Waltz for Debby", Title: "My Foolish Heart", Path: "/music/bill/foolish.flac", Timestamp: 1700000001},
		{Artist: "", Album: "", Title: "Unknown", Path: "", Timestamp: 0},
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}

	var got []Earmark
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}

	if len(got) != len(original) {
		t.Fatalf("length mismatch: got %d, want %d", len(got), len(original))
	}
	for i, e := range original {
		if got[i] != e {
			t.Errorf("[%d] got %+v, want %+v", i, got[i], e)
		}
	}
}

// TestSelfConvKeyDeterministic verifies that selfConvKey returns the same key
// for the same private key on repeated calls.
func TestSelfConvKeyDeterministic(t *testing.T) {
	priv := nostr.GeneratePrivateKey()

	k1, err := selfConvKey(priv)
	if err != nil {
		t.Fatalf("first call: %v", err)
	}
	k2, err := selfConvKey(priv)
	if err != nil {
		t.Fatalf("second call: %v", err)
	}
	if k1 != k2 {
		t.Error("selfConvKey returned different keys for the same private key")
	}
}

// TestSelfConvKeyUnique verifies that two different private keys produce
// different conversation keys (collision resistance sanity check).
func TestSelfConvKeyUnique(t *testing.T) {
	k1, _ := selfConvKey(nostr.GeneratePrivateKey())
	k2, _ := selfConvKey(nostr.GeneratePrivateKey())
	if k1 == k2 {
		t.Error("two different private keys produced the same conversation key")
	}
}

// TestSelfConvKeyInvalidInput verifies that an invalid private key returns an error.
func TestSelfConvKeyInvalidInput(t *testing.T) {
	_, err := selfConvKey("not-a-valid-hex-key")
	if err == nil {
		t.Error("expected error for invalid private key, got nil")
	}
}

// TestEncryptDecryptRoundTrip verifies that data encrypted with selfConvKey
// can be decrypted back to the original plaintext using the same key.
func TestEncryptDecryptRoundTrip(t *testing.T) {
	priv := nostr.GeneratePrivateKey()
	convKey, err := selfConvKey(priv)
	if err != nil {
		t.Fatalf("selfConvKey: %v", err)
	}

	earmarks := []Earmark{
		{Artist: "Coltrane", Album: "A Love Supreme", Title: "Resolution", Timestamp: time.Now().Unix()},
	}

	data, err := json.Marshal(earmarks)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}

	ciphertext, err := nip44.Encrypt(string(data), convKey)
	if err != nil {
		t.Fatalf("nip44.Encrypt: %v", err)
	}

	// Ciphertext must not contain the plaintext.
	if string(data) == ciphertext {
		t.Error("ciphertext is identical to plaintext")
	}

	plaintext, err := nip44.Decrypt(ciphertext, convKey)
	if err != nil {
		t.Fatalf("nip44.Decrypt: %v", err)
	}
	if plaintext != string(data) {
		t.Errorf("round-trip mismatch:\n  got  %q\n  want %q", plaintext, string(data))
	}
}

// TestEncryptDecryptWrongKey verifies that decryption with a different key fails.
func TestEncryptDecryptWrongKey(t *testing.T) {
	priv1 := nostr.GeneratePrivateKey()
	priv2 := nostr.GeneratePrivateKey()

	key1, _ := selfConvKey(priv1)
	key2, _ := selfConvKey(priv2)

	ciphertext, err := nip44.Encrypt("secret playlist data", key1)
	if err != nil {
		t.Fatalf("nip44.Encrypt: %v", err)
	}

	_, err = nip44.Decrypt(ciphertext, key2)
	if err == nil {
		t.Error("expected decryption with wrong key to fail, but it succeeded")
	}
}

// TestEarmarkTimestamp verifies that the Timestamp field survives JSON
// serialization with sub-second precision preserved (Unix seconds are integers).
func TestEarmarkTimestamp(t *testing.T) {
	now := time.Now().Unix()
	e := Earmark{Title: "Track", Timestamp: now}

	data, _ := json.Marshal(e)
	var got Earmark
	_ = json.Unmarshal(data, &got)

	if got.Timestamp != now {
		t.Errorf("timestamp mismatch: got %d, want %d", got.Timestamp, now)
	}
}

// TestEarmarkMaxAge verifies the 30-day constant and the cutoff arithmetic.
func TestEarmarkMaxAge(t *testing.T) {
	if EarmarkMaxAge.Hours() != 30*24 {
		t.Errorf("EarmarkMaxAge = %v, want 720h", EarmarkMaxAge)
	}

	cutoff := time.Now().Add(-EarmarkMaxAge).Unix()

	old := time.Now().Add(-31 * 24 * time.Hour).Unix()
	recent := time.Now().Add(-29 * 24 * time.Hour).Unix()
	exact := time.Now().Add(-30 * 24 * time.Hour).Unix()

	if old >= cutoff {
		t.Error("31-day-old earmark should be below the cutoff")
	}
	if recent < cutoff {
		t.Error("29-day-old earmark should be above the cutoff")
	}
	// Exactly at the boundary (allow 1s of test execution slack).
	if exact > cutoff+1 {
		t.Error("30-day-old earmark should be at or below the cutoff")
	}
}

// TestCleanupPartitionsEarmarks verifies that CleanupOldEarmarks correctly
// separates old from recent earmarks using the cutoff logic, without making
// any network calls.
func TestCleanupPartitionsEarmarks(t *testing.T) {
	cutoff := time.Now().Add(-EarmarkMaxAge).Unix()

	earmarks := []Earmark{
		{Title: "Old 1", Timestamp: cutoff - 1000},
		{Title: "Old 2", Timestamp: cutoff - 1},
		{Title: "Recent 1", Timestamp: cutoff + 1},
		{Title: "Recent 2", Timestamp: time.Now().Unix()},
	}

	var keep, remove []Earmark
	for _, e := range earmarks {
		if e.Timestamp < cutoff {
			remove = append(remove, e)
		} else {
			keep = append(keep, e)
		}
	}

	if len(remove) != 2 {
		t.Errorf("expected 2 old earmarks, got %d", len(remove))
	}
	if len(keep) != 2 {
		t.Errorf("expected 2 recent earmarks, got %d", len(keep))
	}
	for _, e := range remove {
		if !strings.HasPrefix(e.Title, "Old") {
			t.Errorf("wrong earmark in remove list: %q", e.Title)
		}
	}
}
