package com.stremiovlc.player.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremiovlc.player.PlayerApplication
import com.stremiovlc.player.data.PlaybackPositionEntity
import com.stremiovlc.player.player.PlayerState
import com.stremiovlc.player.player.VLCPlayerWrapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playerWrapper = VLCPlayerWrapper(application)

    private val dao = (application as PlayerApplication).database.playbackPositionDao()

    private val _currentUri = MutableStateFlow<String?>(null)
    val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _showSpeedIndicator = MutableStateFlow(false)
    val showSpeedIndicator: StateFlow<Boolean> = _showSpeedIndicator.asStateFlow()

    val playerState: StateFlow<PlayerState> = playerWrapper.state

    private var positionSaveJob: Job? = null
    private var lastSavedTimeMs = 0L
    private var resumeApplied = false

    fun loadAndPlay(uri: Uri) {
        val uriString = uri.toString()
        _currentUri.value = uriString
        resumeApplied = false

        playerWrapper.loadMedia(uri)
        playerWrapper.play()

        startPositionTracking(uriString)
        checkResume(uriString)
    }

    private fun checkResume(uriString: String) {
        viewModelScope.launch {
            val saved = dao.getPosition(uriString) ?: return@launch
            if (saved.durationMs > 0 && saved.positionMs.toFloat() / saved.durationMs < 0.95f) {
                playerWrapper.seekTo(saved.positionMs)
                resumeApplied = true
            }
        }
    }

    private fun startPositionTracking(uriString: String) {
        positionSaveJob?.cancel()
        positionSaveJob = viewModelScope.launch {
            playerWrapper.state.collect { state ->
                if (state.isEnded) {
                    dao.deletePosition(uriString)
                    return@collect
                }

                if (state.currentTimeMs > 0 && state.durationMs > 0) {
                    val elapsed = state.currentTimeMs - lastSavedTimeMs
                    if (elapsed >= 5000 || elapsed < 0) {
                        lastSavedTimeMs = state.currentTimeMs
                        dao.savePosition(
                            PlaybackPositionEntity(
                                uri = uriString,
                                positionMs = state.currentTimeMs,
                                durationMs = state.durationMs,
                                lastPlayed = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    fun savePositionNow() {
        val uriString = _currentUri.value ?: return
        val state = playerWrapper.state.value
        if (state.currentTimeMs > 0 && state.durationMs > 0) {
            viewModelScope.launch {
                dao.savePosition(
                    PlaybackPositionEntity(
                        uri = uriString,
                        positionMs = state.currentTimeMs,
                        durationMs = state.durationMs,
                        lastPlayed = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun onSpeedHoldStart() {
        playerWrapper.setRate(2.0f)
        _showSpeedIndicator.value = true
    }

    fun onSpeedHoldEnd() {
        playerWrapper.setRate(1.0f)
        _showSpeedIndicator.value = false
    }

    fun togglePlayPause() = playerWrapper.togglePlayPause()
    fun seekTo(timeMs: Long) = playerWrapper.seekTo(timeMs)
    fun skipForward() = playerWrapper.skipForward()
    fun skipBackward() = playerWrapper.skipBackward()
    fun setAudioTrack(id: Int) = playerWrapper.setAudioTrack(id)
    fun setSubtitleTrack(id: Int) = playerWrapper.setSubtitleTrack(id)
    fun setChapter(index: Int) = playerWrapper.setChapter(index)

    override fun onCleared() {
        super.onCleared()
        positionSaveJob?.cancel()
        playerWrapper.stop()
        playerWrapper.release()
    }
}
