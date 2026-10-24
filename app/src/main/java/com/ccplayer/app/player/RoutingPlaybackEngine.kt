package com.ccplayer.app.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.media.android.playback.Media3PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackSnapshot
import com.ccplayer.media.android.playback.PlaybackStatus
import com.ccplayer.media.ffmpeg.FFmpegPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaybackBackend { NONE, MEDIA3, FFMPEG }

/** Hardware-first routing with a one-way native fallback; a native failure never creates a fallback loop. */
@UnstableApi
class RoutingPlaybackEngine(context: Context) : PlaybackEngine {
    private val platform = Media3PlaybackEngine(context)
    private val native = FFmpegPlaybackEngine(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
    private val mutableBackend = MutableStateFlow(PlaybackBackend.NONE)
    val backend: StateFlow<PlaybackBackend> = mutableBackend.asStateFlow()

    private var active: PlaybackEngine? = null
    private var attachedSurface: SurfaceView? = null
    private var currentUri: Uri? = null
    private var grade = ColorGrade.IDENTITY
    private var speed = 1f
    private var repeat = false

    init {
        scope.launch {
            platform.state.collect { snapshot ->
                if (active === platform) {
                    mutableState.value = snapshot
                    if (snapshot.status == PlaybackStatus.ERROR) currentUri?.let(::openNative)
                }
            }
        }
        scope.launch {
            native.state.collect { snapshot -> if (active === native) mutableState.value = snapshot }
        }
    }

    override fun attach(surfaceView: SurfaceView) {
        attachedSurface = surfaceView
        active?.attach(surfaceView)
    }

    override fun detach(surfaceView: SurfaceView) {
        active?.detach(surfaceView)
        if (attachedSurface === surfaceView) attachedSurface = null
    }

    override fun open(uri: Uri) = openPlatformFirst(uri)

    fun openPlatformFirst(uri: Uri) {
        currentUri = uri
        select(platform, PlaybackBackend.MEDIA3)
        platform.open(uri)
    }

    fun openNative(uri: Uri) {
        currentUri = uri
        select(native, PlaybackBackend.FFMPEG)
        native.open(uri)
    }

    private fun select(engine: PlaybackEngine, backend: PlaybackBackend) {
        if (active === engine) return
        attachedSurface?.let { surface -> active?.detach(surface) }
        active?.clear()
        active = engine
        mutableBackend.value = backend
        engine.setColorGrade(grade)
        engine.setSpeed(speed)
        engine.setRepeat(repeat)
        attachedSurface?.let(engine::attach)
    }

    override fun clear() {
        currentUri = null
        attachedSurface?.let { surface -> active?.detach(surface) }
        active = null
        platform.clear()
        native.clear()
        mutableState.value = PlaybackSnapshot()
        mutableBackend.value = PlaybackBackend.NONE
    }

    override fun playPause() = active?.playPause() ?: Unit
    override fun pause() = active?.pause() ?: Unit
    override fun seekBy(deltaMs: Long) = active?.seekBy(deltaMs) ?: Unit
    override fun beginScrubbing() = active?.beginScrubbing() ?: Unit
    override fun scrubTo(positionMs: Long) = active?.scrubTo(positionMs) ?: Unit
    override fun endScrubbing(positionMs: Long) = active?.endScrubbing(positionMs) ?: Unit

    override fun setSpeed(speed: Float) {
        this.speed = speed
        active?.setSpeed(speed)
    }

    override fun setRepeat(repeat: Boolean) {
        this.repeat = repeat
        active?.setRepeat(repeat)
    }

    override fun setColorGrade(grade: ColorGrade) {
        this.grade = grade
        platform.setColorGrade(grade)
        native.setColorGrade(grade)
    }

    override fun close() {
        clear()
        platform.close()
        native.close()
        scope.cancel()
    }
}
