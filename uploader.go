package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// uploadFile encrypts and uploads a single file to Blossom, then records it
// in the Nostr earmark list.
func uploadFile(hexPrivKey string, filePath string, progress func(msg string)) error {
	progress(fmt.Sprintf("Encrypting: %s", filepath.Base(filePath)))
	chunks, manifest, err := PrepareUpload(filePath)
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}

	servers, err := ResolveBlossomServers(hexPrivKey)
	if err != nil {
		return fmt.Errorf("resolve servers: %w", err)
	}

	uploadCtx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	if err := UploadPrepared(uploadCtx, hexPrivKey, chunks, manifest, servers,
		func(done, total int) {
			progress(fmt.Sprintf("  Uploading chunks: %d/%d", done, total))
		},
	); err != nil {
		return fmt.Errorf("upload: %w", err)
	}

	progress("  Saving to Nostr...")
	e := Earmark{
		Path:      filePath,
		Timestamp: time.Now().Unix(),
		Blossom:   manifest,
	}
	return AddEarmark(hexPrivKey, e)
}

// UploadFiles encrypts and uploads multiple files to Blossom, recording each
// in the Nostr earmark list. Prints progress to stdout.
func UploadFiles(hexPrivKey string, paths []string) error {
	success := 0
	for i, p := range paths {
		fmt.Printf("[%d/%d] %s\n", i+1, len(paths), filepath.Base(p))
		if err := uploadFile(hexPrivKey, p, func(msg string) {
			fmt.Println(msg)
		}); err != nil {
			fmt.Fprintf(os.Stderr, "  FAILED: %v\n", err)
		} else {
			success++
			fmt.Println("  Done.")
		}
	}
	fmt.Printf("\n%d/%d file(s) earmarked successfully.\n", success, len(paths))
	return nil
}