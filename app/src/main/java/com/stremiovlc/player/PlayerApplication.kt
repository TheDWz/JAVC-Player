package com.stremiovlc.player

import android.app.Application
import com.stremiovlc.player.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayerApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    /** For DB writes that must outlive the screen (e.g. the final save when the player closes). */
    val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            database.playbackPositionDao()
                .pruneOldEntries(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)
        }
    }
}
