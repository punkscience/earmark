package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// configDir returns the path to earmark's config directory (~/.config/earmark).
func configDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "earmark"), nil
}

// configFilePath returns the path to the config file.
func configFilePath() (string, error) {
	dir, err := configDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "config.json"), nil
}

// Config holds all persisted earmark configuration.
type Config struct {
	// NostrPrivateKey stores the user's Nostr private key (raw hex).
	// The file is written with 0600 permissions; treat this value as a secret.
	NostrPrivateKey string `json:"nostr_private_key,omitempty"`
	// NostrRelays is the list of relay WebSocket URLs earmark publishes to
	// and fetches from. When empty, default relays are used.
	NostrRelays []string `json:"nostr_relays,omitempty"`
	// BlossomServers is the list of Blossom server base URLs used for audio
	// file uploads and downloads. When empty, default servers are used.
	BlossomServers []string `json:"blossom_servers,omitempty"`
}

// LoadConfig reads the config file; returns an empty Config if the file does not exist.
func LoadConfig() (*Config, error) {
	path, err := configFilePath()
	if err != nil {
		return &Config{}, err
	}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return &Config{}, nil
	}
	if err != nil {
		return &Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return &Config{}, err
	}
	return &cfg, nil
}

// SaveConfig writes the config to disk, creating directories as needed.
func SaveConfig(cfg *Config) error {
	dir, err := configDir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	path := filepath.Join(dir, "config.json")
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
