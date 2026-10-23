package com.ccplayer.core.media.time

import java.math.BigInteger
import kotlin.math.abs

/** A reduced rational number. Useful for frame rates such as 30000/1001. */
data class Rational private constructor(val numerator: Int, val denominator: Int) {
    val asDouble: Double get() = numerator.toDouble() / denominator.toDouble()

    companion object {
        fun of(numerator: Int, denominator: Int): Rational {
            require(numerator > 0) { "Numerator must be positive" }
            require(denominator > 0) { "Denominator must be positive" }
            val divisor = gcd(abs(numerator), abs(denominator))
            return Rational(numerator / divisor, denominator / divisor)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * Exact frame/timestamp conversion for the project time base.
 *
 * All timeline edits use integer microseconds. A frame rate is never stored as a binary floating
 * point value, so 23.976 and 29.9797 don't accumulate rounding drift in the edit model.
 */
data class FrameRate(val rational: Rational) {
    constructor(numerator: Int, denominator: Int = 1) : this(Rational.of(numerator, denominator))

    val asDouble: Double get() = rational.asDouble

    fun frameToTimeUs(frameIndex: Long): Long {
        require(frameIndex >= 0) { "Frame index cannot be negative" }
        val value = BigInteger.valueOf(frameIndex)
            .multiply(BigInteger.valueOf(rational.denominator.toLong()))
            .multiply(MICROS)
            .add(BigInteger.valueOf(rational.numerator.toLong() / 2))
            .divide(BigInteger.valueOf(rational.numerator.toLong()))
        return value.toExactLong()
    }

    fun timeToFrameFloor(timeUs: Long): Long {
        require(timeUs >= 0) { "Time cannot be negative" }
        return BigInteger.valueOf(timeUs)
            .multiply(BigInteger.valueOf(rational.numerator.toLong()))
            .divide(
                BigInteger.valueOf(rational.denominator.toLong()).multiply(MICROS),
            )
            .toExactLong()
    }

    private companion object {
        val MICROS: BigInteger = BigInteger.valueOf(1_000_000)
    }
}

private fun BigInteger.toExactLong(): Long {
    val result = longValue()
    require(BigInteger.valueOf(result) == this) { "Timestamp exceeds the Long range" }
    return result
}
