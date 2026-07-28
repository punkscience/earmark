package com.derpy.earmarks.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.derpy.earmarks.data.ChannelInvite

/**
 * The channel selector: a scrollable row of sources above the now-playing card.
 *
 * "My Earmarks" is always first and always present. Channels follow. A pending
 * invite count appears at the end when there is one.
 *
 * Selecting a chip replaces the playlist entirely — channels never merge into
 * the personal stash, so it is always unambiguous whose music is playing.
 */
@Composable
fun ChannelSourceBar(
    channelState: ChannelUiState,
    onSelectSource: (PlayerSource) -> Unit,
    onShowInvites: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val active = channelState.source

        FilterChip(
            selected = active is PlayerSource.MyEarmarks,
            onClick = { onSelectSource(PlayerSource.MyEarmarks) },
            label = { Text("My Earmarks") }
        )

        for (channel in channelState.channels) {
            val id = channel.descriptor.id
            val count = channelState.postsFor(id).size
            FilterChip(
                selected = (active as? PlayerSource.Channel)?.id == id,
                onClick = { onSelectSource(PlayerSource.Channel(id, channel.descriptor.name)) },
                label = {
                    Text(
                        if (count > 0) "${channel.descriptor.name} ($count)"
                        else channel.descriptor.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        if (channelState.pendingInviteCount > 0) {
            BadgedBox(badge = { Badge { Text("${channelState.pendingInviteCount}") } }) {
                FilterChip(
                    selected = false,
                    onClick = onShowInvites,
                    label = { Text("Invites") },
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }
    }
}

/**
 * Pending channel invites.
 *
 * Invites from people the user does not follow are shown under a separate
 * heading rather than hidden — web of trust decides how loudly an invite
 * arrives, not whether it is allowed to.
 */
@Composable
fun InvitesDialog(
    invites: List<ChannelInvite>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Channel invites") },
        text = {
            Column {
                val (trusted, requests) = invites.partition { it.trusted }
                InviteGroup("Invites", trusted, onAccept, onDecline)
                InviteGroup("Requests — from people you don't follow", requests, onAccept, onDecline)
                Text(
                    "Joining shows you tracks posted from now on. Channels do not " +
                        "backfill, so anything shared before you join stays unseen.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun InviteGroup(
    heading: String,
    invites: List<ChannelInvite>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    if (invites.isEmpty()) return
    Text(heading, style = MaterialTheme.typography.labelLarge)
    for (invite in invites) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(invite.descriptor.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${invite.members.size} member(s)",
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(onClick = { onAccept(invite.descriptor.id) }) { Text("Join") }
                TextButton(onClick = { onDecline(invite.descriptor.id) }) { Text("Decline") }
            }
        }
    }
}

/** Picks which channel to share the currently playing track into. */
@Composable
fun ShareToChannelDialog(
    channels: List<com.derpy.earmarks.data.Channel>,
    trackTitle: String,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share to channel") },
        text = {
            Column {
                if (channels.isEmpty()) {
                    Text(
                        "You are not in any channels yet. Channels are created from " +
                            "the desktop app."
                    )
                } else {
                    Text(
                        "Send \"$trackTitle\" to:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    for (channel in channels) {
                        TextButton(onClick = { onShare(channel.descriptor.id) }) {
                            Text(channel.descriptor.name)
                        }
                    }
                    Text(
                        "Nothing is re-uploaded — members receive the file key for the " +
                            "copy already on Blossom.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
