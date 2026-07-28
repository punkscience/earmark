package main

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	core "github.com/punkscience/earmark/earmark-core"
	"github.com/spf13/cobra"
)

// channelTimeout bounds any single channel operation. Fan-out publishes one
// gift wrap per member sequentially, so this is generous by design.
const channelTimeout = 90 * time.Second

func channelCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "channel",
		Short: "Share earmarks with other people through a private channel",
		Long: `A channel is a named room of Nostr identities who share earmarks.

Channels are invisible to relays: every message is NIP-59 gift wrapped, so
nothing about a channel — its name, its members, even its existence — is
published. Each post is encrypted separately to each member, which means
removing someone takes effect immediately but a new member sees nothing that
was posted before they joined.

Only the channel's creator can change its membership.

Posts stay playable for 30 days. Use 'channel keep' to adopt one into your own
stash before it expires.`,
	}
	cmd.AddCommand(
		channelCreateCmd(),
		channelListCmd(),
		channelMembersCmd(),
		channelInviteCmd(),
		channelRemoveCmd(),
		channelLeaveCmd(),
		channelInvitesCmd(),
		channelAcceptCmd(),
		channelDeclineCmd(),
		channelSendCmd(),
		channelFeedCmd(),
		channelKeepCmd(),
		channelInboxCmd(),
	)
	return cmd
}

// requireKey resolves the user's key or explains how to set one.
func requireKey() (string, error) {
	hexKey := resolveNostrKey()
	if hexKey == "" {
		return "", fmt.Errorf("no Nostr private key configured — run: earmark key <nsec_or_hex_key>")
	}
	return hexKey, nil
}

func channelContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), channelTimeout)
}

// step reports what the command is waiting on. Channel operations are several
// relay round-trips deep and used to print nothing until they finished, which
// is indistinguishable from a hang. Goes to stderr so piping stdout stays
// clean.
func step(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "… "+format+"\n", args...)
}

// channelInboxCmd shows, or publishes, the user's NIP-17 DM relay list.
func channelInboxCmd() *cobra.Command {
	var publish bool
	cmd := &cobra.Command{
		Use:   "inbox",
		Short: "Show or publish the relays others should send you channel messages on",
		Long: `Channel messages are gift wrapped and addressed to you, so they belong on
relays you actually read — your NIP-17 (kind 10050) DM relay list.

You should not need this command. A list is published for you the first time you
create or join a channel, because channels do not work without one.

What is never automatic is *overwriting* a list you already have — kind 10050
governs your NIP-17 direct messages in every Nostr client, not just this one, so
replacing it is an explicit choice. That is what --publish is for.`,
		Args: cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			pub, err := core.PublicKey(hexKey)
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()

			if !publish {
				step("looking up your inbox relay list")
				current := core.FetchInboxRelays(ctx, pub)
				if len(current) == 0 {
					fmt.Println("No inbox relay list published yet.")
					fmt.Println("One is published for you automatically the first time you create")
					fmt.Println("or join a channel — you should not need to do anything.")
					return nil
				}
				fmt.Printf("%d inbox relay(s):\n\n", len(current))
				for _, r := range current {
					fmt.Printf("  %s\n", r)
				}
				return nil
			}

			relays := core.Relays()
			step("overwriting your inbox relay list with %d configured relay(s)", len(relays))
			if err := core.PublishInboxRelays(ctx, hexKey, relays); err != nil {
				return err
			}
			fmt.Printf("Published. Others will now send your channel messages to:\n\n")
			for _, r := range relays {
				fmt.Printf("  %s\n", r)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&publish, "publish", false,
		"Publish your configured relays as your NIP-17 inbox list")
	return cmd
}

func channelCreateCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "create <name>",
		Short: "Create a channel you own",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("reading channel state from relays")
			ch, err := core.CreateChannel(ctx, hexKey, args[0])
			if err != nil {
				return err
			}
			step("published")
			fmt.Printf("Created channel %q (%s)\n", ch.Descriptor.Name, ch.Descriptor.ID[:12])
			fmt.Printf("Invite someone:  earmark channel invite %q <npub>\n", ch.Descriptor.Name)
			return nil
		},
	}
}

func channelListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List the channels you are in",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("reading channel state from relays")
			st, err := core.LoadChannelState(ctx, hexKey)
			if err != nil {
				return err
			}
			if len(st.Channels) == 0 {
				fmt.Println("No channels yet. Create one:  earmark channel create <name>")
			} else {
				fmt.Printf("%d channel(s):\n\n", len(st.Channels))
				for _, c := range st.Channels {
					role := "member"
					if c.IsCreator(mustPubKey(hexKey)) {
						role = "creator"
					}
					fmt.Printf("  %-24s  %2d member(s)  %-8s  %s\n",
						c.Descriptor.Name, len(c.Members), role, c.Descriptor.ID[:12])
				}
			}
			if n := len(st.Invites); n > 0 {
				fmt.Printf("\n%d pending invite(s) — see: earmark channel invites\n", n)
			}
			return nil
		},
	}
}

func channelMembersCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "members <channel>",
		Short: "Show who is in a channel",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			st, err := core.LoadChannelState(ctx, hexKey)
			if err != nil {
				return err
			}
			ch, err := st.FindByName(args[0])
			if err != nil {
				return err
			}
			self := mustPubKey(hexKey)
			fmt.Printf("%s — %d member(s), roster v%d\n\n", ch.Descriptor.Name, len(ch.Members), ch.Seq)
			for _, m := range ch.Members {
				var notes []string
				if m == ch.Descriptor.Creator {
					notes = append(notes, "creator")
				}
				if m == self {
					notes = append(notes, "you")
				}
				suffix := ""
				if len(notes) > 0 {
					suffix = "  (" + strings.Join(notes, ", ") + ")"
				}
				fmt.Printf("  %s%s\n", core.NpubFromPublicKey(m), suffix)
			}
			return nil
		},
	}
}

func channelInviteCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "invite <channel> <npub_or_hex>",
		Short: "Add someone to a channel you created",
		Args:  cobra.ExactArgs(2),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			memberPub, err := core.ResolvePublicKey(args[1])
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("reading channel state, then sending an encrypted invite")
			if err := core.InviteToChannel(ctx, hexKey, args[0], memberPub); err != nil {
				return err
			}
			fmt.Printf("Invited %s to %q.\n", core.NpubFromPublicKey(memberPub), args[0])
			if !core.HasInboxRelays(memberPub) {
				// They publish their own list the first time they use a
				// channel-aware client, so this resolves itself — but until it
				// does, delivery is a guess and worth saying.
				fmt.Fprintf(os.Stderr,
					"! They have not published inbox relays yet, so delivery falls back to yours.\n"+
						"  It will sort itself out once they open a channel-aware client.\n")
			}
			fmt.Println("They will see nothing posted before now — channels do not backfill.")
			return nil
		},
	}
}

func channelRemoveCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "remove <channel> <npub_or_hex>",
		Short: "Remove someone from a channel you created",
		Args:  cobra.ExactArgs(2),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			memberPub, err := core.ResolvePublicKey(args[1])
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("updating the roster and notifying members")
			if err := core.RemoveFromChannel(ctx, hexKey, args[0], memberPub); err != nil {
				return err
			}
			fmt.Printf("Removed %s from %q.\n", core.NpubFromPublicKey(memberPub), args[0])
			fmt.Println("They receive nothing further. Anything already sent to them stays readable.")
			return nil
		},
	}
}

func channelLeaveCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "leave <channel>",
		Short: "Leave a channel",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			if err := core.LeaveChannel(ctx, hexKey, args[0]); err != nil {
				return err
			}
			fmt.Printf("Left %q.\n", args[0])
			return nil
		},
	}
}

func channelInvitesCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "invites",
		Short: "Show channel invites awaiting your decision",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("fetching gift wraps")
			_, st, err := core.SyncChannels(ctx, hexKey)
			if err != nil {
				return err
			}
			if len(st.Invites) == 0 {
				fmt.Println("No pending invites.")
				return nil
			}
			var trusted, requests []core.ChannelInvite
			for _, inv := range st.Invites {
				if inv.Trusted {
					trusted = append(trusted, inv)
				} else {
					requests = append(requests, inv)
				}
			}
			printInvites("Invites", trusted)
			// Invites from people you do not follow are shown, but separately —
			// web of trust filters how loudly they arrive, it grants nothing.
			printInvites("Requests (from people you don't follow)", requests)
			fmt.Println("Accept with:  earmark channel accept <id>")
			return nil
		},
	}
}

func printInvites(heading string, invites []core.ChannelInvite) {
	if len(invites) == 0 {
		return
	}
	fmt.Printf("%s:\n\n", heading)
	for _, inv := range invites {
		fmt.Printf("  %-24s  %2d member(s)  from %s\n     id: %s\n",
			inv.Descriptor.Name, len(inv.Members),
			core.NpubFromPublicKey(inv.From), inv.Descriptor.ID)
	}
	fmt.Println()
}

func channelAcceptCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "accept <channel_id>",
		Short: "Accept a channel invite",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			ch, err := core.AcceptInvite(ctx, hexKey, args[0])
			if err != nil {
				return err
			}
			fmt.Printf("Joined %q.\n", ch.Descriptor.Name)
			fmt.Println("Tracks posted from now on will appear in:  earmark channel feed")
			return nil
		},
	}
}

func channelDeclineCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "decline <channel_id>",
		Short: "Decline a channel invite",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			if err := core.DeclineInvite(ctx, hexKey, args[0]); err != nil {
				return err
			}
			fmt.Println("Declined. You will not be asked about this channel again.")
			return nil
		},
	}
}

func channelSendCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "send <channel> <search>",
		Short: "Post one of your earmarks to a channel",
		Long: `Post an earmark you have already uploaded to a channel.

<search> matches against artist, album and title. Nothing is re-uploaded — the
channel post hands members the existing chunk hashes and file key, so the cost
is one small event per member.`,
		Args: cobra.ExactArgs(2),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			earmarks, err := core.FetchEarmarks(hexKey)
			if err != nil {
				return fmt.Errorf("could not fetch earmarks: %w", err)
			}
			match, err := pickEarmark(earmarks, args[1])
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("wrapping and publishing to each member")
			if err := core.PostToChannel(ctx, hexKey, args[0], *match); err != nil {
				return err
			}
			fmt.Printf("Posted %s to %q.\n", describeEarmark(*match), args[0])
			return nil
		},
	}
}

// pickEarmark finds the single earmark matching a search term, and refuses to
// guess when more than one does.
func pickEarmark(earmarks []core.Earmark, search string) (*core.Earmark, error) {
	needle := strings.ToLower(search)
	var matches []core.Earmark
	for _, e := range earmarks {
		if e.Blossom == nil {
			continue
		}
		hay := strings.ToLower(e.Artist + " " + e.Album + " " + e.Title)
		if strings.Contains(hay, needle) {
			matches = append(matches, e)
		}
	}
	switch len(matches) {
	case 0:
		return nil, fmt.Errorf("no uploaded earmark matches %q", search)
	case 1:
		return &matches[0], nil
	default:
		fmt.Fprintf(os.Stderr, "%q matches %d earmarks:\n", search, len(matches))
		for _, e := range matches {
			fmt.Fprintf(os.Stderr, "  %s\n", describeEarmark(e))
		}
		return nil, fmt.Errorf("be more specific")
	}
}

func describeEarmark(e core.Earmark) string {
	desc := e.Title
	if e.Artist != "" {
		desc = e.Artist + " — " + desc
	}
	if desc == "" {
		desc = "(untitled)"
	}
	return desc
}

func channelFeedCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "feed [channel]",
		Short: "Show tracks posted to your channels",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := channelContext()
			defer cancel()
			step("fetching gift wraps")
			posts, st, err := core.SyncChannels(ctx, hexKey)
			if err != nil {
				return err
			}
			filterID := ""
			if len(args) == 1 {
				ch, err := st.FindByName(args[0])
				if err != nil {
					return err
				}
				filterID = ch.Descriptor.ID
			}

			shown := 0
			for _, p := range posts {
				if filterID != "" && p.Chan != filterID {
					continue
				}
				name := p.Chan[:8]
				if ch := st.Find(p.Chan); ch != nil {
					name = ch.Descriptor.Name
				}
				fmt.Printf("  %-14s  %-16s  %-40s  %s  from %s\n",
					p.ID(), truncate(name, 16), truncate(describeEarmark(p.Earmark), 40),
					time.Unix(p.PostedAt, 0).Format("2006-01-02 15:04"),
					shortNpub(p.Sender))
				shown++
			}
			if shown == 0 {
				fmt.Println("Nothing posted yet. Tracks posted from now on will appear here —")
				fmt.Println("channels do not backfill, so you see only what arrives after you join.")
				return nil
			}
			fmt.Printf("\n%d post(s). Keep one before it expires:  earmark channel keep <id>\n", shown)
			return nil
		},
	}
}

func channelKeepCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "keep <post_id>",
		Short: "Adopt a channel post into your own stash",
		Long: `Copy a posted track onto your own Blossom servers and record it in your
own earmark list with a fresh 30-day clock.

Where the servers support BUD-04 mirroring the copy happens server to server,
so no audio passes through this machine. Post ids come from 'channel feed'.`,
		Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			hexKey, err := requireKey()
			if err != nil {
				return err
			}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
			defer cancel()
			posts, _, err := core.SyncChannels(ctx, hexKey)
			if err != nil {
				return err
			}
			var match *core.ChannelPost
			for i := range posts {
				if posts[i].ID() == args[0] {
					match = &posts[i]
					break
				}
			}
			if match == nil {
				return fmt.Errorf("no post with id %q — run: earmark channel feed", args[0])
			}
			fmt.Printf("Keeping %s...\n", describeEarmark(match.Earmark))
			if err := core.KeepPost(ctx, hexKey, match); err != nil {
				return err
			}
			fmt.Println("Kept. It is now in your own earmark list with a fresh 30-day clock.")
			return nil
		},
	}
}

func shortNpub(pubHex string) string {
	npub := core.NpubFromPublicKey(pubHex)
	if len(npub) > 16 {
		return npub[:12] + "…"
	}
	return npub
}

// mustPubKey derives the caller's pubkey, returning "" when the key is bad —
// callers use it only to label output, never to make a decision.
func mustPubKey(hexPrivKey string) string {
	pub, err := core.PublicKey(hexPrivKey)
	if err != nil {
		return ""
	}
	return pub
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "\u2026"
}
