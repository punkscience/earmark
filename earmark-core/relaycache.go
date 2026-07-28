package earmark

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// relayListTTL is how long a cached relay-list lookup stays fresh.
const relayListTTL = 15 * time.Minute

// relayListLookupTimeout bounds a relay-list lookup. These are optimisations,
// not requirements — the configured relays always work as a fallback — so a
// lookup must never be the reason a command feels slow.
const relayListLookupTimeout = 5 * time.Second

// relayListCache memoises kind-10002 (outbox) and kind-10050 (inbox) relay
// lists, in memory and, when the host has offered a CacheDir, on disk.
//
// The disk half is not an optimisation nicety: a CLI process exits before its
// in-memory cache can ever be read back, so without it every single command
// repays the lookup.
type relayListEntry struct {
	Relays    []string  `json:"relays"`
	FetchedAt time.Time `json:"fetched_at"`
}

var relayListMem = struct {
	sync.Mutex
	entries map[string]relayListEntry
}{entries: map[string]relayListEntry{}}

func relayListKey(kind int, pubHex string) string {
	return fmt.Sprintf("%d:%s", kind, pubHex)
}

func relayListCachePath() string {
	dir := CacheDir()
	if dir == "" {
		return ""
	}
	return filepath.Join(dir, "relay-lists.json")
}

func readRelayListDisk() map[string]relayListEntry {
	path := relayListCachePath()
	if path == "" {
		return nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var m map[string]relayListEntry
	if err := json.Unmarshal(data, &m); err != nil {
		return nil
	}
	return m
}

func writeRelayListDisk(key string, entry relayListEntry) {
	path := relayListCachePath()
	if path == "" {
		return
	}
	m := readRelayListDisk()
	if m == nil {
		m = map[string]relayListEntry{}
	}
	m[key] = entry
	// Drop stale entries rather than growing the file forever.
	for k, v := range m {
		if time.Since(v.FetchedAt) >= relayListTTL {
			delete(m, k)
		}
	}
	data, err := json.Marshal(m)
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(path), 0o700)
	_ = os.WriteFile(path, data, 0o600)
}

// cachedRelayList returns a cached relay list for (kind, pubHex), calling fetch
// only when nothing fresh is available.
//
// Empty results are cached too. A user with no relay list is the common case,
// and without caching the negative every command would pay the full lookup
// timeout to rediscover that.
func cachedRelayList(kind int, pubHex string, fetch func() []string) []string {
	key := relayListKey(kind, pubHex)

	relayListMem.Lock()
	if e, ok := relayListMem.entries[key]; ok && time.Since(e.FetchedAt) < relayListTTL {
		relayListMem.Unlock()
		return e.Relays
	}
	relayListMem.Unlock()

	if m := readRelayListDisk(); m != nil {
		if e, ok := m[key]; ok && time.Since(e.FetchedAt) < relayListTTL {
			relayListMem.Lock()
			relayListMem.entries[key] = e
			relayListMem.Unlock()
			return e.Relays
		}
	}

	relays := fetch()
	entry := relayListEntry{Relays: relays, FetchedAt: time.Now()}
	relayListMem.Lock()
	relayListMem.entries[key] = entry
	relayListMem.Unlock()
	writeRelayListDisk(key, entry)
	return relays
}

// invalidateRelayList drops a cached entry, in memory and on disk. Called after
// publishing a new list so the next read does not serve the stale one.
func invalidateRelayList(kind int, pubHex string) {
	key := relayListKey(kind, pubHex)
	relayListMem.Lock()
	delete(relayListMem.entries, key)
	relayListMem.Unlock()

	path := relayListCachePath()
	if path == "" {
		return
	}
	m := readRelayListDisk()
	if m == nil {
		return
	}
	delete(m, key)
	if data, err := json.Marshal(m); err == nil {
		_ = os.WriteFile(path, data, 0o600)
	}
}
