package com.stremiovlc.player.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stremiovlc.player.ui.components.OsdPill
import com.stremiovlc.player.ui.components.PlayerControls
import com.stremiovlc.player.ui.components.TrackSelectionSheet
import com.stremiovlc.player.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onBrightnessUp: () -> Float = { 0f },
    onBrightnessDown: () -> Float = { 0f }
) {
    val playerState by viewModel.playerState.collectAsState()
    val showSpeed by viewModel.showSpeedIndicator.collectAsState()
    val isInPiP by viewModel.isInPictureInPictureMode.collectAsState()
    val title by viewModel.title.collectAsState()
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var controlsVisible by remember { mutableStateOf(true) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var volumeOverlayPercent by remember { mutableStateOf(0) }
    var showBrightnessOverlay by remember { mutableStateOf(false) }
    var brightnessOverlayPercent by remember { mutableStateOf(0) }

    LaunchedEffect(showVolumeOverlay) {
        if (showVolumeOverlay) {
            delay(1500)
            showVolumeOverlay = false
        }
    }
    LaunchedEffect(showBrightnessOverlay) {
        if (showBrightnessOverlay) {
            delay(1500)
            showBrightnessOverlay = false
        }
    }
    LaunchedEffect(controlsVisible, lastInteractionTime, playerState.isPlaying) {
        if (controlsVisible && playerState.isPlaying) {
            delay(4000)
            if (System.currentTimeMillis() - lastInteractionTime >= 3800) {
                controlsVisible = false
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        var dragStartX by remember { mutableFloatStateOf(0f) }
        var accumulatedDrag by remember { mutableFloatStateOf(0f) }
        val stepPixels = 80f

        val adjustVolume: (Int) -> Unit = { delta ->
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream)
            val currentVol = audioManager.getStreamVolume(stream)
            val newVol = (currentVol + delta).coerceIn(0, maxVol)
            audioManager.setStreamVolume(stream, newVol, 0)
            volumeOverlayPercent = (newVol * 100 / maxVol).coerceIn(0, 100)
            showVolumeOverlay = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> dragStartX = offset.x; accumulatedDrag = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            accumulatedDrag += dragAmount
                            // Swipe up (negative) -> increase, swipe down (positive) -> decrease.
                            while (accumulatedDrag <= -stepPixels) {
                                accumulatedDrag += stepPixels
                                if (dragStartX < widthPx / 2) {
                                    val brightness = onBrightnessUp()
                                    brightnessOverlayPercent = (brightness * 100).toInt().coerceIn(0, 100)
                                    showBrightnessOverlay = true
                                } else {
                                    adjustVolume(1)
                                }
                            }
                            while (accumulatedDrag >= stepPixels) {
                                accumulatedDrag -= stepPixels
                                if (dragStartX < widthPx / 2) {
                                    val brightness = onBrightnessDown()
                                    brightnessOverlayPercent = (brightness * 100).toInt().coerceIn(0, 100)
                                    showBrightnessOverlay = true
                                } else {
                                    adjustVolume(-1)
                                }
                            }
                        },
                        onDragEnd = { accumulatedDrag = 0f }
                    )
                }
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
                            if (playerState.error != null) {
                                viewModel.retry()
                            } else {
                                controlsVisible = !controlsVisible
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        }
                    }
                )
            }
    ) {
        // VLC SurfaceView
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { viewModel.playerWrapper.attachViews(it) }
            },
            modifier = Modifier.fillMaxSize()
        )

        // shortcut: plain pill until the 1.2.0 error overlay (Retry/Back buttons) lands.
        OsdPill(
            visible = playerState.error != null,
            text = "${playerState.error ?: ""}. Tap to retry",
            modifier = Modifier.align(Alignment.Center)
        )

        OsdPill(
            visible = showSpeed && !isInPiP,
            text = "2x Speed",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )

        OsdPill(
            visible = showVolumeOverlay && !isInPiP,
            text = "Volume $volumeOverlayPercent%",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )

        OsdPill(
            visible = showBrightnessOverlay && !isInPiP,
            text = "Brightness $brightnessOverlayPercent%",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )

        PlayerControls(
            playerState = playerState,
            visible = controlsVisible && !isInPiP,
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
            },
            playbackSpeed = playerState.playbackRate,
            title = title,
            onSpeedClick = {
                viewModel.cyclePlaybackSpeed()
                lastInteractionTime = System.currentTimeMillis()
            }
        )

        if (showTrackSheet && !isInPiP) {
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
                audioDelayMs = playerState.audioDelayMs,
                onAudioDelayChange = { viewModel.setAudioDelay(it) },
                onDismiss = { showTrackSheet = false }
            )
        }
    }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.savePositionNow()
            viewModel.playerWrapper.detachViews()
        }
    }
}
