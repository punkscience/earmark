package earmark

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

// --- Encryption unit tests -----------------------------------------------

// TestEncryptDecryptChunk verifies that encryptChunk → decryptChunk is a
// lossless round-trip for arbitrary payloads.
func TestEncryptDecryptChunk(t *testing.T) {
	key, err := generateEncryptionKey()
	if err != nil {
		t.Fatalf("generateEncryptionKey: %v", err)
	}

	cases := [][]byte{
		[]byte("hello world"),
		make([]byte, 0),                   // empty
		make([]byte, 1),                   // single byte
		bytes.Repeat([]byte{0xAB}, 65536), // 64 KiB of repeated bytes
	}

	for _, plain := range cases {
		enc, err := encryptChunk(plain, key)
		if err != nil {
			t.Fatalf("encryptChunk: %v", err)
		}
		if bytes.Equal(enc, plain) && len(plain) > 0 {
			t.Error("ciphertext is identical to plaintext")
		}

		got, err := decryptChunk(enc, key)
		if err != nil {
			t.Fatalf("decryptChunk: %v", err)
		}
		if !bytes.Equal(got, plain) {
			t.Errorf("round-trip mismatch (len %d)", len(plain))
		}
	}
}

// TestDecryptChunkWrongKey verifies that decryption with a different key fails
// with an error (GCM authentication tag mismatch).
func TestDecryptChunkWrongKey(t *testing.T) {
	key1, _ := generateEncryptionKey()
	key2, _ := generateEncryptionKey()

	enc, err := encryptChunk([]byte("secret audio data"), key1)
	if err != nil {
		t.Fatalf("encryptChunk: %v", err)
	}
	if _, err := decryptChunk(enc, key2); err == nil {
		t.Error("expected decryption with wrong key to fail, got nil")
	}
}

// TestDecryptChunkTruncated verifies that a truncated ciphertext returns an error.
func TestDecryptChunkTruncated(t *testing.T) {
	key, _ := generateEncryptionKey()
	enc, _ := encryptChunk([]byte("some data"), key)

	// Feed only the nonce bytes (truncated before any ciphertext).
	if _, err := decryptChunk(enc[:4], key); err == nil {
		t.Error("expected error for truncated input, got nil")
	}
}

// TestEncryptNoncesAreUnique verifies that two encryptions of the same plaintext
// produce different ciphertexts (due to random nonces).
func TestEncryptNoncesAreUnique(t *testing.T) {
	key, _ := generateEncryptionKey()
	plain := []byte("same plaintext")

	enc1, _ := encryptChunk(plain, key)
	enc2, _ := encryptChunk(plain, key)

	if bytes.Equal(enc1, enc2) {
		t.Error("two encryptions of the same plaintext produced identical ciphertext")
	}
}

// TestGenerateEncryptionKeyLength verifies key length is always 32 bytes.
func TestGenerateEncryptionKeyLength(t *testing.T) {
	for i := 0; i < 10; i++ {
		key, err := generateEncryptionKey()
		if err != nil {
			t.Fatalf("generateEncryptionKey: %v", err)
		}
		if len(key) != 32 {
			t.Errorf("expected 32-byte key, got %d bytes", len(key))
		}
	}
}

// TestSha256Hex verifies the sha256Hex helper against a known vector.
func TestSha256Hex(t *testing.T) {
	// SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
	got := sha256Hex([]byte{})
	want := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if got != want {
		t.Errorf("sha256Hex(\"\") = %q, want %q", got, want)
	}
}

// --- Upload / download integration tests (httptest servers) --------------

// blossomTestServer starts a minimal Blossom-compatible HTTP server backed by
// an in-memory map. Returns the server and a cleanup function.
func blossomTestServer(t *testing.T) (*httptest.Server, func()) {
	t.Helper()
	store := map[string][]byte{}
	var mu sync.Mutex // protect concurrent uploads in parallel tests

	mux := http.NewServeMux()

	// PUT /upload — store blob, verify SHA-256 matches content
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		data, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read error", http.StatusInternalServerError)
			return
		}
		sum := sha256Hex(data)
		mu.Lock()
		store[sum] = data
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"url":"%s/%s","sha256":"%s","size":%d}`,
			r.Host, sum, sum, len(data))
	})

	// GET /<sha256> — retrieve blob
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		sum := strings.TrimPrefix(r.URL.Path, "/")
		mu.Lock()
		data, ok := store[sum]
		mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write(data)
	})

	srv := httptest.NewServer(mux)
	return srv, srv.Close
}

// TestUploadDownloadChunk verifies a single chunk upload → download cycle
// against a real (httptest) Blossom server.
func TestUploadDownloadChunk(t *testing.T) {
	srv, cleanup := blossomTestServer(t)
	defer cleanup()

	privKey := nostr.GeneratePrivateKey()
	key, _ := generateEncryptionKey()
	plain := []byte("this is audio chunk data")

	encrypted, err := encryptChunk(plain, key)
	if err != nil {
		t.Fatalf("encryptChunk: %v", err)
	}
	sum := sha256Hex(encrypted)

	// Upload
	if err := uploadChunk(context.Background(), srv.URL, encrypted, sum, privKey, nil, time.Minute); err != nil {
		t.Fatalf("uploadChunk: %v", err)
	}

	// Download and verify
	got, err := downloadChunk(context.Background(), srv.URL, sum, privKey)
	if err != nil {
		t.Fatalf("downloadChunk: %v", err)
	}
	if !bytes.Equal(got, encrypted) {
		t.Error("downloaded data does not match uploaded data")
	}

	// Decrypt and verify plaintext
	decrypted, err := decryptChunk(got, key)
	if err != nil {
		t.Fatalf("decryptChunk: %v", err)
	}
	if !bytes.Equal(decrypted, plain) {
		t.Errorf("decrypted content mismatch: got %q, want %q", decrypted, plain)
	}
}

// TestUploadFileAndReassemble tests the full PrepareUpload → UploadPrepared →
// DownloadAndReassemble pipeline against a real httptest Blossom server, using
// a file larger than one chunk to exercise the splitting logic.
func TestUploadFileAndReassemble(t *testing.T) {
	srv, cleanup := blossomTestServer(t)
	defer cleanup()

	privKey := nostr.GeneratePrivateKey()

	// Create a test file that spans 2.5 chunks (each chunk is blossomChunkSize bytes).
	fileSize := int(float64(blossomChunkSize) * 2.5)
	fileData := make([]byte, fileSize)
	for i := range fileData {
		fileData[i] = byte(i % 251)
	}

	// Write to a temp file.
	tmp, err := os.CreateTemp(t.TempDir(), "blossom-test-*.bin")
	if err != nil {
		t.Fatalf("CreateTemp: %v", err)
	}
	if _, err := tmp.Write(fileData); err != nil {
		t.Fatalf("Write: %v", err)
	}
	tmp.Close()

	// Step 1: encrypt locally — SHA-256s known before any network I/O.
	prepared, manifest, err := PrepareUpload(tmp.Name())
	if err != nil {
		t.Fatalf("PrepareUpload: %v", err)
	}

	// 2.5 chunks → 3 chunks
	if len(manifest.Chunks) != 3 {
		t.Errorf("expected 3 chunks, got %d", len(manifest.Chunks))
	}
	// All SHA-256s must be populated before upload.
	for i, c := range manifest.Chunks {
		if c.SHA256 == "" {
			t.Errorf("chunk %d has empty SHA-256 before upload", i)
		}
		if len(c.Servers) != 0 {
			t.Errorf("chunk %d has servers set before upload (should be empty)", i)
		}
	}

	// Step 2: upload pre-encrypted chunks.
	servers := []string{srv.URL}
	// Progress reports cumulative encrypted bytes, fired as the request body is
	// consumed — so the call count tracks socket writes, not chunks. Assert the
	// contract that matters: monotonic, and finishing exactly at the total.
	var progressCalls []int64
	var progressTotal int64
	if err := UploadPrepared(context.Background(), privKey, prepared, manifest, servers,
		func(done, total int64) {
			progressCalls = append(progressCalls, done)
			progressTotal = total
		}); err != nil {
		t.Fatalf("UploadPrepared: %v", err)
	}

	if len(progressCalls) == 0 {
		t.Fatal("expected at least one progress callback, got none")
	}
	for i := 1; i < len(progressCalls); i++ {
		if progressCalls[i] < progressCalls[i-1] {
			t.Errorf("progress went backwards at call %d: %d then %d",
				i, progressCalls[i-1], progressCalls[i])
		}
	}
	var wantTotal int64
	for _, c := range prepared {
		wantTotal += int64(len(c.Data))
	}
	if progressTotal != wantTotal {
		t.Errorf("progress total = %d, want %d", progressTotal, wantTotal)
	}
	if last := progressCalls[len(progressCalls)-1]; last != wantTotal {
		t.Errorf("final progress = %d, want %d", last, wantTotal)
	}
	// Servers must be populated after upload.
	for i, c := range manifest.Chunks {
		if len(c.Servers) == 0 {
			t.Errorf("chunk %d has no servers after upload", i)
		}
	}

	// Verify the manifest key decodes to 32 bytes.
	keyBytes, err := base64.StdEncoding.DecodeString(manifest.Key)
	if err != nil || len(keyBytes) != 32 {
		t.Errorf("manifest key invalid: err=%v len=%d", err, len(keyBytes))
	}

	// Reassemble.
	destPath, err := DownloadAndReassemble(context.Background(), manifest, privKey, nil)
	if err != nil {
		t.Fatalf("DownloadAndReassemble: %v", err)
	}
	defer os.Remove(destPath)

	reassembled, err := os.ReadFile(destPath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if !bytes.Equal(reassembled, fileData) {
		t.Errorf("reassembled content does not match original (len %d vs %d)",
			len(reassembled), len(fileData))
	}
}

// TestDownloadChunkSHA256Mismatch verifies that downloadChunk rejects a
// response whose content doesn't match the expected SHA-256.
func TestDownloadChunkSHA256Mismatch(t *testing.T) {
	// Server returns garbage regardless of SHA-256 requested.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("not what you asked for"))
	}))
	defer srv.Close()

	privKey := nostr.GeneratePrivateKey()
	_, err := downloadChunk(context.Background(), srv.URL, "aaaa"+strings.Repeat("0", 60), privKey)
	if err == nil {
		t.Error("expected SHA-256 mismatch error, got nil")
	}
	if !strings.Contains(err.Error(), "mismatch") {
		t.Errorf("unexpected error message: %v", err)
	}
}

// TestUploadChunkToServers_PartialFailure verifies that uploadChunkToServers
// succeeds even when one of the target servers is down, and only returns the
// servers that accepted the upload.
func TestUploadChunkToServers_PartialFailure(t *testing.T) {
	goodSrv, cleanup := blossomTestServer(t)
	defer cleanup()

	privKey := nostr.GeneratePrivateKey()
	key, _ := generateEncryptionKey()
	enc, _ := encryptChunk([]byte("audio"), key)
	sum := sha256Hex(enc)

	// One working server, one dead server.
	servers := []string{"http://127.0.0.1:1", goodSrv.URL}
	accepted, err := uploadChunkToServers(context.Background(), servers, enc, sum, privKey, nil, time.Minute)
	if err != nil {
		t.Fatalf("expected partial success, got error: %v", err)
	}
	if len(accepted) != 1 || accepted[0] != goodSrv.URL {
		t.Errorf("expected only good server in accepted list, got %v", accepted)
	}
}

// TestBlossomAuthToken verifies that blossomAuthToken returns a non-empty
// base64 string that decodes to a valid JSON Nostr event.
func TestBlossomAuthToken(t *testing.T) {
	privKey := nostr.GeneratePrivateKey()
	token, err := blossomAuthToken(privKey, strings.Repeat("a", 64), "upload")
	if err != nil {
		t.Fatalf("blossomAuthToken: %v", err)
	}
	if token == "" {
		t.Fatal("expected non-empty token")
	}

	decoded, err := base64.StdEncoding.DecodeString(token)
	if err != nil {
		t.Fatalf("token is not valid base64: %v", err)
	}

	var ev map[string]interface{}
	if err := json.Unmarshal(decoded, &ev); err != nil {
		t.Fatalf("decoded token is not valid JSON: %v", err)
	}
	if ev["kind"].(float64) != blossomAuthKind {
		t.Errorf("expected kind %d, got %v", blossomAuthKind, ev["kind"])
	}
}

// TestManifestKeyRoundTrip verifies the base64 key in a manifest survives
// encode → store → decode without corruption.
func TestManifestKeyRoundTrip(t *testing.T) {
	key, _ := generateEncryptionKey()
	encoded := base64.StdEncoding.EncodeToString(key)
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !bytes.Equal(decoded, key) {
		t.Error("key round-trip mismatch")
	}
}

// TestPrepareUploadNotFound verifies PrepareUpload returns an error for a missing file.
func TestPrepareUploadNotFound(t *testing.T) {
	_, _, err := PrepareUpload(filepath.Join(t.TempDir(), "nonexistent.flac"))
	if err == nil {
		t.Error("expected error for missing file, got nil")
	}
}

// TestDeleteChunk verifies that deleteChunk issues a DELETE request with an
// Authorization header and treats a 404 as success.
func TestDeleteChunk(t *testing.T) {
	var receivedAuth string
	var receivedMethod string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedMethod = r.Method
		receivedAuth = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	privKey := nostr.GeneratePrivateKey()
	sha256hex := strings.Repeat("a", 64)

	if err := deleteChunk(context.Background(), srv.URL, sha256hex, privKey); err != nil {
		t.Fatalf("deleteChunk: %v", err)
	}
	if receivedMethod != http.MethodDelete {
		t.Errorf("expected DELETE, got %s", receivedMethod)
	}
	if !strings.HasPrefix(receivedAuth, "Nostr ") {
		t.Errorf("expected 'Nostr <token>' Authorization header, got %q", receivedAuth)
	}
}

// TestDeleteChunk404 verifies that a 404 response is treated as success
// (idempotent — the chunk is already gone).
func TestDeleteChunk404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	}))
	defer srv.Close()

	privKey := nostr.GeneratePrivateKey()
	if err := deleteChunk(context.Background(), srv.URL, strings.Repeat("b", 64), privKey); err != nil {
		t.Errorf("expected 404 to be treated as success, got: %v", err)
	}
}

// TestDeleteManifestChunks verifies that all chunks across all servers receive
// DELETE requests in parallel.
func TestDeleteManifestChunks(t *testing.T) {
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

	privKey := nostr.GeneratePrivateKey()
	manifest := &BlossomManifest{
		Key: base64.StdEncoding.EncodeToString(make([]byte, 32)),
		Chunks: []BlossomChunk{
			{Index: 0, SHA256: strings.Repeat("a", 64), Servers: []string{srv.URL}},
			{Index: 1, SHA256: strings.Repeat("b", 64), Servers: []string{srv.URL}},
			{Index: 2, SHA256: strings.Repeat("c", 64), Servers: []string{srv.URL}},
		},
	}

	DeleteManifestChunks(context.Background(), privKey, manifest)

	if len(deleted) != 3 {
		t.Errorf("expected 3 DELETE calls, got %d: %v", len(deleted), deleted)
	}
}

// TestDeleteManifestChunks_Nil verifies that a nil manifest is a no-op.
func TestDeleteManifestChunks_Nil(t *testing.T) {
	// Should not panic.
	DeleteManifestChunks(context.Background(), nostr.GeneratePrivateKey(), nil)
}

// TestDeleteManifestChunks_EmptyServers verifies that chunks with no servers
// (queued while offline, never uploaded) are silently skipped.
func TestDeleteManifestChunks_EmptyServers(t *testing.T) {
	manifest := &BlossomManifest{
		Key: base64.StdEncoding.EncodeToString(make([]byte, 32)),
		Chunks: []BlossomChunk{
			{Index: 0, SHA256: strings.Repeat("d", 64), Servers: nil},
		},
	}
	// Should not panic or error.
	DeleteManifestChunks(context.Background(), nostr.GeneratePrivateKey(), manifest)
}

// TestPrepareUploadSHA256sKnownBeforeUpload verifies the key insight: all chunk
// SHA-256s are populated by PrepareUpload before any network I/O occurs.
func TestPrepareUploadSHA256sKnownBeforeUpload(t *testing.T) {
	data := bytes.Repeat([]byte{0x42}, 1024)
	tmp, err := os.CreateTemp(t.TempDir(), "*.bin")
	if err != nil {
		t.Fatalf("CreateTemp: %v", err)
	}
	tmp.Write(data)
	tmp.Close()

	prepared, manifest, err := PrepareUpload(tmp.Name())
	if err != nil {
		t.Fatalf("PrepareUpload: %v", err)
	}
	if len(prepared) != 1 || len(manifest.Chunks) != 1 {
		t.Fatalf("expected 1 chunk, got %d prepared, %d in manifest", len(prepared), len(manifest.Chunks))
	}
	if manifest.Chunks[0].SHA256 == "" {
		t.Error("SHA-256 empty after PrepareUpload — chunk identity not known yet")
	}
	if len(manifest.Chunks[0].Servers) != 0 {
		t.Error("Servers should be empty before UploadPrepared")
	}
}

// TestDownloadChunkWithFallback_NoServersUsesConfigured verifies that a chunk
// whose manifest names no servers is fetched from the configured servers
// instead of failing unasked. Earmarks published before the manifest recorded
// a server list have exactly this shape, and iterating an empty list made them
// undownloadable by every client at once.
func TestDownloadChunkWithFallback_NoServersUsesConfigured(t *testing.T) {
	srv, cleanup := blossomTestServer(t)
	defer cleanup()

	Configure(Settings{BlossomServers: []string{srv.URL}})
	defer Configure(Settings{})

	privKey := nostr.GeneratePrivateKey()
	key, _ := generateEncryptionKey()
	encrypted, err := encryptChunk([]byte("audio with no server recorded"), key)
	if err != nil {
		t.Fatalf("encryptChunk: %v", err)
	}
	sum := sha256Hex(encrypted)
	if err := uploadChunk(context.Background(), srv.URL, encrypted, sum, privKey, nil, time.Minute); err != nil {
		t.Fatalf("uploadChunk: %v", err)
	}

	chunk := BlossomChunk{Index: 0, SHA256: sum, Servers: nil}
	got, err := downloadChunkWithFallback(context.Background(), chunk, privKey)
	if err != nil {
		t.Fatalf("downloadChunkWithFallback: %v", err)
	}
	if !bytes.Equal(got, encrypted) {
		t.Error("downloaded data does not match uploaded data")
	}
}
