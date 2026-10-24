package com.ccplayer.media.ffmpeg

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.core.media.color.ColorGradeMatrix
import com.ccplayer.media.android.playback.PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackSnapshot
import com.ccplayer.media.android.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Surface/AudioTrack playback backed by the same FFmpeg and linear-light color code as native export. */
class FFmpegPlaybackEngine(context: Context) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
    private var openJob: Job? = null
    private var player: FFmpegPlayer? = null
    private var attachedView: SurfaceView? = null
    private var grade = ColorGrade.IDENTITY
    private var wasPlayingBeforeScrub = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            player?.setSurface(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            player?.setSurface(holder.surface)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            player?.setSurface(null)
        }
    }

    private val positionUpdater = object : Runnable {
        override fun run() {
            val current = player
            if (current != null && mutableState.value.status != PlaybackStatus.IDLE) {
                runCatching {
                    mutableState.value = mutableState.value.copy(
                        positionMs = (current.positionUs() / 1_000L).coerceAtLeast(0),
                        durationMs = (current.durationUs() / 1_000L).coerceAtLeast(0),
                    )
                }
            }
            handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    init { handler.post(positionUpdater) }

    override fun attach(surfaceView: SurfaceView) {
        if (attachedView === surfaceView) return
        attachedView?.holder?.removeCallback(surfaceCallback)
        attachedView = surfaceView
        surfaceView.holder.addCallback(surfaceCallback)
        if (surfaceView.holder.surface?.isValid == true) player?.setSurface(surfaceView.holder.surface)
    }

    override fun detach(surfaceView: SurfaceView) {
        surfaceView.holder.removeCallback(surfaceCallback)
        if (attachedView === surfaceView) attachedView = null
        player?.setSurface(null)
    }

    override fun open(uri: Uri) {
        clear()
        mutableState.value = PlaybackSnapshot(status = PlaybackStatus.LOADING, speed = 1f)
        val newPlayer = FFmpegPlayer(appContext)
        player = newPlayer
        newPlayer.listener = object : FFmpegPlayer.Listener {
            override fun onState(playing: Boolean, ended: Boolean) {
                if (player !== newPlayer) return
                mutableState.value = mutableState.value.copy(
                    status = if (ended) PlaybackStatus.ENDED else PlaybackStatus.READY,
                    isPlaying = playing,
                    errorMessage = null,
                )
            }
            override fun onVideoSize(width: Int, height: Int, pixelAspectRatio: Float) {
                if (player !== newPlayer) return
                mutableState.value = mutableState.value.copy(
                    videoWidth = width,
                    videoHeight = height,
                    pixelAspectRatio = pixelAspectRatio.takeIf { it > 0 } ?: 1f,
                )
            }
            override fun onError(message: String) {
                if (player !== newPlayer) return
                mutableState.value = mutableState.value.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    errorMessage = "Native FFmpeg playback failed: $message",
                )
            }
        }
        openJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    attachedView?.holder?.surface?.takeIf { it.isValid }?.let(newPlayer::setSurface)
                    newPlayer.open(uri)
                    newPlayer.setGrade(ColorGradeMatrix.create(grade, useHdr = false))
                }
            }.onSuccess {
                if (player === newPlayer) {
                    mutableState.value = mutableState.value.copy(
                        status = PlaybackStatus.READY,
                        durationMs = (newPlayer.durationUs() / 1_000L).coerceAtLeast(0),
                    )
                }
            }.onFailure { error ->
                if (player === newPlayer) {
                    mutableState.value = mutableState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = error.message ?: "Native media could not be opened",
                    )
                }
            }
        }
    }

    override fun clear() {
        openJob?.cancel()
        openJob = null
        val old = player
        player = null
        old?.close()
        mutableState.value = PlaybackSnapshot()
    }

    override fun playPause() {
        val current = player ?: return
        if (mutableState.value.isPlaying) current.pause() else current.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekBy(deltaMs: Long) {
        seekTo((mutableState.value.positionMs + deltaMs).coerceIn(0, mutableState.value.durationMs.coerceAtLeast(0)))
    }

    override fun beginScrubbing() {
        wasPlayingBeforeScrub = mutableState.value.isPlaying
        player?.pause()
    }

    override fun scrubTo(positionMs: Long) = seekTo(positionMs)

    override fun endScrubbing(positionMs: Long) {
        seekTo(positionMs)
        if (wasPlayingBeforeScrub) player?.play()
        wasPlayingBeforeScrub = false
    }

    private fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, mutableState.value.durationMs.coerceAtLeast(0))
        player?.seekTo(target * 1_000L)
        mutableState.value = mutableState.value.copy(positionMs = target)
    }

    override fun setSpeed(speed: Float) {
        require(speed in 0.25f..4f) { "Speed must be between 0.25 and 4" }
        player?.setSpeed(speed)
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    override fun setRepeat(repeat: Boolean) {
        player?.setRepeat(repeat)
        mutableState.value = mutableState.value.copy(repeat = repeat)
    }

    override fun setColorGrade(grade: ColorGrade) {
        this.grade = grade
        player?.setGrade(ColorGradeMatrix.create(grade, useHdr = false))
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        clear()
        scope.cancel()
    }

    private companion object { const val POSITION_UPDATE_MS = 100L }
}
