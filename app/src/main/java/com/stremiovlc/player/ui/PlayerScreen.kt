package com.stremiovlc.player.ui

import android.view.LayoutInflater
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.withTimeoutOrNull
import com.stremiovlc.player.R
import com.stremiovlc.player.ui.components.PlayerControls
import com.stremiovlc.player.ui.components.SpeedIndicator
import com.stremiovlc.player.ui.components.TrackSelectionSheet
import com.stremiovlc.player.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val playerState by viewModel.playerState.collectAsState()
    val showSpeed by viewModel.showSpeedIndicator.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(controlsVisible, lastInteractionTime) {
        if (controlsVisible && playerState.isPlaying) {
            delay(4000)
            if (System.currentTimeMillis() - lastInteractionTime >= 3800) {
                controlsVisible = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val released = withTimeoutOrNull(300L) {
                            tryAwaitRelease()
                        }

                        if (released == null) {
                            viewModel.onSpeedHoldStart()
                            tryAwaitRelease()
                            viewModel.onSpeedHoldEnd()
                        } else if (released) {
                            controlsVisible = !controlsVisible
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    }
                )
            }
    ) {
        // VLC SurfaceView
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx).inflate(R.layout.vlc_surface, null)
                val videoSurface = view.findViewById<SurfaceView>(R.id.vlc_surface)
                val subtitleSurface = view.findViewById<SurfaceView>(R.id.vlc_subtitle_surface)
                viewModel.playerWrapper.attachSurfaces(videoSurface, subtitleSurface)
                view
            },
            modifier = Modifier.fillMaxSize()
        )

        SpeedIndicator(
            visible = showSpeed,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )

        PlayerControls(
            playerState = playerState,
            visible = controlsVisible,
            onPlayPause = {
                viewModel.togglePlayPause()
                lastInteractionTime = System.currentTimeMillis()
            },
            onSeek = { timeMs ->
                viewModel.seekTo(timeMs)
                lastInteractionTime = System.currentTimeMillis()
            },
            onSkipForward = {
                viewModel.skipForward()
                lastInteractionTime = System.currentTimeMillis()
            },
            onSkipBackward = {
                viewModel.skipBackward()
                lastInteractionTime = System.currentTimeMillis()
            },
            onTrackSelectionClick = {
                showTrackSheet = true
                lastInteractionTime = System.currentTimeMillis()
            },
            onBackClick = {
                viewModel.savePositionNow()
                onBack()
            }
        )

        if (showTrackSheet) {
            TrackSelectionSheet(
                audioTracks = playerState.audioTracks,
                subtitleTracks = playerState.subtitleTracks,
                chapters = playerState.chapters,
                currentAudioTrackId = playerState.currentAudioTrackId,
                currentSubtitleTrackId = playerState.currentSubtitleTrackId,
                currentChapterIndex = playerState.currentChapterIndex,
                onAudioTrackSelected = { viewModel.setAudioTrack(it) },
                onSubtitleTrackSelected = { viewModel.setSubtitleTrack(it) },
                onChapterSelected = { viewModel.setChapter(it) },
                onDismiss = { showTrackSheet = false }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.savePositionNow()
            viewModel.playerWrapper.detachSurfaces()
        }
    }
}
