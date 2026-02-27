package com.stremiovlc.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object PipActionHandler {
    const val ACTION_TOGGLE_PLAY = "com.stremiovlc.player.PIP_TOGGLE_PLAY"

    @Volatile
    var onTogglePlay: (() -> Unit)? = null
}

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == PipActionHandler.ACTION_TOGGLE_PLAY) {
            PipActionHandler.onTogglePlay?.invoke()
        }
    }
}

