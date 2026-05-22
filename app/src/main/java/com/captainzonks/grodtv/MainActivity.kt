package com.captainzonks.grodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.captainzonks.grodtv.api.ApiService
import com.captainzonks.grodtv.player.PlayerService
import com.captainzonks.grodtv.ui.GrodTvNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Touch the player controller so ExoPlayer is created on the main thread
        // before any service binds to it.
        appContainer.playerController

        PlayerService.start(this)
        ApiService.start(this)

        setContent {
            GrodTvNav()
        }
    }
}
