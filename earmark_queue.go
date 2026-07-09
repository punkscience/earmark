package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// queueFilePath returns the path to the offline earmark queue file.
func queueFilePath() (string, error) {
	dir, err := configDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "queue.json"), nil
}

// LoadQueue reads the offline earmark queue from disk.
func LoadQueue() ([]Earmark, error) {
	path, err := queueFilePath()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return []Earmark{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("could not read queue file: %w", err)
	}
	var earmarks []Earmark
	if err := json.Unmarshal(data, &earmarks); err != nil {
		return nil, fmt.Errorf("could not parse queue file: %w", err)
	}
	return earmarks, nil
}

func saveQueue(earmarks []Earmark) error {
	path, err := queueFilePath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return fmt.Errorf("could not create config directory: %w", err)
	}
	data, err := json.Marshal(earmarks)
	if err != nil {
		return fmt.Errorf("could not marshal queue: %w", err)
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "queue-*.json.tmp")
	if err != nil {
		return fmt.Errorf("could not create temp file: %w", err)
	}
	tmpPath := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpPath)
		return fmt.Errorf("could not write temp file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("could not close temp file: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("could not rename temp file: %w", err)
	}
	return nil
}

// FlushQueue publishes all queued earmarks and removes successful ones.
func FlushQueue(hexPrivKey string) (int, error) {
	queue, err := LoadQueue()
	if err != nil {
		return 0, err
	}
	if len(queue) == 0 {
		return 0, nil
	}
	flushed := 0
	for _, e := range queue {
		if err := AddEarmark(hexPrivKey, e); err == nil {
			// Best-effort remove.
			existing, _ := LoadQueue()
			filtered := existing[:0]
			for _, q := range existing {
				if q.Timestamp != e.Timestamp {
					filtered = append(filtered, q)
				}
			}
			if len(filtered) < len(existing) {
				_ = saveQueue(filtered)
			}
			flushed++
		}
	}
	return flushed, nil
}