package earmark

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// TestPurgeSkipsPinnedChunks is the guarantee that makes channel sharing safe:
// purging your own expired earmark must not delete chunks you lent to a channel
// inside the last 30 days, or the recipients' copies die with yours.
func TestPurgeSkipsPinnedChunks(t *testing.T) {
	var mu sync.Mutex
	var deleted []string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			mu.Lock()
			deleted = append(deleted, strings.TrimPrefix(r.URL.Path, "/"))
			mu.Unlock()
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	shared := strings.Repeat("a", 64)
	private := strings.Repeat("b", 64)

	manifest := &BlossomManifest{
		Key: base64.StdEncoding.EncodeToString(make([]byte, 32)),
		Chunks: []BlossomChunk{
			{Index: 0, SHA256: shared, Servers: []string{srv.URL}},
			{Index: 1, SHA256: private, Servers: []string{srv.URL}},
		},
	}

	st := &ChannelState{V: 1, Pins: []ChannelPin{
		{Chan: "chan1", Chunks: []string{shared}, PostedAt: time.Now().Unix()},
	}}

	DeleteManifestChunksExcept(context.Background(), nostr.GeneratePrivateKey(),
		manifest, st.PinnedChunks(time.Now()))

	mu.Lock()
	defer mu.Unlock()
	for _, h := range deleted {
		if h == shared {
			t.Error("a chunk pinned by a live channel post was deleted")
		}
	}
	if len(deleted) != 1 || deleted[0] != private {
		t.Errorf("deleted = %v, want only the unpinned chunk", deleted)
	}
}

// TestPurgeDeletesOnceThePinExpires verifies the protection is a window, not a
// permanent exemption — a pin older than the post lifetime stops shielding.
func TestPurgeDeletesOnceThePinExpires(t *testing.T) {
	var mu sync.Mutex
	var deleted []string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			mu.Lock()
			deleted = append(deleted, strings.TrimPrefix(r.URL.Path, "/"))
			mu.Unlock()
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	shared := strings.Repeat("a", 64)
	manifest := &BlossomManifest{
		Key:    base64.StdEncoding.EncodeToString(make([]byte, 32)),
		Chunks: []BlossomChunk{{Index: 0, SHA256: shared, Servers: []string{srv.URL}}},
	}
	st := &ChannelState{V: 1, Pins: []ChannelPin{
		{Chan: "chan1", Chunks: []string{shared},
			PostedAt: time.Now().Add(-40 * 24 * time.Hour).Unix()},
	}}

	DeleteManifestChunksExcept(context.Background(), nostr.GeneratePrivateKey(),
		manifest, st.PinnedChunks(time.Now()))

	mu.Lock()
	defer mu.Unlock()
	if len(deleted) != 1 {
		t.Errorf("deleted = %v, want the chunk to be deleted once its pin expired", deleted)
	}
}

// TestMirrorChunkPrefersServerSideCopy verifies Keep uses BUD-04 /mirror rather
// than pulling the audio through the client. On a phone that difference is the
// feature.
func TestMirrorChunkPrefersServerSideCopy(t *testing.T) {
	var mu sync.Mutex
	var mirrored int
	var downloaded int

	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		downloaded++
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer source.Close()

	dest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPut && r.URL.Path == "/mirror" {
			mu.Lock()
			mirrored++
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer dest.Close()

	chunk := BlossomChunk{Index: 0, SHA256: strings.Repeat("a", 64), Servers: []string{source.URL}}
	hosted, err := MirrorChunk(context.Background(), nostr.GeneratePrivateKey(),
		chunk, []string{dest.URL})
	if err != nil {
		t.Fatalf("MirrorChunk: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if mirrored != 1 {
		t.Errorf("mirror calls = %d, want 1", mirrored)
	}
	if downloaded != 0 {
		t.Errorf("the client downloaded %d chunk(s) — mirroring should move no bytes through us", downloaded)
	}
	if len(hosted) != 1 || hosted[0] != dest.URL {
		t.Errorf("hosted = %v, want [%s]", hosted, dest.URL)
	}
}

// TestMirrorChunkFallsBackToUpload verifies that a server without /mirror still
// gets the chunk, by way of a download and re-upload.
func TestMirrorChunkFallsBackToUpload(t *testing.T) {
	payload := []byte("encrypted chunk bytes")
	sum := sha256Hex(payload)

	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(payload)
	}))
	defer source.Close()

	var mu sync.Mutex
	var uploaded bool
	dest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/mirror":
			w.WriteHeader(http.StatusNotFound) // no BUD-04 support
		case r.URL.Path == "/upload" && r.Method == http.MethodPut:
			mu.Lock()
			uploaded = true
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer dest.Close()

	chunk := BlossomChunk{Index: 0, SHA256: sum, Servers: []string{source.URL}}
	hosted, err := MirrorChunk(context.Background(), nostr.GeneratePrivateKey(),
		chunk, []string{dest.URL})
	if err != nil {
		t.Fatalf("MirrorChunk: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if !uploaded {
		t.Error("no upload happened after /mirror was refused")
	}
	if len(hosted) != 1 {
		t.Errorf("hosted = %v, want the destination server", hosted)
	}
}

// TestMirrorChunkNoSource verifies a chunk with nowhere to copy from is an
// error rather than a silent success.
func TestMirrorChunkNoSource(t *testing.T) {
	chunk := BlossomChunk{Index: 0, SHA256: strings.Repeat("a", 64)}
	if _, err := MirrorChunk(context.Background(), nostr.GeneratePrivateKey(),
		chunk, []string{"https://example.invalid"}); err == nil {
		t.Error("expected an error for a chunk with no source server")
	}
}
