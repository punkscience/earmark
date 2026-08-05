package earmark

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

const (
	blossomChunkSize = 16 * 1024 * 1024
	blossomAuthKind  = 24242
)

// BlossomChunk is one encrypted 16 MiB slice of a file, addressed by the
// SHA-256 of its *encrypted* bytes.
type BlossomChunk struct {
	Index   int      `json:"index"`
	SHA256  string   `json:"sha256"`
	Size    int      `json:"size"`
	Servers []string `json:"servers"`
}

type BlossomManifest struct {
	Key    string         `json:"key"`
	Ext    string         `json:"ext,omitempty"`
	Chunks []BlossomChunk `json:"chunks"`
}

type blossomBlobDescriptor struct {
	URL      string `json:"url"`
	SHA256   string `json:"sha256"`
	Size     int    `json:"size"`
	Type     string `json:"type"`
	Uploaded int64  `json:"uploaded"`
}

// generateEncryptionKey returns 32 cryptographically random bytes.
func generateEncryptionKey() ([]byte, error) {
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, fmt.Errorf("could not generate encryption key: %w", err)
	}
	return key, nil
}

func encryptChunk(data, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("could not create AES cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("could not create GCM: %w", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, fmt.Errorf("could not generate nonce: %w", err)
	}
	return gcm.Seal(nonce, nonce, data, nil), nil
}

func decryptChunk(data, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("could not create AES cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("could not create GCM: %w", err)
	}
	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return nil, fmt.Errorf("encrypted chunk too short (%d bytes)", len(data))
	}
	nonce, ciphertext := data[:nonceSize], data[nonceSize:]
	plain, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, fmt.Errorf("decryption failed (wrong key or corrupt data): %w", err)
	}
	return plain, nil
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func blossomAuthToken(hexPrivKey, sha256hex, action string) (string, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return "", fmt.Errorf("could not derive public key: %w", err)
	}
	ev := nostr.Event{
		PubKey:    pubHex,
		CreatedAt: nostr.Timestamp(time.Now().Unix()),
		Kind:      blossomAuthKind,
		Content:   fmt.Sprintf("%s %s", action, sha256hex),
		Tags: nostr.Tags{
			{"t", action},
			{"x", sha256hex},
			{"expiration", fmt.Sprintf("%d", time.Now().Add(5*time.Minute).Unix())},
		},
	}
	if err := ev.Sign(hexPrivKey); err != nil {
		return "", fmt.Errorf("could not sign auth event: %w", err)
	}
	data, err := json.Marshal(ev)
	if err != nil {
		return "", fmt.Errorf("could not marshal auth event: %w", err)
	}
	return base64.StdEncoding.EncodeToString(data), nil
}

func uploadChunk(ctx context.Context, serverURL string, data []byte, sha256hex, hexPrivKey string, onBytes func(uploaded int64), idle time.Duration) error {
	token, err := blossomAuthToken(hexPrivKey, sha256hex, "upload")
	if err != nil {
		return err
	}

	// Idle watchdog: cancel the request only after `idle` elapses with no bytes
	// sent. Every read of the request body resets the timer, so a slow-but-
	// steady upload never trips it; a stalled socket aborts within one window.
	reqCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	var stalled atomic.Bool
	timer := time.AfterFunc(idle, func() {
		stalled.Store(true)
		cancel()
	})
	defer timer.Stop()

	body := &progressReader{r: bytes.NewReader(data), cb: func(n int64) {
		timer.Reset(idle)
		if onBytes != nil {
			onBytes(n)
		}
	}}

	req, err := http.NewRequestWithContext(reqCtx, http.MethodPut,
		serverURL+"/upload", body)
	if err != nil {
		return fmt.Errorf("could not build upload request: %w", err)
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Authorization", "Nostr "+token)
	req.ContentLength = int64(len(data))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		if stalled.Load() {
			return fmt.Errorf("upload stalled: no progress for %s", idle)
		}
		return fmt.Errorf("upload request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("server returned %d: %s", resp.StatusCode, body)
	}
	return nil
}

func uploadChunkToServers(ctx context.Context, servers []string, data []byte, sha256hex, hexPrivKey string, onBytes func(uploaded int64), idle time.Duration) ([]string, error) {
	type result struct {
		server string
		err    error
	}
	// Uploads run concurrently against every server; report the furthest-along
	// server's byte count as this chunk's progress, throttled to limit chatter.
	var mu sync.Mutex
	var maxBytes, lastReported int64
	var perServer func(int64)
	if onBytes != nil {
		perServer = func(n int64) {
			mu.Lock()
			if n > maxBytes {
				maxBytes = n
			}
			m := maxBytes
			fire := m-lastReported >= 128*1024 || m >= int64(len(data))
			if fire {
				lastReported = m
			}
			mu.Unlock()
			if fire {
				onBytes(m)
			}
		}
	}
	ch := make(chan result, len(servers))
	for _, s := range servers {
		s := s
		go func() {
			ch <- result{s, uploadChunk(ctx, s, data, sha256hex, hexPrivKey, perServer, idle)}
		}()
	}
	var succeeded []string
	var lastErr error
	for range servers {
		r := <-ch
		if r.err != nil {
			lastErr = r.err
		} else {
			succeeded = append(succeeded, r.server)
		}
	}
	if len(succeeded) == 0 {
		return nil, fmt.Errorf("chunk %s: upload failed on all servers: %w", sha256hex[:8], lastErr)
	}
	return succeeded, nil
}

func deleteChunk(ctx context.Context, serverURL, sha256hex, hexPrivKey string) error {
	token, err := blossomAuthToken(hexPrivKey, sha256hex, "delete")
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete,
		serverURL+"/"+sha256hex, nil)
	if err != nil {
		return fmt.Errorf("could not build delete request: %w", err)
	}
	req.Header.Set("Authorization", "Nostr "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("delete request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 256))
		return fmt.Errorf("server returned %d: %s", resp.StatusCode, body)
	}
	return nil
}

func downloadChunk(ctx context.Context, serverURL, sha256hex, hexPrivKey string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		serverURL+"/"+sha256hex, nil)
	if err != nil {
		return nil, fmt.Errorf("could not build download request: %w", err)
	}
	// Sign a "get" authorization so servers with a pubkey allowlist (e.g. the
	// self-hosted primary) serve the blob back. Public servers ignore it.
	token, err := blossomAuthToken(hexPrivKey, sha256hex, "get")
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Nostr "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("download request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("server returned %d", resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("could not read response body: %w", err)
	}
	if got := sha256Hex(data); got != sha256hex {
		return nil, fmt.Errorf("SHA-256 mismatch: got %s, want %s", got[:8], sha256hex[:8])
	}
	return data, nil
}

// downloadChunkWithFallback fetches a chunk from the servers its manifest
// names, falling back to the configured servers when it names none.
//
// Earmarks published before the manifest recorded a server list have an empty
// Servers, which says nothing about where the blob is. Iterating it as-is asks
// nobody and reports failure, so those chunks were undownloadable by every
// client at once.
func downloadChunkWithFallback(ctx context.Context, chunk BlossomChunk, hexPrivKey string) ([]byte, error) {
	servers := chunk.Servers
	if len(servers) == 0 {
		servers = BlossomServers()
	}
	if len(servers) == 0 {
		return nil, fmt.Errorf("chunk %d: no Blossom servers to try", chunk.Index)
	}
	var lastErr error
	for _, server := range servers {
		data, err := downloadChunk(ctx, server, chunk.SHA256, hexPrivKey)
		if err == nil {
			return data, nil
		}
		lastErr = err
	}
	return nil, fmt.Errorf("chunk %d: all servers failed: %w", chunk.Index, lastErr)
}

// PreparedChunk is an encrypted chunk ready for upload.
type PreparedChunk struct {
	Index  int
	Data   []byte
	SHA256 string
}

// PrepareUpload reads filePath, splits into 16 MiB chunks, encrypts each.
func PrepareUpload(filePath string) ([]PreparedChunk, *BlossomManifest, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return nil, nil, fmt.Errorf("could not open file: %w", err)
	}
	defer f.Close()

	key, err := generateEncryptionKey()
	if err != nil {
		return nil, nil, err
	}

	var prepared []PreparedChunk
	var manifestChunks []BlossomChunk
	buf := make([]byte, blossomChunkSize)
	index := 0

	for {
		n, readErr := io.ReadFull(f, buf)
		if n == 0 {
			break
		}
		encrypted, err := encryptChunk(buf[:n], key)
		if err != nil {
			return nil, nil, fmt.Errorf("chunk %d: encryption failed: %w", index, err)
		}
		sum := sha256Hex(encrypted)
		prepared = append(prepared, PreparedChunk{Index: index, Data: encrypted, SHA256: sum})
		manifestChunks = append(manifestChunks, BlossomChunk{
			Index:  index,
			SHA256: sum,
			Size:   len(encrypted),
		})
		index++
		if readErr == io.EOF || readErr == io.ErrUnexpectedEOF {
			break
		}
		if readErr != nil {
			return nil, nil, fmt.Errorf("could not read file: %w", readErr)
		}
	}

	manifest := &BlossomManifest{
		Key:    base64.StdEncoding.EncodeToString(key),
		Ext:    filepath.Ext(filePath),
		Chunks: manifestChunks,
	}
	return prepared, manifest, nil
}

// UploadProgress reports cumulative encrypted bytes uploaded for the file.
type UploadProgress func(bytesUploaded, bytesTotal int64)

// UploadPrepared uploads pre-encrypted chunks to all servers concurrently.
// Each server request has an idle watchdog: it is cancelled only after the
// upload makes no progress for the resolved idle timeout, so slow-but-steady
// uploads run to completion while stalled connections fail fast.
func UploadPrepared(ctx context.Context, hexPrivKey string, chunks []PreparedChunk, manifest *BlossomManifest, servers []string, progress UploadProgress) error {
	idle := uploadIdleTimeout()
	var totalBytes int64
	for _, c := range chunks {
		totalBytes += int64(len(c.Data))
	}
	var completed int64
	for _, c := range chunks {
		chunkLen := int64(len(c.Data))
		var onBytes func(int64)
		if progress != nil {
			onBytes = func(cur int64) {
				progress(completed+cur, totalBytes)
			}
		}
		accepted, err := uploadChunkToServers(ctx, servers, c.Data, c.SHA256, hexPrivKey, onBytes, idle)
		if err != nil {
			return err
		}
		manifest.Chunks[c.Index].Servers = accepted
		completed += chunkLen
		if progress != nil {
			progress(completed, totalBytes)
		}
	}
	return nil
}

// progressReader wraps an io.Reader and reports cumulative bytes read.
type progressReader struct {
	r  io.Reader
	n  int64
	cb func(int64)
}

func (pr *progressReader) Read(p []byte) (int, error) {
	n, err := pr.r.Read(p)
	if n > 0 {
		pr.n += int64(n)
		if pr.cb != nil {
			pr.cb(pr.n)
		}
	}
	return n, err
}

// DownloadProgress is called after each chunk download.
type DownloadProgress func(chunksDownloaded, chunksTotal int)

// DownloadAndReassemble fetches all chunks, decrypts, and reassembles to a temp file.
func DownloadAndReassemble(ctx context.Context, manifest *BlossomManifest, hexPrivKey string, progress DownloadProgress) (string, error) {
	key, err := base64.StdEncoding.DecodeString(manifest.Key)
	if err != nil {
		return "", fmt.Errorf("could not decode encryption key: %w", err)
	}

	total := len(manifest.Chunks)
	plainChunks := make([][]byte, total)
	errs := make([]error, total)
	var mu sync.Mutex
	var wg sync.WaitGroup
	downloaded := 0

	for _, chunk := range manifest.Chunks {
		chunk := chunk
		wg.Add(1)
		go func() {
			defer wg.Done()
			data, err := downloadChunkWithFallback(ctx, chunk, hexPrivKey)
			if err != nil {
				errs[chunk.Index] = err
				return
			}
			plain, err := decryptChunk(data, key)
			if err != nil {
				errs[chunk.Index] = fmt.Errorf("chunk %d: %w", chunk.Index, err)
				return
			}
			plainChunks[chunk.Index] = plain
			mu.Lock()
			downloaded++
			if progress != nil {
				progress(downloaded, total)
			}
			mu.Unlock()
		}()
	}
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			return "", fmt.Errorf("could not retrieve chunk %d: %w", i, err)
		}
	}

	ext := manifest.Ext
	if ext == "" {
		ext = ".audio"
	}
	tmp, err := os.CreateTemp("", "earmark-*"+ext)
	if err != nil {
		return "", fmt.Errorf("could not create temp file: %w", err)
	}
	defer tmp.Close()

	for _, chunk := range plainChunks {
		if _, err := tmp.Write(chunk); err != nil {
			os.Remove(tmp.Name())
			return "", fmt.Errorf("could not write temp file: %w", err)
		}
	}
	return tmp.Name(), nil
}

// DeleteManifestChunks deletes all chunks in the manifest from all servers.
func DeleteManifestChunks(ctx context.Context, hexPrivKey string, manifest *BlossomManifest) {
	DeleteManifestChunksExcept(ctx, hexPrivKey, manifest, nil)
}

// DeleteManifestChunksExcept deletes a manifest's chunks, skipping any hash in
// pinned.
//
// Pins exist because a channel post hands recipients the hashes of chunks this
// user is hosting. Purging your own earmark inside the post's 30-day window
// would delete the audio out from under them, so the purge has to know what it
// has lent out. See ChannelState.PinnedChunks.
func DeleteManifestChunksExcept(ctx context.Context, hexPrivKey string, manifest *BlossomManifest, pinned map[string]struct{}) {
	if manifest == nil {
		return
	}
	var wg sync.WaitGroup
	for _, chunk := range manifest.Chunks {
		if _, held := pinned[chunk.SHA256]; held {
			continue
		}
		for _, server := range chunk.Servers {
			chunk, server := chunk, server
			wg.Add(1)
			go func() {
				defer wg.Done()
				_ = deleteChunk(ctx, server, chunk.SHA256, hexPrivKey)
			}()
		}
	}
	wg.Wait()
}

// ResolveBlossomServers returns kind-10063 servers unioned with the configured list.
func ResolveBlossomServers(hexPrivKey string) ([]string, error) {
	pubHex, err := nostr.GetPublicKey(hexPrivKey)
	if err != nil {
		return nil, fmt.Errorf("could not derive public key: %w", err)
	}
	discoverCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	nip10063 := fetchBlossomServers(discoverCtx, pubHex)
	cancel()
	return unionStrings(nip10063, BlossomServers()), nil
}

func fetchBlossomServers(ctx context.Context, pubHex string) []string {
	filter := nostr.Filter{
		Kinds:   []int{10063},
		Authors: []string{pubHex},
		Limit:   1,
	}
	ev := QueryRelays(ctx, Relays(), filter)
	if ev == nil {
		return nil
	}
	var servers []string
	for _, tag := range ev.Tags {
		if len(tag) >= 2 && tag[0] == "server" {
			servers = append(servers, tag[1])
		}
	}
	return servers
}
