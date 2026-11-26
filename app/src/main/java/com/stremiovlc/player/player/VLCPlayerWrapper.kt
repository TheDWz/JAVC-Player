package com.stremiovlc.player.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.stremiovlc.player.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

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
    val playbackRate: Float = 1.0f,
    /** User-set A/V offset; positive delays audio. Kept across media so a Bluetooth offset sticks. */
    val audioDelayMs: Long = 0L,
    /** Display size of the video (sample aspect applied), 0 until known. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Set when playback stopped because of a problem (dead stream, unplayable file); shown with a retry. */
    val error: String? = null
)

/**
 * VLC also reports "end reached" when a network stream simply drops (Wi-Fi loss, expired debrid link),
 * so only treat it as finished when playback got to within a minute (or 5%) of the end.
 */
internal fun isRealEnd(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return true // unknown length (live): nothing to compare against
    return durationMs - positionMs <= minOf(60_000L, durationMs / 20)
}

class VLCPlayerWrapper(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val libVLC: LibVLC
    private val mediaPlayer: MediaPlayer

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    // True from loadMedia() until the new media reports Playing; guards against late events
    // from the previous media (VLC delivers events asynchronously).
    @Volatile
    private var awaitingPlayback = false

    // Resume point applied on the first Playing event: seeking earlier is often ignored, and the
    // :start-time option decodes from a non-keyframe (seconds of corrupted picture).
    private var pendingStartMs = 0L

    // Set when a transient focus loss (e.g. a notification sound, a call) paused us, so we resume after.
    private var pausedForFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocusLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_state.value.isPlaying) {
                    pausedForFocusLoss = true
                    mediaPlayer.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedForFocusLoss) {
                    pausedForFocusLoss = false
                    mediaPlayer.play()
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks for us on API 26+.
        }
    }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else null

    // Headphones unplugged / Bluetooth disconnected: pause instead of blasting the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        val args = arrayListOf("--network-caching=1500", "--avcodec-skiploopfilter", "1")
        if (BuildConfig.DEBUG) args += "-vv"
        libVLC = LibVLC(appContext, args)
        mediaPlayer = MediaPlayer(libVLC)

        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (pendingStartMs > 0L) {
                        mediaPlayer.time = pendingStartMs
                        pendingStartMs = 0L
                    }
                    awaitingPlayback = false
                    refreshTracks()
                    // VLC resets the delay per media; re-apply the user's offset.
                    mediaPlayer.setAudioDelay(_state.value.audioDelayMs * 1000)
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
                    // Ignore a late end event from the previous media.
                    if (awaitingPlayback) return@setEventListener
                    abandonFocus()
                    val s = _state.value
                    _state.value = if (isRealEnd(s.currentTimeMs, s.durationMs)) {
                        s.copy(isPlaying = false, isEnded = true)
                    } else {
                        s.copy(isPlaying = false, isBuffering = false, error = "Stream lost")
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    abandonFocus()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        error = "Couldn't play this stream"
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
                    if (awaitingPlayback) return@setEventListener
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
                MediaPlayer.Event.Vout -> {
                    refreshVideoSize()
                }
            }
        }
    }

    /** VLCVideoLayout sizes the video and subtitle surfaces itself, including PiP resizes. */
    fun attachViews(layout: VLCVideoLayout) {
        mediaPlayer.attachViews(layout, null, true, false)
    }

    fun detachViews() {
        mediaPlayer.detachViews()
    }

    fun loadMedia(uri: Uri, startMs: Long = 0L) {
        awaitingPlayback = true
        // Drop stale state (time, duration, ended flag, tracks) from any previous media so
        // it cannot be saved or deleted against the newly loaded URI.
        _state.value = PlayerState(
            playbackRate = _state.value.playbackRate,
            audioDelayMs = _state.value.audioDelayMs
        )
        val media = Media(libVLC, uri)
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        pendingStartMs = startMs
        mediaPlayer.media = media
        media.release()
    }

    fun play() {
        requestFocus()
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
        val length = mediaPlayer.length
        val target = mediaPlayer.time + ms
        mediaPlayer.time = if (length > 0L) target.coerceAtMost(length) else target
    }

    fun skipBackward(ms: Long = 10_000L) {
        val newTime = (mediaPlayer.time - ms).coerceAtLeast(0L)
        mediaPlayer.time = newTime
    }

    fun setRate(rate: Float) {
        mediaPlayer.setRate(rate)
        _state.value = _state.value.copy(playbackRate = rate)
    }

    fun getRate(): Float = mediaPlayer.rate

    fun setAudioDelay(delayMs: Long) {
        mediaPlayer.setAudioDelay(delayMs * 1000) // VLC takes microseconds
        _state.value = _state.value.copy(audioDelayMs = delayMs)
    }

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

    fun stop() {
        mediaPlayer.stop()
    }

    fun release() {
        abandonFocus()
        appContext.unregisterReceiver(noisyReceiver)
        mediaPlayer.release()
        libVLC.release()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun refreshVideoSize() {
        val track = mediaPlayer.currentVideoTrack ?: return
        if (track.width <= 0 || track.height <= 0) return
        val sar = if (track.sarNum > 0 && track.sarDen > 0) track.sarNum.toFloat() / track.sarDen else 1f
        _state.value = _state.value.copy(
            videoWidth = (track.width * sar).toInt(),
            videoHeight = track.height
        )
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
        refreshVideoSize()
    }
}
