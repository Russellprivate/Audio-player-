package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPink
import com.example.utils.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayerBar(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playerState by viewModel.audioPlayer.playerState.collectAsState()
    var showFullPlayer by remember { mutableStateOf(false) }

    val hasActive = playerState.activeAudio != null || playerState.previewFileTitle != null
    if (!hasActive) return

    val title = playerState.activeAudio?.title ?: playerState.previewFileTitle ?: "Audio"
    val progress = if (playerState.durationMs > 0) {
        playerState.currentPositionMs.toFloat() / playerState.durationMs
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { showFullPlayer = true },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(NeonCyan),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { viewModel.audioPlayer.togglePlayPause() }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${FormatUtils.formatDuration(playerState.currentPositionMs)} / ${FormatUtils.formatDuration(playerState.durationMs)}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        WaveformVisualizer(
                            isPlaying = playerState.isPlaying,
                            progress = progress,
                            barCount = 20,
                            modifier = Modifier.height(18.dp)
                        )
                    }
                }
            }

            IconButton(onClick = { viewModel.audioPlayer.stop() }, modifier = Modifier.size(32.dp)) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showFullPlayer) {
        ModalBottomSheet(
            onDismissRequest = { showFullPlayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            FullPlayerContent(
                viewModel = viewModel,
                onClose = { showFullPlayer = false }
            )
        }
    }
}

@Composable
fun FullPlayerContent(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val playerState by viewModel.audioPlayer.playerState.collectAsState()
    val title = playerState.activeAudio?.title ?: playerState.previewFileTitle ?: "Audio Track"
    val progress = if (playerState.durationMs > 0) {
        playerState.currentPositionMs.toFloat() / playerState.durationMs
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top drag handle & developer tag
        Text(
            text = "Russell Private Limited • Audio Player",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Large Waveform Visualizer
        Card(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                WaveformVisualizer(
                    isPlaying = playerState.isPlaying,
                    progress = progress,
                    barCount = 42,
                    modifier = Modifier.height(80.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Title and Format
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = playerState.activeAudio?.originalVideoName ?: "Converted Audio",
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Seek Slider
        Slider(
            value = playerState.currentPositionMs.toFloat(),
            onValueChange = { pos -> viewModel.audioPlayer.seekTo(pos.toLong()) },
            valueRange = 0f..playerState.durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = NeonCyan,
                activeTrackColor = NeonCyan
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = FormatUtils.formatDuration(playerState.currentPositionMs),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = FormatUtils.formatDuration(playerState.durationMs),
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Main Controls: Rewind 10s, Play/Pause, Forward 10s, Loop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.audioPlayer.toggleLoop() }) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = "Loop",
                    tint = if (playerState.isLooping) NeonCyan else MaterialTheme.colorScheme.outline
                )
            }

            IconButton(onClick = {
                val nextPos = (playerState.currentPositionMs - 10000L).coerceAtLeast(0L)
                viewModel.audioPlayer.seekTo(nextPos)
            }) {
                Icon(imageVector = Icons.Default.FastRewind, contentDescription = "Rewind 10s", modifier = Modifier.size(32.dp))
            }

            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(NeonCyan)
                    .clickable { viewModel.audioPlayer.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(38.dp)
                )
            }

            IconButton(onClick = {
                val nextPos = (playerState.currentPositionMs + 10000L).coerceAtMost(playerState.durationMs)
                viewModel.audioPlayer.seekTo(nextPos)
            }) {
                Icon(imageVector = Icons.Default.FastForward, contentDescription = "Forward 10s", modifier = Modifier.size(32.dp))
            }

            IconButton(onClick = {
                playerState.activeAudio?.let {
                    FormatUtils.shareAudioFile(context, it.filePath, it.title)
                }
            }) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Playback Speed Selector
        Text(
            text = "Playback Speed: ${playerState.speed}x",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                FilterChip(
                    selected = playerState.speed == spd,
                    onClick = { viewModel.audioPlayer.setSpeed(spd) },
                    label = { Text("${spd}x", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
