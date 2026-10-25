package com.ccplayer.media.ffmpeg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** JNI owner for the clocked native decoder. All methods are safe to call from the player/UI threads. */
class FFmpegPlayer(context: Context) : Closeable {
    interface Listener {
        fun onState(playing: Boolean, ended: Boolean)
        fun onVideoSize(width: Int, height: Int, pixelAspectRatio: Float)
        fun onError(message: String)
    }

    private val resolver = context.applicationContext.contentResolver
    private val nativeHandle = AtomicLong(0)
    private var input: ParcelFileDescriptor? = null
    var listener: Listener? = null

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioGeneration = Long.MIN_VALUE
    @Volatile private var audioAnchorPositionUs = -1L
    @Volatile private var audioAnchorFrame = 0L
    @Volatile private var audioSpeed = 1f

    init {
        nativeHandle.set(nativeCreate())
    }

    @Synchronized
    fun open(uri: Uri) {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw FFmpegException("Could not open media for native playback")
        input = descriptor
        try {
            nativeOpen(handle(), "/proc/self/fd/${descriptor.fd}")
        } catch (error: Throwable) {
            descriptor.close()
            input = null
            throw error
        }
    }

    fun setSurface(surface: Surface?) = nativeSetSurface(handle(), surface)
    fun play() = nativePlay(handle())
    fun pause() = nativePause(handle())
    fun seekTo(positionUs: Long) = nativeSeek(handle(), positionUs.coerceAtLeast(0))
    fun setSpeed(speed: Float) = nativeSetSpeed(handle(), speed)
    fun setRepeat(repeat: Boolean) = nativeSetRepeat(handle(), repeat)
    fun setGrade(matrix: FloatArray) {
        require(matrix.size == 16) { "Color matrix must contain 16 floats" }
        nativeSetGrade(handle(), matrix)
    }
    fun positionUs(): Long = nativePosition(handle())
    fun durationUs(): Long = nativeDuration(handle())

    fun onNativeAudio(samples: ByteBuffer, sampleRate: Int, channels: Int, presentationUs: Long, generation: Long) {
        if (generation != audioGeneration) {
            configureAudio(sampleRate, channels, presentationUs, generation)
        }
        val track = audioTrack ?: return
        while (samples.hasRemaining() && generation == audioGeneration && nativeHandle.get() != 0L) {
            val written = track.write(samples, samples.remaining(), AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw FFmpegException("AudioTrack PCM write failed: $written")
            if (written == 0) Thread.yield()
        }
    }

    fun onNativeAudioPosition(): Long {
        val track = audioTrack ?: return -1
        val anchor = audioAnchorPositionUs
        if (anchor < 0 || track.playState != AudioTrack.PLAYSTATE_PLAYING) return -1
        val timestamp = AudioTimestamp()
        if (!track.getTimestamp(timestamp)) return -1
        val frames = timestamp.framePosition - audioAnchorFrame
        if (frames < 0) return -1
        return anchor + frames * 1_000_000L / track.sampleRate
    }

    fun onNativeAudioFlush() {
        audioGeneration = Long.MIN_VALUE
        audioAnchorPositionUs = -1
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    fun onNativeAudioSpeed(speed: Float) {
        audioSpeed = speed
        audioTrack?.let { track -> runCatching { track.playbackParams = playbackParams(speed) } }
    }

    fun onNativeState(playing: Boolean, ended: Boolean) {
        listener?.onState(playing, ended)
    }

    fun onNativeVideoSize(width: Int, height: Int, pixelAspectRatio: Float) {
        listener?.onVideoSize(width, height, pixelAspectRatio)
    }

    fun onNativePlaybackError(message: String) {
        listener?.onError(message)
    }

    private fun configureAudio(sampleRate: Int, channels: Int, presentationUs: Long, generation: Long) {
        releaseAudio()
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_SURROUND
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> throw FFmpegException("AudioTrack cannot address this $channels-channel layout explicitly")
        }
        val bytesPerFrame = channels * 2
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw FFmpegException("AudioTrack rejected $sampleRate Hz / $channels channels")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * bytesPerFrame / 5))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw FFmpegException("AudioTrack could not initialize")
        }
        track.playbackParams = playbackParams(audioSpeed)
        audioTrack = track
        audioGeneration = generation
        audioAnchorPositionUs = presentationUs
        audioAnchorFrame = 0
        track.play()
    }

    private fun playbackParams(speed: Float): PlaybackParams =
        PlaybackParams().setSpeed(speed).setPitch(1f).setAudioFallbackMode(
            PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT,
        )

    private fun releaseAudio() {
        val track = audioTrack
        audioTrack = null
        audioGeneration = Long.MIN_VALUE
        audioAnchorPositionUs = -1
        if (track != null) {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun handle(): Long = nativeHandle.get().takeIf { it != 0L }
        ?: throw FFmpegException("Native player is closed")

    @Synchronized
    override fun close() {
        val pointer = nativeHandle.getAndSet(0)
        if (pointer != 0L) {
            runCatching { nativeSetSurface(pointer, null) }
            runCatching { nativeRelease(pointer) }
        }
        input?.close()
        input = null
        releaseAudio()
    }

    private external fun nativeCreate(): Long
    private external fun nativeOpen(pointer: Long, path: String)
    private external fun nativeSetSurface(pointer: Long, surface: Surface?)
    private external fun nativePlay(pointer: Long)
    private external fun nativePause(pointer: Long)
    private external fun nativeSeek(pointer: Long, positionUs: Long)
    private external fun nativeSetSpeed(pointer: Long, speed: Float)
    private external fun nativeSetRepeat(pointer: Long, repeat: Boolean)
    private external fun nativeSetGrade(pointer: Long, matrix: FloatArray)
    private external fun nativePosition(pointer: Long): Long
    private external fun nativeDuration(pointer: Long): Long
    private external fun nativeRelease(pointer: Long)

    private companion object {
        init {
            System.loadLibrary("ccplayer_ffmpeg")
        }
    }
}
