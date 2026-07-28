package earmark

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// MirrorChunk copies a chunk onto the caller's own Blossom servers and returns
// the servers now known to hold it.
//
// It prefers BUD-04 mirroring, where the destination server fetches the blob
// itself from a URL — no bytes pass through this machine, which is what makes
// adopting a track viable on a phone or a metered connection. Servers that do
// not implement /mirror fall back to a download-verify-upload, which is correct
// but pays for the bytes twice.
func MirrorChunk(ctx context.Context, hexPrivKey string, chunk BlossomChunk, servers []string) ([]string, error) {
	if len(chunk.Servers) == 0 {
		return nil, fmt.Errorf("chunk %s has no source server", shortHash(chunk.SHA256))
	}
	sourceURL := chunk.Servers[0] + "/" + chunk.SHA256

	var hosted []string
	var needUpload []string
	for _, dest := range servers {
		if err := mirrorTo(ctx, dest, sourceURL, chunk.SHA256, hexPrivKey); err != nil {
			needUpload = append(needUpload, dest)
			continue
		}
		hosted = append(hosted, dest)
	}
	if len(needUpload) == 0 {
		return hosted, nil
	}

	// At least one destination cannot mirror, so the bytes have to come through
	// here after all. Fetch once and push to everyone that needs it.
	data, err := downloadChunkWithFallback(ctx, chunk, hexPrivKey)
	if err != nil {
		return hosted, fmt.Errorf("mirror unsupported and download failed: %w", err)
	}
	uploaded, err := uploadChunkToServers(ctx, needUpload, data, chunk.SHA256, hexPrivKey, nil, uploadIdleTimeout())
	if err != nil {
		if len(hosted) > 0 {
			// Some server took it; that is enough to keep the chunk alive.
			return hosted, nil
		}
		return nil, err
	}
	return append(hosted, uploaded...), nil
}

// mirrorTo asks one server to fetch a blob for itself (BUD-04).
func mirrorTo(ctx context.Context, serverURL, sourceURL, sha256hex, hexPrivKey string) error {
	token, err := blossomAuthToken(hexPrivKey, sha256hex, "upload")
	if err != nil {
		return err
	}
	body, err := json.Marshal(map[string]string{"url": sourceURL})
	if err != nil {
		return fmt.Errorf("could not build mirror request: %w", err)
	}
	reqCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodPut,
		serverURL+"/mirror", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("could not build mirror request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Nostr "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("mirror request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 256))
		return fmt.Errorf("server returned %d: %s", resp.StatusCode, msg)
	}
	return nil
}

func shortHash(h string) string {
	if len(h) > 8 {
		return h[:8]
	}
	return h
}
