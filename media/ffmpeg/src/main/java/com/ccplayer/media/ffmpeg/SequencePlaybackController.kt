package com.ccplayer.media.ffmpeg

import android.graphics.Bitmap
import com.ccplayer.core.media.time.FrameRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import kotlin.coroutines.coroutineContext

data class SequencePlaybackState(
    val isOpen: Boolean = false,
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    val frameIndex: Int = 0,
    val frameCount: Int = 0,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val frameRateNum: Int = 24,
    val frameRateDen: Int = 1,
    val preview: Bitmap? = null,
    val droppedFrames: Int = 0,
    val error: String? = null,
)

/** Rational-fps, bounded-read-ahead playback for a sorted SAF image sequence. */
class SequencePlaybackController(private val decoder: FFmpegImageDecoder) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SequencePlaybackState())
    val state: StateFlow<SequencePlaybackState> = mutableState.asStateFlow()
    private var info: ImageSequenceInfo? = null
    private var playbackJob: Job? = null
    private var renderJob: Job? = null
    private var generation = 0L
    @Volatile private var playing = false
    @Volatile private var basePositionUs = 0L
    @Volatile private var anchorNanoTime = 0L

    fun open(sequence: ImageSequenceInfo, frameRateNum: Int = 24, frameRateDen: Int = 1, startPositionUs: Long = 0) {
        stop()
        info = sequence
        val rate = FrameRate(frameRateNum, frameRateDen)
        val duration = rate.frameToTimeUs(sequence.frames.size.toLong())
        val start = startPositionUs.coerceIn(0, duration)
        val index = rate.timeToFrameFloor(start).toInt()
            .coerceIn(0, (sequence.frames.size - 1).coerceAtLeast(0))
        val position = rate.frameToTimeUs(index.toLong())
        basePositionUs = position
        mutableState.value = SequencePlaybackState(
            isOpen = true,
            frameIndex = index,
            frameCount = sequence.frames.size,
            positionUs = position,
            durationUs = duration,
            frameRateNum = frameRateNum,
            frameRateDen = frameRateDen,
        )
        renderStill(index, generation)
    }

    fun playPause() {
        if (playing) pause() else play()
    }

    fun play() {
        if (!mutableState.value.isOpen || playing) return
        if (mutableState.value.ended) {
            seekFrame(0, resume = true)
            return
        }
        basePositionUs = mutableState.value.positionUs
        anchorNanoTime = System.nanoTime()
        playing = true
        mutableState.value = mutableState.value.copy(isPlaying = true, ended = false, error = null)
        val expected = generation
        playbackJob = scope.launch { playbackLoop(expected) }
    }

    fun pause() {
        if (!playing) return
        val position = clockPositionUs()
        stop()
        basePositionUs = position
        mutableState.value = mutableState.value.copy(isPlaying = false, positionUs = position)
    }

    fun seekFrame(index: Int, resume: Boolean = playing) {
        val state = mutableState.value
        if (state.frameCount <= 0) return
        stop()
        val target = index.coerceIn(0, state.frameCount - 1)
        val position = FrameRate(state.frameRateNum, state.frameRateDen).frameToTimeUs(target.toLong())
        basePositionUs = position
        mutableState.value = state.copy(isPlaying = false, frameIndex = target, positionUs = position, ended = false, error = null)
        val expected = generation
        renderStill(target, expected, resume)
    }

    fun setFrameRate(numerator: Int, denominator: Int) {
        require(numerator > 0 && denominator > 0)
        val state = mutableState.value
        if (!state.isOpen) return
        val wasPlaying = playing
        val position = if (playing) clockPositionUs() else state.positionUs
        stop()
        val rate = FrameRate(numerator, denominator)
        val duration = rate.frameToTimeUs(state.frameCount.toLong())
        val clamped = position.coerceIn(0, duration)
        val index = rate.timeToFrameFloor(clamped).toInt().coerceIn(0, (state.frameCount - 1).coerceAtLeast(0))
        basePositionUs = clamped
        mutableState.value = state.copy(
            isPlaying = false,
            frameIndex = index,
            positionUs = clamped,
            durationUs = duration,
            frameRateNum = numerator,
            frameRateDen = denominator,
            ended = false,
        )
        renderStill(index, generation, wasPlaying)
    }

    private suspend fun playbackLoop(expected: Long) {
        var prefetch: Deferred<Bitmap>? = null
        try {
            val initialState = mutableState.value
            val rate = FrameRate(initialState.frameRateNum, initialState.frameRateDen)
            var nextIndex = rate.timeToFrameFloor(clockPositionUs()).toInt()
                .coerceIn(0, (initialState.frameCount - 1).coerceAtLeast(0))
            while (coroutineContext.isActive && generation == expected && playing) {
                val state = mutableState.value
                val clock = clockPositionUs()
                if (nextIndex >= state.frameCount) {
                    if (clock < state.durationUs) {
                        delay(((state.durationUs - clock) / 1000).coerceIn(1, 20))
                        continue
                    }
                    playing = false
                    basePositionUs = state.durationUs
                    mutableState.value = state.copy(
                        isPlaying = false,
                        ended = true,
                        positionUs = state.durationUs,
                        frameIndex = (state.frameCount - 1).coerceAtLeast(0),
                    )
                    break
                }
                val target = rate.frameToTimeUs(nextIndex.toLong())
                val frameDuration = rate.frameToTimeUs(1).coerceAtLeast(1)
                if (target + frameDuration < clock - frameDuration && nextIndex < state.frameCount - 1) {
                    nextIndex++
                    mutableState.value = mutableState.value.copy(droppedFrames = mutableState.value.droppedFrames + 1)
                    continue
                }
                if (target > clock + 2000) {
                    delay(((target - clock) / 1000).coerceIn(1, 20))
                    continue
                }
                if (prefetch == null) {
                    val frame = info!!.frames[nextIndex]
                    prefetch = scope.async(Dispatchers.Default) { decoder.decode(frame.uri, PREVIEW_DIMENSION) }
                }
                val bitmap = prefetch!!.await()
                prefetch = null
                if (generation != expected || !playing) break
                mutableState.value = mutableState.value.copy(
                    preview = bitmap,
                    frameIndex = nextIndex,
                    positionUs = clockPositionUs(),
                    error = null,
                )
                nextIndex++
                if (nextIndex < state.frameCount) {
                    val frame = info!!.frames[nextIndex]
                    prefetch = scope.async(Dispatchers.Default) { decoder.decode(frame.uri, PREVIEW_DIMENSION) }
                }
            }
        } catch (error: Throwable) {
            if (generation == expected) {
                playing = false
                mutableState.value = mutableState.value.copy(isPlaying = false, error = error.message)
            }
        } finally {
            prefetch?.cancel()
        }
    }

    private fun renderStill(index: Int, expected: Long, resume: Boolean = false) {
        val sequence = info ?: return
        renderJob = scope.launch {
            runCatching { decoder.decode(sequence.frames[index].uri, PREVIEW_DIMENSION) }
                .onSuccess { bitmap ->
                    if (generation == expected) {
                        mutableState.value = mutableState.value.copy(preview = bitmap, frameIndex = index, error = null)
                        if (resume) play()
                    }
                }
                .onFailure { error ->
                    if (generation == expected) mutableState.value = mutableState.value.copy(error = error.message)
                }
        }
    }

    private fun clockPositionUs(): Long {
        if (!playing) return basePositionUs
        return (basePositionUs + (System.nanoTime() - anchorNanoTime) / 1000)
            .coerceIn(0, mutableState.value.durationUs)
    }

    private fun stop() {
        generation++
        playing = false
        playbackJob?.cancel()
        playbackJob = null
        renderJob?.cancel()
        renderJob = null
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private companion object { const val PREVIEW_DIMENSION = 960 }
}
