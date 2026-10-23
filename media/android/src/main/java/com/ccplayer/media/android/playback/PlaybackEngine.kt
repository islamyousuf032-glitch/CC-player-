package com.ccplayer.media.android.playback

import android.net.Uri
import android.view.SurfaceView
import com.ccplayer.core.media.color.ColorGrade
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

enum class PlaybackStatus { IDLE, LOADING, READY, ENDED, ERROR }

data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val pixelAspectRatio: Float = 1f,
    val errorMessage: String? = null,
) {
    val aspectRatio: Float?
        get() = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth * pixelAspectRatio / videoHeight
        } else {
            null
        }
}

/** Surface-based boundary: a native FFmpeg/RAW engine can be added without changing Compose UI. */
interface PlaybackEngine : Closeable {
    val state: StateFlow<PlaybackSnapshot>

    fun attach(surfaceView: SurfaceView)
    fun detach(surfaceView: SurfaceView)
    fun open(uri: Uri)
    fun clear()
    fun playPause()
    fun pause()
    fun seekBy(deltaMs: Long)
    fun beginScrubbing()
    fun scrubTo(positionMs: Long)
    fun endScrubbing(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setRepeat(repeat: Boolean)
    fun setColorGrade(grade: ColorGrade)
}
