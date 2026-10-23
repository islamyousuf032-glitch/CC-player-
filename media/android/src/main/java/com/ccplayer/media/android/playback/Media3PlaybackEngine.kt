package com.ccplayer.media.android.playback

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SeekParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.media.android.color.ColorGradeEffects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
class Media3PlaybackEngine(context: Context) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            DefaultRenderersFactory(appContext).setEnableDecoderFallback(true),
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2_000,
                    /* maxBufferMs = */ 8_000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_000,
                )
                .build(),
        )
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(PlayerListener())
        }

    private val positionUpdater = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    init {
        handler.post(positionUpdater)
    }

    override fun attach(surfaceView: SurfaceView) = player.setVideoSurfaceView(surfaceView)

    override fun detach(surfaceView: SurfaceView) = player.clearVideoSurfaceView(surfaceView)

    override fun open(uri: Uri) {
        mutableState.value = PlaybackSnapshot(status = PlaybackStatus.LOADING)
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }.onFailure { error ->
            setError(error.message ?: "Unable to open this media")
        }
    }

    override fun clear() {
        player.stop()
        player.clearMediaItems()
        mutableState.value = PlaybackSnapshot()
    }

    override fun playPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
        refresh()
    }

    override fun pause() {
        player.pause()
        refresh()
    }

    override fun seekBy(deltaMs: Long) {
        val target = player.currentPosition + deltaMs
        player.seekTo(target.coerceIn(0, player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE))
        refresh()
    }

    override fun beginScrubbing() {
        player.setScrubbingModeEnabled(true)
    }

    override fun scrubTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        refresh()
    }

    override fun endScrubbing(positionMs: Long) {
        player.setScrubbingModeEnabled(false)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(positionMs.coerceAtLeast(0))
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        refresh()
    }

    override fun setSpeed(speed: Float) {
        require(speed in 0.25f..4f) { "Speed must be between 0.25 and 4" }
        player.setPlaybackSpeed(speed)
        refresh()
    }

    override fun setRepeat(repeat: Boolean) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        refresh()
    }

    override fun setColorGrade(grade: ColorGrade) {
        player.setVideoEffects(ColorGradeEffects.create(grade))
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        player.release()
    }

    private fun refresh() {
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val status = when {
            mutableState.value.status == PlaybackStatus.ERROR -> PlaybackStatus.ERROR
            player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE -> PlaybackStatus.LOADING
            player.currentMediaItem == null -> PlaybackStatus.IDLE
            else -> PlaybackStatus.READY
        }
        mutableState.value = mutableState.value.copy(
            status = status,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            speed = player.playbackParameters.speed,
            repeat = player.repeatMode == Player.REPEAT_MODE_ONE,
        )
    }

    private fun setError(message: String) {
        mutableState.value = mutableState.value.copy(
            status = PlaybackStatus.ERROR,
            isPlaying = false,
            errorMessage = message,
        )
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            mutableState.value = mutableState.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
                pixelAspectRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0 } ?: 1f,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            val detail = buildString {
                append("Android's codec pipeline could not play this stream (")
                append(error.errorCodeName)
                append(").")
                if (error.cause?.message?.isNotBlank() == true) {
                    append(' ')
                    append(error.cause?.message)
                }
            }
            setError(detail)
        }
    }

    private companion object {
        const val POSITION_UPDATE_MS = 100L
    }
}
