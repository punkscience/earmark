package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/punkscience/earmark/earmark-cli/internal/filter"
	"github.com/spf13/cobra"

	core "github.com/punkscience/earmark/earmark-core"
)

// Version is the build version, injected at build time via ldflags.
var Version = "dev"

func main() {
	configureCore()
	if err := rootCmd().Execute(); err != nil {
		fmt.Fprintf(os.Stderr, "earmark: %v\n", err)
		os.Exit(1)
	}
}

func rootCmd() *cobra.Command {
	var sourceDir string
	var channels []string

	cmd := &cobra.Command{
		Use:   "earmark [--source <dir>] [keyword...]",
		Short: "Encrypt and upload music files to Blossom cloud storage via Nostr",
		Long: `earmark finds music files (individual files, PLS/M3U playlists, or
keyword search in a source directory), encrypts them with AES-256-GCM,
uploads to Blossom servers, and records the manifest in a private Nostr list.

Keyword expression syntax (AND binds tighter than OR):
  jazz AND piano          path must contain both "jazz" and "piano"
  jazz OR blues           path must contain "jazz" or "blues"
  (jazz OR blues) AND piano
  "jazz piano"            exact phrase match

Without a subcommand, positional arguments are treated as keywords for
searching the --source directory. If the first argument is a file or
playlist, it is uploaded directly.

Examples:
  earmark --source ~/Music jazz               Search for "jazz", select, upload
  earmark --source ~/Music "miles AND davis"   Search with expression
  earmark playlist.m3u                         Upload all tracks in playlist
  earmark song.flac                            Upload a single file
  earmark --channel "Family Jams" song.flac    Upload and share to a channel`,
		Args: cobra.ArbitraryArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			hexKey := resolveNostrKey()
			if hexKey == "" {
				return fmt.Errorf("no Nostr private key configured — run: earmark key <nsec_or_hex_key>")
			}

			runLegacyMigration(hexKey)

			// Flush any offline queue first.
			if n, _ := FlushQueue(hexKey); n > 0 {
				fmt.Printf("Flushed %d queued earmark(s).\n", n)
			}

			// Determine what to upload.
			paths, err := resolveUploadPaths(sourceDir, args)
			if err != nil {
				return err
			}
			if len(paths) == 0 {
				return fmt.Errorf("nothing to upload")
			}

			// If searching a source directory, run interactive selector.
			if sourceDir != "" {
				selected, err := runSelector(paths)
				if err != nil {
					return fmt.Errorf("selector: %w", err)
				}
				if len(selected) == 0 {
					fmt.Println("No files selected.")
					return nil
				}
				paths = selected
			}

			return UploadFiles(hexKey, paths, channels)
		},
	}

	cmd.Flags().StringVar(&sourceDir, "source", "", "Directory to search for audio files")
	cmd.Flags().StringArrayVar(&channels, "channel", nil,
		"Also post each earmark to this channel (repeatable)")

	cmd.AddCommand(versionCmd())
	cmd.AddCommand(keyCmd())
	cmd.AddCommand(listCmd())
	cmd.AddCommand(downloadCmd())
	cmd.AddCommand(channelCmd())

	return cmd
}

// runLegacyMigration merges any earmark list published under the legacy d tag
// into the current list. Failures are non-fatal; the command proceeds.
func runLegacyMigration(hexKey string) {
	n, err := core.MigrateLegacyEarmarks(hexKey)
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: legacy list migration: %v\n", err)
		return
	}
	if n > 0 {
		fmt.Printf("Migrated %d earmark(s) from legacy list.\n", n)
	}
}

// resolveUploadPaths determines what files to upload based on CLI args.
func resolveUploadPaths(sourceDir string, args []string) ([]string, error) {
	if sourceDir != "" {
		// Search in source directory, with optional keyword filter.
		var expr filter.Expr
		if len(args) > 0 {
			exprStr := argsToExpr(args)
			var err error
			expr, err = filter.ParseExpr(exprStr)
			if err != nil {
				return nil, fmt.Errorf("invalid keyword expression: %w", err)
			}
		}
		files, err := scanMusicDir(sourceDir, expr)
		if err != nil {
			return nil, err
		}
		if len(args) > 0 {
			fmt.Printf("Found %d file(s) matching %q in %s\n", len(files), argsToExpr(args), sourceDir)
		} else {
			fmt.Printf("Found %d audio file(s) in %s\n", len(files), sourceDir)
		}
		return files, nil
	}
	if len(args) > 0 {
		path := args[0]
		if isPlaylistFile(path) {
			fmt.Printf("Parsing playlist: %s\n", path)
			tracks, err := parsePlaylist(path)
			if err != nil {
				return nil, err
			}
			fmt.Printf("Found %d track(s) in playlist.\n", len(tracks))
			return tracks, nil
		}
		// Single file upload.
		if _, err := os.Stat(path); err != nil {
			return nil, fmt.Errorf("file not found: %s", path)
		}
		return []string{path}, nil
	}
	return nil, fmt.Errorf("specify a source directory with --source, a file path, or a playlist")
}

// isPlaylistFile reports whether path has a playlist extension.
func isPlaylistFile(path string) bool {
	ext := strings.ToLower(filepath.Ext(path))
	return ext == ".m3u" || ext == ".m3u8" || ext == ".pls"
}

// isPlaylistOrFile reports whether arg looks like a file path (has extension
// matching a playlist or audio format) rather than a keyword.
func isPlaylistOrFile(arg string) bool {
	if isPlaylistFile(arg) {
		return true
	}
	ext := strings.ToLower(filepath.Ext(arg))
	return ext == ".mp3" || ext == ".wav" || ext == ".flac" ||
		ext == ".ogg" || ext == ".m4a" || ext == ".aac"
}

// argsToExpr converts positional CLI args into a keyword expression string.
// Multi-word args are wrapped as quoted phrases; single-word args and args
// containing AND/OR/parens are passed through.
func argsToExpr(args []string) string {
	parts := make([]string, len(args))
	for i, a := range args {
		if isPhraseArg(a) {
			parts[i] = `"` + a + `"`
		} else {
			parts[i] = a
		}
	}
	return strings.Join(parts, " OR ")
}

func isPhraseArg(s string) bool {
	if strings.ContainsAny(s, `"()`) {
		return false
	}
	fields := strings.Fields(s)
	if len(fields) <= 1 {
		return false
	}
	for _, f := range fields {
		switch strings.ToUpper(f) {
		case "AND", "OR":
			return false
		}
	}
	return true
}

func keyCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "key <nsec_or_hex_key>",
		Short: "Save a Nostr private key to the config file",
		Long: `Save your Nostr private key to ~/.config/earmark/config.json.

The key is used to sign and publish earmark events to Nostr relays, and to
authenticate with Blossom servers for uploads/downloads.

You can pass either a bech32-encoded nsec key (nsec1...) or a raw 64-character
hex string. The key is stored as hex internally.

Pass an empty string to clear the saved key:

  earmark key ""`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			rawInput := args[0]
			cfg, err := LoadConfig()
			if err != nil {
				cfg = &Config{}
			}
			if rawInput == "" {
				cfg.NostrPrivateKey = ""
				if err := SaveConfig(cfg); err != nil {
					return fmt.Errorf("could not save config: %w", err)
				}
				fmt.Println("Nostr private key cleared.")
				return nil
			}
			hexKey, err := core.ResolvePrivateKey(rawInput)
			if err != nil {
				return fmt.Errorf("invalid key: %w", err)
			}
			cfg.NostrPrivateKey = hexKey
			if err := SaveConfig(cfg); err != nil {
				return fmt.Errorf("could not save config: %w", err)
			}
			path, _ := configFilePath()
			fmt.Printf("Nostr private key saved to %s\n", path)
			return nil
		},
	}
}

func versionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the earmark version",
		Args:  cobra.NoArgs,
		Run: func(cmd *cobra.Command, _ []string) {
			fmt.Println(Version)
		},
	}
}

func listCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List your earmarked tracks from Nostr",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			hexKey := resolveNostrKey()
			if hexKey == "" {
				return fmt.Errorf("no Nostr private key configured — run: earmark key <nsec_or_hex_key>")
			}
			runLegacyMigration(hexKey)
			earmarks, err := core.FetchEarmarks(hexKey)
			if err != nil {
				return fmt.Errorf("could not fetch earmarks: %w", err)
			}
			if len(earmarks) == 0 {
				fmt.Println("No earmarks found.")
				return nil
			}
			fmt.Printf("%d earmark(s):\n\n", len(earmarks))
			for i, e := range earmarks {
				ts := time.Unix(e.Timestamp, 0).Format("2006-01-02 15:04")
				desc := e.Title
				if e.Artist != "" {
					desc = e.Artist + " — " + desc
				}
				if desc == "" {
					desc = filepath.Base(e.Path)
				}
				blossomTag := ""
				if e.Blossom != nil {
					blossomTag = " [Blossom]"
				}
				fmt.Printf(" %3d.  %-50s  (%s)%s\n", i+1, desc, ts, blossomTag)
			}
			return nil
		},
	}
}

func downloadCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "download",
		Short: "Download earmarked tracks from Blossom",
		Long: `Fetch your earmark list and download all Blossom-uploaded tracks,
decrypting and reassembling them to the current directory.

Tracks that exist locally are skipped.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			hexKey := resolveNostrKey()
			if hexKey == "" {
				return fmt.Errorf("no Nostr private key configured — run: earmark key <nsec_or_hex_key>")
			}
			runLegacyMigration(hexKey)
			fmt.Println("Fetching earmarks...")
			earmarks, err := core.FetchEarmarks(hexKey)
			if err != nil {
				return fmt.Errorf("could not fetch earmarks: %w", err)
			}

			downloaded := 0
			for i, e := range earmarks {
				if e.Blossom == nil {
					continue
				}
				desc := filepath.Base(e.Path)
				if e.Title != "" {
					desc = e.Title
				}

				// Check if local file exists.
				if e.Path != "" {
					if _, err := os.Stat(e.Path); err == nil {
						fmt.Printf("[%d/%d] %s: already on disk, skipped.\n", i+1, len(earmarks), desc)
						continue
					}
				}

				fmt.Printf("[%d/%d] %s: downloading...\n", i+1, len(earmarks), desc)
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
				tmp, err := core.DownloadAndReassemble(ctx, e.Blossom, hexKey, func(done, total int) {
					fmt.Printf("  chunk %d/%d\n", done, total)
				})
				cancel()
				if err != nil {
					fmt.Fprintf(os.Stderr, "  FAILED: %v\n", err)
					continue
				}

				// Rename temp file to a proper name in current directory.
				ext := filepath.Ext(e.Path)
				if ext == "" && e.Blossom.Ext != "" {
					ext = e.Blossom.Ext
				}
				outName := desc
				if !strings.HasSuffix(outName, ext) {
					outName += ext
				}
				// Sanitize filename.
				outName = strings.Map(func(r rune) rune {
					if r == '/' || r == '\\' || r == ':' {
						return '_'
					}
					return r
				}, outName)

				if err := os.Rename(tmp, outName); err != nil {
					os.Remove(tmp)
					fmt.Fprintf(os.Stderr, "  FAILED to save: %v\n", err)
					continue
				}
				fmt.Printf("  Saved to: %s\n", outName)
				downloaded++
			}
			fmt.Printf("\n%d file(s) downloaded.\n", downloaded)
			return nil
		},
	}
}
