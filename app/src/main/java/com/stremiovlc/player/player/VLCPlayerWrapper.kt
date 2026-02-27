package com.stremiovlc.player.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.ViewTreeObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

data class TrackInfo(
    val id: Int,
    val name: String
)

data class ChapterInfo(
    val index: Int,
    val name: String,
    val timeOffsetMs: Long,
    val durationMs: Long
)

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferingPercent: Float = 100f,
    val isBuffering: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val chapters: List<ChapterInfo> = emptyList(),
    val currentAudioTrackId: Int = -1,
    val currentSubtitleTrackId: Int = -1,
    val currentChapterIndex: Int = -1,
    val isSeekable: Boolean = false,
    val isEnded: Boolean = false,
    val playbackRate: Float = 1.0f
)

class VLCPlayerWrapper(context: Context) {

    // Tunable extra Bluetooth latency added on top of pipeline latency (in ms).
    private companion object {
        const val BT_EXTRA_MS = 150.0
        const val PLAYING_DELAY_MS = 100L
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val libVLC: LibVLC
    private val mediaPlayer: MediaPlayer

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var surfaceView: SurfaceView? = null
    private var subtitleSurfaceView: SurfaceView? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateBluetoothAudioDelay()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateBluetoothAudioDelay()
        }
    }

    init {
        val args = arrayListOf(
            "--network-caching=1500",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-skiploopfilter", "1",
            "--avcodec-skip-frame", "0",
            "--avcodec-skip-idct", "0",
            "--android-display-chroma", "RV32",
            "--audio-resampler", "soxr",
            "--stats",
            "-vv"
        )
        libVLC = LibVLC(appContext, args)
        mediaPlayer = MediaPlayer(libVLC)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    refreshTracks()
                    updateBluetoothAudioDelay(fromPlayingEvent = true)
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        isEnded = false,
                        isBuffering = false
                    )
                }
                MediaPlayer.Event.Paused -> {
                    _state.value = _state.value.copy(isPlaying = false)
                }
                MediaPlayer.Event.Stopped -> {
                    _state.value = _state.value.copy(isPlaying = false)
                }
                MediaPlayer.Event.EndReached -> {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        isEnded = true
                    )
                }
                MediaPlayer.Event.Buffering -> {
                    val pct = event.buffering
                    _state.value = _state.value.copy(
                        bufferingPercent = pct,
                        isBuffering = pct < 100f
                    )
                }
                MediaPlayer.Event.TimeChanged -> {
                    _state.value = _state.value.copy(
                        currentTimeMs = event.timeChanged,
                        currentChapterIndex = mediaPlayer.chapter
                    )
                }
                MediaPlayer.Event.LengthChanged -> {
                    _state.value = _state.value.copy(
                        durationMs = event.lengthChanged
                    )
                }
                MediaPlayer.Event.SeekableChanged -> {
                    _state.value = _state.value.copy(
                        isSeekable = event.seekable
                    )
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> {
                    refreshTracks()
                }
            }
        }
    }

    fun attachSurfaces(video: SurfaceView, subtitle: SurfaceView) {
        surfaceView = video
        subtitleSurfaceView = subtitle

        val vlcVout: IVLCVout = mediaPlayer.vlcVout
        if (!vlcVout.areViewsAttached()) {
            vlcVout.setVideoView(video)
            vlcVout.setSubtitlesView(subtitle)

            // Ensure LibVLC knows the actual surface size so it can
            // center the video correctly inside the view.
            val vto: ViewTreeObserver = video.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (video.width > 0 && video.height > 0) {
                            vlcVout.setWindowSize(video.width, video.height)
                            video.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                })
            }

            // Let VLC choose the best scaling/aspect for the surface.
            mediaPlayer.setScale(0f)
            mediaPlayer.setAspectRatio(null)

            vlcVout.attachViews()
        }
    }

    /**
     * Ensures that the video/subtitle surfaces are attached to LibVLC.
     * Useful after returning from background / PiP when Vout may have been dropped.
     */
    fun ensureSurfacesAttached() {
        val video = surfaceView
        val subtitle = subtitleSurfaceView
        if (video != null && subtitle != null) {
            val vlcVout: IVLCVout = mediaPlayer.vlcVout
            if (!vlcVout.areViewsAttached()) {
                vlcVout.setVideoView(video)
                vlcVout.setSubtitlesView(subtitle)
                // We don't need to reset window size here; LibVLC will update on next layout.
                mediaPlayer.setScale(0f)
                mediaPlayer.setAspectRatio(null)
                vlcVout.attachViews()
            }
        }
    }

    /**
     * Updates LibVLC's output window size to match the current surface dimensions.
     * Call this when the surface is resized (e.g. entering or leaving PiP) so video
     * scales to fit and stays centered.
     */
    fun updateWindowSize() {
        val video = surfaceView ?: return
        val vlcVout: IVLCVout = mediaPlayer.vlcVout
        if (!vlcVout.areViewsAttached()) return
        if (video.width > 0 && video.height > 0) {
            vlcVout.setWindowSize(video.width, video.height)
            mediaPlayer.setScale(0f)
            mediaPlayer.setAspectRatio(null)
        }
    }

    fun detachSurfaces() {
        val vlcVout = mediaPlayer.vlcVout
        if (vlcVout.areViewsAttached()) {
            vlcVout.detachViews()
        }
        surfaceView = null
        subtitleSurfaceView = null
    }

    fun loadMedia(uri: Uri) {
        val media = Media(libVLC, uri)
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        mediaPlayer.media = media
        media.release()
    }

    fun play() {
        mediaPlayer.play()
    }

    fun pause() {
        mediaPlayer.pause()
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun seekTo(timeMs: Long) {
        mediaPlayer.time = timeMs
    }

    fun skipForward(ms: Long = 10_000L) {
        val newTime = (mediaPlayer.time + ms).coerceAtMost(mediaPlayer.length)
        mediaPlayer.time = newTime
    }

    fun skipBackward(ms: Long = 10_000L) {
        val newTime = (mediaPlayer.time - ms).coerceAtLeast(0L)
        mediaPlayer.time = newTime
    }

    /**
     * Attempts to resynchronize audio/video by seeking to the current playback time.
     * Useful after mode changes like entering or exiting PiP.
     */
    fun resyncPosition() {
        val current = mediaPlayer.time
        if (current > 0L) {
            mediaPlayer.time = current
        }
    }

    fun setRate(rate: Float) {
        mediaPlayer.setRate(rate)
        _state.value = _state.value.copy(playbackRate = rate)
    }

    fun getRate(): Float = mediaPlayer.rate

    fun setAudioTrack(trackId: Int) {
        mediaPlayer.setAudioTrack(trackId)
        _state.value = _state.value.copy(currentAudioTrackId = trackId)
    }

    fun setSubtitleTrack(trackId: Int) {
        mediaPlayer.setSpuTrack(trackId)
        _state.value = _state.value.copy(currentSubtitleTrackId = trackId)
    }

    fun setChapter(index: Int) {
        mediaPlayer.chapter = index
        _state.value = _state.value.copy(currentChapterIndex = index)
    }

    fun getCurrentTimeMs(): Long = mediaPlayer.time

    fun getDurationMs(): Long = mediaPlayer.length

    fun stop() {
        mediaPlayer.stop()
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaPlayer.release()
        libVLC.release()
    }

    private fun updateBluetoothAudioDelay(fromPlayingEvent: Boolean = false) {
        val delayMs = if (fromPlayingEvent) PLAYING_DELAY_MS else 0L

        val runnable = Runnable {
            updateBluetoothAudioDelayInternal()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (delayMs > 0L) {
                mainHandler.postDelayed(runnable, delayMs)
            } else {
                runnable.run()
            }
        } else {
            if (delayMs > 0L) {
                mainHandler.postDelayed(runnable, delayMs)
            } else {
                mainHandler.post(runnable)
            }
        }
    }

    private fun updateBluetoothAudioDelayInternal() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val hasBluetoothOutput = devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
                else -> false
            }
        }

        if (!hasBluetoothOutput) {
            mediaPlayer.setAudioDelay(0L)
            return
        }

        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 48_000

        val framesPerBuffer = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 256

        val baseLatencyMs = framesPerBuffer * 1000.0 / sampleRate.toDouble()
        val totalLatencyMs = baseLatencyMs + BT_EXTRA_MS
        val latencyUs = (totalLatencyMs * 1000.0).toLong()

        mediaPlayer.setAudioDelay(-latencyUs)
    }

    private fun refreshTracks() {
        val audioTracks = mediaPlayer.audioTracks?.map { desc ->
            TrackInfo(id = desc.id, name = desc.name ?: "Track ${desc.id}")
        } ?: emptyList()

        val subtitleTracks = mediaPlayer.spuTracks?.map { desc ->
            TrackInfo(id = desc.id, name = desc.name ?: "Track ${desc.id}")
        } ?: emptyList()

        val chapterArray = mediaPlayer.getChapters(-1)
        val chapters = chapterArray?.mapIndexed { index, ch ->
            ChapterInfo(
                index = index,
                name = ch.name ?: "Chapter ${index + 1}",
                timeOffsetMs = ch.timeOffset,
                durationMs = ch.duration
            )
        } ?: emptyList()

        _state.value = _state.value.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            chapters = chapters,
            currentAudioTrackId = mediaPlayer.audioTrack,
            currentSubtitleTrackId = mediaPlayer.spuTrack,
            currentChapterIndex = mediaPlayer.chapter
        )
    }
}
