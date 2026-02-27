package com.stremiovlc.player

import android.app.Application
import com.stremiovlc.player.data.AppDatabase

class PlayerApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
