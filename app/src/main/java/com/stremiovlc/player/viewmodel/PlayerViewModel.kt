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

    private val app = application as PlayerApplication
    private val dao = app.database.playbackPositionDao()

    private val _currentUri = MutableStateFlow<String?>(null)
    val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _showSpeedIndicator = MutableStateFlow(false)
    val showSpeedIndicator: StateFlow<Boolean> = _showSpeedIndicator.asStateFlow()

    private val _isInPictureInPictureMode = MutableStateFlow(false)
    val isInPictureInPictureMode: StateFlow<Boolean> = _isInPictureInPictureMode.asStateFlow()

    val playerState: StateFlow<PlayerState> = playerWrapper.state

    private var positionSaveJob: Job? = null
    private var lastSavedTimeMs = 0L
    private var basePlaybackRate = 1.0f
    private var isSpeedHoldActive = false

    private val availablePlaybackRates = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    private var loadJob: Job? = null

    /** [callerPositionMs] is the resume point sent by Stremio/Nuvio; it wins over our own DB. */
    fun loadAndPlay(uri: Uri, callerPositionMs: Long = 0L, title: String? = null) {
        val uriString = uri.toString()
        _currentUri.value = uriString
        _title.value = title
        lastSavedTimeMs = 0L
        positionSaveJob?.cancel()

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val startMs = if (callerPositionMs > 0L) callerPositionMs else savedResumePosition(uriString)
            playerWrapper.loadMedia(uri, startMs)
            playerWrapper.play()
            startPositionTracking(uriString)
        }
    }

    private suspend fun savedResumePosition(uriString: String): Long {
        val saved = dao.getPosition(uriString) ?: return 0L
        val nearEnd = saved.durationMs > 0 && saved.positionMs.toFloat() / saved.durationMs >= 0.95f
        return if (nearEnd) 0L else saved.positionMs
    }

    private fun startPositionTracking(uriString: String) {
        positionSaveJob?.cancel()
        positionSaveJob = viewModelScope.launch {
            playerWrapper.state.collect { state ->
                if (state.isEnded) {
                    // The activity finishes on end, so don't let viewModelScope's cancellation drop this.
                    app.appScope.launch { dao.deletePosition(uriString) }
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
        // While the new media is still loading, state still belongs to the previous media.
        if (loadJob?.isActive == true) return
        val state = playerWrapper.state.value
        if (!state.isEnded && state.currentTimeMs > 0 && state.durationMs > 0) {
            app.appScope.launch {
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
        isSpeedHoldActive = true
        playerWrapper.setRate(2.0f)
        _showSpeedIndicator.value = true
    }

    fun onSpeedHoldEnd() {
        isSpeedHoldActive = false
        playerWrapper.setRate(basePlaybackRate)
        _showSpeedIndicator.value = false
    }

    fun cyclePlaybackSpeed() {
        val currentBaseIndex = availablePlaybackRates.indexOfFirst { it == basePlaybackRate }
        val currentIndex = if (currentBaseIndex == -1) {
            availablePlaybackRates.indexOfFirst { it == playerWrapper.getRate() }.takeIf { it != -1 } ?: 1
        } else {
            currentBaseIndex
        }

        val nextIndex = (currentIndex + 1) % availablePlaybackRates.size
        val newRate = availablePlaybackRates[nextIndex]

        basePlaybackRate = newRate

        if (!isSpeedHoldActive) {
            playerWrapper.setRate(newRate)
        }
    }

    fun togglePlayPause() = playerWrapper.togglePlayPause()
    fun seekTo(timeMs: Long) = playerWrapper.seekTo(timeMs)
    fun skipForward() = playerWrapper.skipForward()
    fun skipBackward() = playerWrapper.skipBackward()
    fun setAudioTrack(id: Int) = playerWrapper.setAudioTrack(id)
    fun setSubtitleTrack(id: Int) = playerWrapper.setSubtitleTrack(id)
    fun setChapter(index: Int) = playerWrapper.setChapter(index)

    fun setPictureInPictureMode(inPiP: Boolean) {
        _isInPictureInPictureMode.value = inPiP
    }

    override fun onCleared() {
        super.onCleared()
        positionSaveJob?.cancel()
        playerWrapper.stop()
        playerWrapper.release()
    }
}
