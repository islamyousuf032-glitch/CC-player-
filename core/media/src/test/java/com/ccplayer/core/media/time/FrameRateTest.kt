package com.ccplayer.core.media.time

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateTest {
    @Test
    fun rationalIsReduced() {
        val rate = Rational.of(60_000, 2_002)
        assertEquals(30_000, rate.numerator)
        assertEquals(1_001, rate.denominator)
    }

    @Test
    fun fractionalFrameRateUsesExactTimestampMath() {
        val rate = FrameRate(30_000, 1_001)
        assertEquals(0, rate.frameToTimeUs(0))
        assertEquals(33_367, rate.frameToTimeUs(1))
        assertEquals(1_001_000, rate.frameToTimeUs(30))
        assertEquals(29, rate.timeToFrameFloor(1_000_999))
        assertEquals(30, rate.timeToFrameFloor(1_001_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeFrameIsRejected() {
        FrameRate(24).frameToTimeUs(-1)
    }
}
