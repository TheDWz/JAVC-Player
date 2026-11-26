package com.stremiovlc.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.stremiovlc.player.player.PlayerState
import kotlinx.coroutines.launch
import com.stremiovlc.player.ui.PlayerScreen
import com.stremiovlc.player.viewmodel.PlayerViewModel

private const val TAG = "JAVC"

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PlayerViewModel
    private var pipParams: PictureInPictureParams? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        PipActionHandler.onTogglePlay = { viewModel.togglePlayPause() }

        // Configure PiP parameters once, including auto-enter and actions on supported versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipParamsWithActions()
        }

        val intentUri = extractUri(intent)
        logIntent(intent)
        trackResult()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val currentUri by viewModel.currentUri.collectAsState()

                if (currentUri != null) {
                    PlayerScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                        onBrightnessUp = { increaseBrightness() },
                        onBrightnessDown = { decreaseBrightness() }
                    )
                } else {
                    UrlEntryScreen(onPlay = { uri ->
                        viewModel.loadAndPlay(uri)
                    })
                }
            }
        }

        // Skip on recreation (e.g. rotation): the ViewModel already holds this media.
        if (intentUri != null && viewModel.currentUri.value != intentUri.toString()) {
            viewModel.loadAndPlay(intentUri, callerPosition(intent), intent.getStringExtra("title"))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logIntent(intent)
        val uri = extractUri(intent)
        if (uri != null) {
            viewModel.playerWrapper.stop()
            viewModel.loadAndPlay(uri, callerPosition(intent), intent.getStringExtra("title"))
        }
    }

    override fun onPause() {
        super.onPause()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = viewModel.playerState.value
            if (state.isPlaying && !isFinishing) {
                // Enter PiP when the system is about to pause us (for older devices or when auto-enter
                // doesn't trigger). On newer versions auto-enter should normally handle Home/Recents.
                val params = pipParams
                if (params != null && !isInPictureInPictureMode) {
                    enterPictureInPictureMode(params)
                }
            }
        }

        viewModel.savePositionNow()
        publishResult(viewModel.playerState.value)
    }

    override fun onResume() {
        super.onResume()
        // When coming back from background / PiP, make sure VLC
        // is still attached to the existing SurfaceViews and that
        // the video output is sized to the full-screen surface.
        viewModel.playerWrapper.ensureSurfacesAttached()
        window.decorView.post {
            viewModel.playerWrapper.updateWindowSize()
            // Nudge LibVLC to resync A/V after returning from PiP/background.
            viewModel.playerWrapper.resyncPosition()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.savePositionNow()
        publishResult(viewModel.playerState.value)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // This callback is invoked when the user is leaving the activity
        // via Home/Recents; it's the recommended place to enter PiP.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = viewModel.playerState.value
            if (state.isPlaying && !isInPictureInPictureMode) {
                val params = pipParams
                if (params != null) {
                    enterPictureInPictureMode(params)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPictureInPictureMode(isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            // When entering PiP, update the window size once the PiP layout has settled
            // so the video scales and centers correctly inside the PiP window.
            window.decorView.post {
                viewModel.playerWrapper.updateWindowSize()
                window.decorView.postDelayed({
                    viewModel.playerWrapper.updateWindowSize()
                    // After PiP has fully settled, resync A/V at the current timestamp.
                    viewModel.playerWrapper.resyncPosition()
                }, 150)
            }
        }
        // When leaving PiP we don't touch the window size here; instead we correct it
        // in onResume where the full-screen surface size is known.
    }

    /**
     * Stremio and Nuvio read watch progress from the activity result. Keep it current so every
     * exit path (Back, PiP dismissed, process killed) reports where playback got to.
     */
    private fun trackResult() {
        var lastPublishedMs = -1L
        lifecycleScope.launch {
            viewModel.playerState.collect { state ->
                if (state.isEnded) {
                    publishResult(state)
                    // Hand control back so the caller can mark it watched / offer the next episode.
                    if (intent?.action == Intent.ACTION_VIEW) finish()
                } else if (state.currentTimeMs > 0 &&
                    (lastPublishedMs < 0 || kotlin.math.abs(state.currentTimeMs - lastPublishedMs) >= 5_000)
                ) {
                    lastPublishedMs = state.currentTimeMs
                    publishResult(state)
                }
            }
        }
    }

    private fun publishResult(state: PlayerState) {
        if (state.durationMs <= 0) return
        val completed = state.isEnded
        // Callers such as Nuvio drop results without a position, so a finished video reports its full length.
        val position = if (completed) state.durationMs else state.currentTimeMs
        val result = Intent("com.mxtech.intent.result.VIEW").apply {
            // MX Player keys are ints, VLC keys are longs; callers read them with typed getters.
            putExtra("position", position.toInt())
            putExtra("duration", state.durationMs.toInt())
            putExtra("end_by", if (completed) "playback_completion" else "user")
            putExtra("extra_position", position)
            putExtra("extra_duration", state.durationMs)
            data = intent?.data
        }
        setResult(RESULT_OK, result)
        Log.d(TAG, "result position=$position duration=${state.durationMs} completed=$completed caller=$callingActivity")
    }

    private fun callerPosition(intent: Intent): Long {
        val extras = intent.extras ?: return 0L
        for (key in listOf("extra_position", "position", "startfrom")) {
            val ms = (extras.get(key) as? Number)?.toLong() ?: continue
            if (ms > 0L) return ms
        }
        return 0L
    }

    private fun logIntent(intent: Intent?) {
        val extras = intent?.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }
        Log.d(TAG, "intent action=${intent?.action} data=${intent?.data} caller=$callingActivity extras=[$extras]")
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent?.action == Intent.ACTION_VIEW) {
            return intent.data
        }
        return null
    }

    private fun updatePipParamsWithActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }

        // Single pause action for PiP; it toggles playback via PipActionHandler.
        val intent = Intent(this, PipActionReceiver::class.java).setAction(PipActionHandler.ACTION_TOGGLE_PLAY)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)

        val icon = Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        val action = RemoteAction(icon, "Pause", "Pause playback", pendingIntent)

        builder.setActions(listOf(action))

        val params = builder.build()
        pipParams = params
        setPictureInPictureParams(params)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private var currentBrightness = 0.5f

    fun increaseBrightness() {
        currentBrightness = (currentBrightness + 0.05f).coerceIn(0.1f, 1f)
        applyBrightness()
    }

    fun decreaseBrightness() {
        currentBrightness = (currentBrightness - 0.05f).coerceIn(0.1f, 1f)
        applyBrightness()
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply { screenBrightness = currentBrightness }
    }
}

@Composable
private fun UrlEntryScreen(onPlay: (Uri) -> Unit) {
    var url by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "JAVC Player",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter a stream URL to play, or open a video from Stremio",
                color = Color(0xAAFFFFFF),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Stream URL") },
                placeholder = { Text("https://...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color(0x55FFFFFF),
                    focusedLabelColor = Color(0xFF6C63FF),
                    cursorColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        onPlay(Uri.parse(url.trim()))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6C63FF)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Play", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
