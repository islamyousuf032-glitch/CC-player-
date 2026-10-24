package com.ccplayer.media.ffmpeg

import android.graphics.Bitmap
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
    private var job: Job? = null
    private var generation = 0L
    @Volatile private var playing = false
    @Volatile private var basePositionUs = 0L
    @Volatile private var anchorNanoTime = 0L

    fun open(sequence: ImageSequenceInfo, frameRateNum: Int = 24, frameRateDen: Int = 1, startPositionUs: Long = 0) {
        stop()
        info = sequence
        val duration = durationUs(sequence.frames.size, frameRateNum, frameRateDen)
        val index = frameIndexForPosition(startPositionUs, sequence.frames.size, frameRateNum, frameRateDen)
        val position = positionForFrame(index, frameRateNum, frameRateDen)
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
        if (mutableState.value.ended) seekFrame(0, resume = false)
        basePositionUs = mutableState.value.positionUs
        anchorNanoTime = System.nanoTime()
        playing = true
        mutableState.value = mutableState.value.copy(isPlaying = true, ended = false, error = null)
        val expected = generation
        job = scope.launch { playbackLoop(expected) }
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
        val position = positionForFrame(target, state.frameRateNum, state.frameRateDen)
        basePositionUs = position
        mutableState.value = state.copy(frameIndex = target, positionUs = position, ended = false, error = null)
        val expected = generation
        job = scope.launch {
            renderStill(target, expected)
            if (resume && generation == expected) play()
        }
    }

    fun setFrameRate(numerator: Int, denominator: Int) {
        require(numerator > 0 && denominator > 0)
        val state = mutableState.value
        if (!state.isOpen) return
        val position = if (playing) clockPositionUs() else state.positionUs
        stop()
        val duration = durationUs(state.frameCount, numerator, denominator)
        val clamped = position.coerceIn(0, duration)
        val index = frameIndexForPosition(clamped, state.frameCount, numerator, denominator)
        basePositionUs = clamped
        mutableState.value = state.copy(
            frameIndex = index,
            positionUs = clamped,
            durationUs = duration,
            frameRateNum = numerator,
            frameRateDen = denominator,
            ended = false,
        )
        renderStill(index, generation)
    }

    private suspend fun playbackLoop(expected: Long) {
        var prefetch: Deferred<Bitmap>? = null
        try {
            var nextIndex = frameIndexForPosition(
                clockPositionUs(),
                mutableState.value.frameCount,
                mutableState.value.frameRateNum,
                mutableState.value.frameRateDen,
            )
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
                val target = positionForFrame(nextIndex, state.frameRateNum, state.frameRateDen)
                val frameDuration = 1_000_000.0 * state.frameRateDen / state.frameRateNum
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

    private fun renderStill(index: Int, expected: Long) {
        val sequence = info ?: return
        job = scope.launch {
            runCatching { decoder.decode(sequence.frames[index].uri, PREVIEW_DIMENSION) }
                .onSuccess { bitmap ->
                    if (generation == expected) mutableState.value = mutableState.value.copy(preview = bitmap, frameIndex = index, error = null)
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
        job?.cancel()
        job = null
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private companion object { const val PREVIEW_DIMENSION = 960 }
}

private fun durationUs(frames: Int, numerator: Int, denominator: Int): Long =
    (frames.toDouble() * 1_000_000.0 * denominator / numerator).toLong()

private fun positionForFrame(index: Int, numerator: Int, denominator: Int): Long =
    (index.toDouble() * 1_000_000.0 * denominator / numerator).toLong()

private fun frameIndexForPosition(positionUs: Long, frameCount: Int, numerator: Int, denominator: Int): Int =
    (positionUs.toDouble() * numerator / (1_000_000.0 * denominator)).toInt().coerceIn(0, (frameCount - 1).coerceAtLeast(0))
