package com.ccplayer.app.player

import android.graphics.Color
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.ccplayer.media.android.playback.PlaybackEngine

@UnstableApi
@Composable
fun EngineVideoSurface(
    engine: PlaybackEngine,
    aspectRatio: Float?,
    resizeMode: VideoResizeMode,
    keepScreenOn: Boolean,
    modifier: Modifier = Modifier,
) {
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val surface = SurfaceView(context).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            surfaceView = surface
            AspectRatioFrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface)
            }
        },
        update = { layout ->
            layout.setAspectRatio(aspectRatio ?: 0f)
            layout.resizeMode = when (resizeMode) {
                VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                VideoResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            surfaceView?.keepScreenOn = keepScreenOn
        },
    )

    surfaceView?.let { surface ->
        DisposableEffect(engine, surface) {
            engine.attach(surface)
            onDispose { engine.detach(surface) }
        }
    }
}
