package earmark

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
)

// Moved here from derpy's nostr_test.go when the protocol code was extracted
// into this module. The behaviour they cover — key resolution and relay-list
// merging — belongs to whichever module owns the code, not to the host app.

// TestResolvePrivateKey verifies that resolvePrivateKey accepts both raw hex
// and bech32-encoded nsec keys and normalises them to hex.
func TestResolvePrivateKey(t *testing.T) {
	// Generate a fresh key for the round-trip test.
	hexKey := nostr.GeneratePrivateKey()

	// Encode it to nsec so we can test bech32 decoding.
	nsecKey, err := nip19.EncodePrivateKey(hexKey)
	if err != nil {
		t.Fatalf("could not encode test key to nsec: %v", err)
	}

	tests := []struct {
		name    string
		input   string
		wantHex string // expected result (empty means we just check no error)
		wantErr bool
	}{
		{
			name:    "raw hex key returned unchanged",
			input:   hexKey,
			wantHex: hexKey,
		},
		{
			name:    "nsec key decoded to hex",
			input:   nsecKey,
			wantHex: hexKey,
		},
		{
			name:    "hex key with surrounding whitespace",
			input:   "  " + hexKey + "\n",
			wantHex: hexKey,
		},
		{
			name:    "invalid nsec key",
			input:   "nsec1notvalid",
			wantErr: true,
		},
		{
			name:    "wrong bech32 prefix (npub not nsec)",
			input:   "npub1" + strings.Repeat("q", 58), // a structurally plausible but wrong prefix
			wantErr: false,                             // treated as raw hex; go-nostr validates on sign
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ResolvePrivateKey(tt.input)
			if tt.wantErr {
				if err == nil {
					t.Errorf("expected error, got nil (result: %q)", got)
				}
				return
			}
			if err != nil {
				t.Errorf("unexpected error: %v", err)
				return
			}
			if tt.wantHex != "" && got != tt.wantHex {
				t.Errorf("got %q, want %q", got, tt.wantHex)
			}
		})
	}
}

// TestNpubFromPrivateKey verifies that npubFromPrivateKey derives a valid
// bech32-encoded public key from a raw hex private key.
func TestNpubFromPrivateKey(t *testing.T) {
	hexKey := nostr.GeneratePrivateKey()

	npub, err := NpubFromPrivateKey(hexKey)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.HasPrefix(npub, "npub1") {
		t.Errorf("expected npub to start with 'npub1', got %q", npub)
	}

	// Verify round-trip: decode npub back to public hex and compare with
	// what go-nostr derives directly from the private key.
	prefix, val, err := nip19.Decode(npub)
	if err != nil {
		t.Fatalf("could not decode npub: %v", err)
	}
	if prefix != "npub" {
		t.Errorf("expected prefix 'npub', got %q", prefix)
	}
	pubHex, ok := val.(string)
	if !ok {
		t.Fatal("decoded value is not a string")
	}

	wantPub, err := nostr.GetPublicKey(hexKey)
	if err != nil {
		t.Fatalf("could not derive public key: %v", err)
	}
	if pubHex != wantPub {
		t.Errorf("npub round-trip mismatch: got %q, want %q", pubHex, wantPub)
	}
}

// TestNpubFromPrivateKey_Invalid checks that an empty/invalid key returns an error.
func TestNpubFromPrivateKey_Invalid(t *testing.T) {
	_, err := NpubFromPrivateKey("not-a-valid-hex-key")
	if err == nil {
		t.Error("expected error for invalid key, got nil")
	}
}

// TestUnionStrings verifies deduplication and order preservation.
func TestUnionStrings(t *testing.T) {
	a := []string{"wss://a.com", "wss://b.com"}
	b := []string{"wss://b.com", "wss://c.com"}
	got := UnionStrings(a, b)
	want := []string{"wss://a.com", "wss://b.com", "wss://c.com"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("[%d] got %q, want %q", i, got[i], want[i])
		}
	}
}

func TestUnionStrings_Empty(t *testing.T) {
	if got := UnionStrings(nil, nil); len(got) != 0 {
		t.Errorf("expected empty, got %v", got)
	}
	a := []string{"wss://a.com"}
	if got := UnionStrings(nil, a); len(got) != 1 || got[0] != a[0] {
		t.Errorf("got %v", got)
	}
	if got := UnionStrings(a, nil); len(got) != 1 || got[0] != a[0] {
		t.Errorf("got %v", got)
	}
}
