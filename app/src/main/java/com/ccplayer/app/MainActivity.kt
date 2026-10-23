package com.ccplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.media3.common.util.UnstableApi
import com.ccplayer.app.player.PlayerScreen
import com.ccplayer.app.player.PlayerViewModel
import com.ccplayer.app.ui.theme.CCPlayerTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CCPlayerTheme {
                PlayerScreen(viewModel = playerViewModel)
            }
        }
    }
}
