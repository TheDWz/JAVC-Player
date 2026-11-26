package com.stremiovlc.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stremiovlc.player.player.ChapterInfo
import com.stremiovlc.player.player.TrackInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionSheet(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    chapters: List<ChapterInfo>,
    currentAudioTrackId: Int,
    currentSubtitleTrackId: Int,
    currentChapterIndex: Int,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onChapterSelected: (Int) -> Unit,
    audioDelayMs: Long,
    onAudioDelayChange: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = buildList {
        if (audioTracks.isNotEmpty()) add("Audio")
        if (subtitleTracks.isNotEmpty()) add("Subtitles")
        if (chapters.isNotEmpty()) add("Chapters")
    }

    if (tabs.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF01C1C1E),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            if (tabs.size > 1) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                            icon = {
                                when (title) {
                                    "Audio" -> Icon(Icons.Default.Audiotrack, contentDescription = null)
                                    "Subtitles" -> Icon(Icons.Default.Subtitles, contentDescription = null)
                                    "Chapters" -> Icon(Icons.Default.List, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val currentTabName = tabs.getOrNull(selectedTab) ?: tabs.first()

            when (currentTabName) {
                "Audio" -> {
                    AudioDelayRow(audioDelayMs = audioDelayMs, onChange = onAudioDelayChange)
                    HorizontalDivider(color = Color(0x33FFFFFF))
                    AudioTrackList(
                        tracks = audioTracks,
                        currentTrackId = currentAudioTrackId,
                        onTrackSelected = onAudioTrackSelected
                    )
                }
                "Subtitles" -> SubtitleTrackList(
                    tracks = subtitleTracks,
                    currentTrackId = currentSubtitleTrackId,
                    onTrackSelected = onSubtitleTrackSelected
                )
                "Chapters" -> ChapterList(
                    chapters = chapters,
                    currentChapterIndex = currentChapterIndex,
                    onChapterSelected = onChapterSelected
                )
            }
        }
    }
}

/** Manual A/V sync: positive delays audio (use when sound is ahead of the picture). */
@Composable
private fun AudioDelayRow(audioDelayMs: Long, onChange: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Audio delay: ${if (audioDelayMs > 0) "+" else ""}$audioDelayMs ms",
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onChange(audioDelayMs - 50) }) {
            Icon(Icons.Default.Remove, contentDescription = "Audio earlier by 50 ms", tint = Color.White)
        }
        TextButton(onClick = { onChange(0) }, enabled = audioDelayMs != 0L) { Text("Reset") }
        IconButton(onClick = { onChange(audioDelayMs + 50) }) {
            Icon(Icons.Default.Add, contentDescription = "Audio later by 50 ms", tint = Color.White)
        }
    }
}

@Composable
private fun AudioTrackList(
    tracks: List<TrackInfo>,
    currentTrackId: Int,
    onTrackSelected: (Int) -> Unit
) {
    LazyColumn {
        items(tracks) { track ->
            TrackRow(
                name = track.name,
                isSelected = track.id == currentTrackId,
                onClick = { onTrackSelected(track.id) }
            )
        }
    }
}

@Composable
private fun SubtitleTrackList(
    tracks: List<TrackInfo>,
    currentTrackId: Int,
    onTrackSelected: (Int) -> Unit
) {
    LazyColumn {
        item {
            TrackRow(
                name = "Disable Subtitles",
                isSelected = currentTrackId == -1,
                onClick = { onTrackSelected(-1) }
            )
            HorizontalDivider(color = Color(0x33FFFFFF))
        }
        items(tracks.filter { it.id != -1 }) { track ->
            TrackRow(
                name = track.name,
                isSelected = track.id == currentTrackId,
                onClick = { onTrackSelected(track.id) }
            )
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<ChapterInfo>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit
) {
    LazyColumn {
        items(chapters) { chapter ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChapterSelected(chapter.index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (chapter.index == currentChapterIndex) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Spacer(modifier = Modifier.width(36.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapter.name,
                        fontWeight = if (chapter.index == currentChapterIndex) FontWeight.Bold else FontWeight.Normal,
                        color = Color.White
                    )
                    Text(
                        text = formatTime(chapter.timeOffsetMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xAAFFFFFF)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Spacer(modifier = Modifier.width(36.dp))
        }
        Text(
            text = name,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = Color.White
        )
    }
}
