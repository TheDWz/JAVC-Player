package com.stremiovlc.player.player

import android.content.Context
import android.net.Uri
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
    val isEnded: Boolean = false
)

class VLCPlayerWrapper(context: Context) {

    private val libVLC: LibVLC
    private val mediaPlayer: MediaPlayer

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var surfaceView: SurfaceView? = null
    private var subtitleSurfaceView: SurfaceView? = null

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
        libVLC = LibVLC(context.applicationContext, args)
        mediaPlayer = MediaPlayer(libVLC)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    refreshTracks()
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

    fun setRate(rate: Float) {
        mediaPlayer.setRate(rate)
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
        mediaPlayer.release()
        libVLC.release()
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
