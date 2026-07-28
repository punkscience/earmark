package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/dhowden/tag"
	"github.com/mattn/go-isatty"
)

// readTrackMetadata reads the artist, album, and title tags from an audio file.
// Files without readable tags fall back to the filename for the title so the
// entry is never anonymous, mirroring derpy's behaviour.
func readTrackMetadata(filePath string) (artist, album, title string) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", "", filepath.Base(filePath)
	}
	defer file.Close()

	tags, err := tag.ReadFrom(file)
	if err != nil {
		return "Unknown Artist", "Unknown Album", filepath.Base(filePath)
	}
	return tags.Artist(), tags.Album(), tags.Title()
}

// uploadPhase identifies which stage of a single-file upload is in progress.
type uploadPhase int

const (
	phaseEncrypting uploadPhase = iota
	phaseDiscovering
	phaseUploading
	phaseSaving
)

// uploadEvent is a progress update emitted during uploadFile. Byte counts are
// only meaningful during phaseUploading.
type uploadEvent struct {
	phase      uploadPhase
	bytesDone  int64
	bytesTotal int64
}

// uploadFile encrypts and uploads a single file to Blossom, then records it
// in the Nostr earmark list, emitting progress events as it goes.
func uploadFile(hexPrivKey string, filePath string, onEvent func(uploadEvent)) error {
	onEvent(uploadEvent{phase: phaseEncrypting})
	chunks, manifest, err := PrepareUpload(filePath)
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}

	onEvent(uploadEvent{phase: phaseDiscovering})
	servers, err := ResolveBlossomServers(hexPrivKey)
	if err != nil {
		return fmt.Errorf("resolve servers: %w", err)
	}

	var totalBytes int64
	for _, c := range chunks {
		totalBytes += int64(len(c.Data))
	}
	onEvent(uploadEvent{phase: phaseUploading, bytesTotal: totalBytes})

	// No overall deadline here — UploadPrepared bounds each chunk individually
	// so slow uplinks aren't penalised for time spent on earlier chunks.
	if err := UploadPrepared(context.Background(), hexPrivKey, chunks, manifest, servers,
		func(done, total int64) {
			onEvent(uploadEvent{phase: phaseUploading, bytesDone: done, bytesTotal: total})
		},
	); err != nil {
		return fmt.Errorf("upload: %w", err)
	}

	onEvent(uploadEvent{phase: phaseSaving})
	artist, album, title := readTrackMetadata(filePath)
	e := Earmark{
		Artist:    artist,
		Album:     album,
		Title:     title,
		Path:      filePath,
		Timestamp: time.Now().Unix(),
		Blossom:   manifest,
	}
	return AddEarmark(hexPrivKey, e)
}

// UploadFiles encrypts and uploads multiple files to Blossom, recording each
// in the Nostr earmark list. On an interactive terminal it shows a live
// progress TUI; otherwise it falls back to plain line-by-line output.
func UploadFiles(hexPrivKey string, paths []string) error {
	if isInteractive() {
		return uploadFilesTUI(hexPrivKey, paths)
	}
	return uploadFilesPlain(hexPrivKey, paths)
}

// isInteractive reports whether stdout is a terminal capable of driving a TUI.
func isInteractive() bool {
	fd := os.Stdout.Fd()
	return isatty.IsTerminal(fd) || isatty.IsCygwinTerminal(fd)
}

// uploadFilesPlain uploads files printing progress line-by-line to stdout.
func uploadFilesPlain(hexPrivKey string, paths []string) error {
	success := 0
	for i, p := range paths {
		fmt.Printf("[%d/%d] %s\n", i+1, len(paths), filepath.Base(p))
		lastPhase := uploadPhase(-1)
		err := uploadFile(hexPrivKey, p, func(ev uploadEvent) {
			if ev.phase == lastPhase {
				return
			}
			lastPhase = ev.phase
			switch ev.phase {
			case phaseEncrypting:
				fmt.Println("  Encrypting...")
			case phaseDiscovering:
				fmt.Println("  Discovering Blossom servers...")
			case phaseUploading:
				fmt.Println("  Uploading...")
			case phaseSaving:
				fmt.Println("  Saving to Nostr...")
			}
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "  FAILED: %v\n", err)
		} else {
			success++
			fmt.Println("  Done.")
		}
	}
	fmt.Printf("\n%d/%d file(s) earmarked successfully.\n", success, len(paths))
	return nil
}