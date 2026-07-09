package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"ca.punkscience.earmark/internal/filter"
)

var audioExts = map[string]bool{
	".mp3": true, ".wav": true, ".flac": true,
	".ogg": true, ".m4a": true, ".aac": true,
}

// scanMusicDir recursively scans root for audio files. If expr is non-nil,
// only paths matching the keyword expression are returned.
func scanMusicDir(root string, expr filter.Expr) ([]string, error) {
	var all []string
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		if audioExts[strings.ToLower(filepath.Ext(path))] {
			all = append(all, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if expr == nil {
		return all, nil
	}
	result := filter.FilterExpr(all, expr)
	if len(result) == 0 {
		return nil, fmt.Errorf("no audio files matched the keyword expression")
	}
	return result, nil
}