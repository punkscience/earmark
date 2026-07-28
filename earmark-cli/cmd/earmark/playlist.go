package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// parsePlaylist reads a PLS or M3U playlist and returns absolute paths to
// each referenced file. Lines that reference non-existent files are skipped;
// directories are skipped. Returns an error if the playlist is empty after
// resolving all entries.
func parsePlaylist(path string) ([]string, error) {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".m3u", ".m3u8":
		return parseM3U(path)
	case ".pls":
		return parsePLS(path)
	default:
		return nil, fmt.Errorf("unsupported playlist format: %s (expected .m3u, .m3u8, or .pls)", ext)
	}
}

func parseM3U(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("could not open playlist: %w", err)
	}
	defer f.Close()

	base := filepath.Dir(path)
	var tracks []string

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		resolved := resolvePath(base, line)
		if resolved == "" {
			continue
		}
		tracks = append(tracks, resolved)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("could not scan playlist: %w", err)
	}
	if len(tracks) == 0 {
		return nil, fmt.Errorf("no valid tracks found in playlist")
	}
	return tracks, nil
}

func parsePLS(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("could not open playlist: %w", err)
	}
	defer f.Close()

	base := filepath.Dir(path)
	var tracks []string

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// PLS File entries have the form: FileN=path
		eq := strings.Index(line, "=")
		if eq < 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		if !strings.HasPrefix(key, "file") {
			continue
		}
		val := strings.TrimSpace(line[eq+1:])
		if val == "" {
			continue
		}
		resolved := resolvePath(base, val)
		if resolved == "" {
			continue
		}
		tracks = append(tracks, resolved)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("could not scan playlist: %w", err)
	}
	if len(tracks) == 0 {
		return nil, fmt.Errorf("no valid tracks found in playlist")
	}
	return tracks, nil
}

// resolvePath resolves a track reference relative to the playlist's directory.
// Returns the absolute path, or "" if the file does not exist or is a directory.
func resolvePath(base, ref string) string {
	p := ref
	if !filepath.IsAbs(p) {
		p = filepath.Join(base, p)
	}
	info, err := os.Stat(p)
	if err != nil || info.IsDir() {
		return ""
	}
	return p
}
