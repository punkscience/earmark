package com.derpy.earmarks.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.derpy.earmarks.player.PlayerState

@Composable
fun PlayerScreen(
    appState: AppState,
    playerState: PlayerState,
    stats: BlossomStats,
    notice: String?,
    onDismissNotice: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onDeleteCurrent: () -> Unit,
    onClearKey: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (notice != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text("Heads up") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) { Text("OK") }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = { Text("Remove your stored Nostr key. You'll need to enter it again next time.") },
            confirmButton = {
                TextButton(onClick = { onClearKey(); showSettings = false }) { Text("Remove key") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        val title = playerState.title.ifBlank { "this earmark" }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete earmark?") },
            text = {
                Text(
                    "This will permanently remove \"$title\" from your earmark list " +
                        "and delete its files from every Blossom server. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeleteCurrent(); showDeleteConfirm = false }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Wordmark(fontSizeSp = 20)
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        Spacer(Modifier.weight(1f))

        // Album art (from embedded ID3/Vorbis tags). Decoded once per byte
        // array via `remember` so we don't repeat the work on every
        // unrelated state emission. Shown only while a track is loaded.
        val artworkBitmap = remember(playerState.artworkData) {
            playerState.artworkData?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
        if (appState is AppState.Playing && artworkBitmap != null) {
            Image(
                bitmap = artworkBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f)
            )
            Spacer(Modifier.height(24.dp))
        }

        // Status / now-playing area
        when (appState) {
            is AppState.Loading -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(appState.message, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is AppState.Downloading -> {
                CircularProgressIndicator(
                    progress = { appState.done.toFloat() / appState.total },
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Downloading ${appState.done} / ${appState.total}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is AppState.Playing -> {
                if (playerState.title.isNotBlank()) {
                    Text(playerState.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                }
                if (playerState.artist.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(playerState.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
                if (playerState.album.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(playerState.album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
                if (playerState.totalTracks > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("${playerState.currentIndex + 1} / ${playerState.totalTracks}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                if (appState.unavailable > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${appState.unavailable} earmark(s) unavailable — retry next launch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is AppState.Error -> {
                Text(appState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center)
            }
            else -> {}
        }

        Spacer(Modifier.weight(1f))

        // Playback controls — only active when playing
        val controlsEnabled = appState is AppState.Playing
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showDeleteConfirm = true },
                enabled = controlsEnabled
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete current earmark",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onSkipPrevious, enabled = controlsEnabled) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous",
                    modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onPlayPause, enabled = controlsEnabled,
                modifier = Modifier.size(64.dp)) {
                Icon(
                    if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }
            IconButton(onClick = onSkipNext, enabled = controlsEnabled) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next",
                    modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        StatsPanel(stats = stats)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatsPanel(stats: BlossomStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "blossom storage",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "[ ok ]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${stats.totalEarmarks} earmarks · ${stats.totalParts} parts",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "%.1f MB".format(stats.totalMb),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
