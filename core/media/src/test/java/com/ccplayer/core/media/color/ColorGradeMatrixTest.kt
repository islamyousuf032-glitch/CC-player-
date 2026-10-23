package com.ccplayer.core.media.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorGradeMatrixTest {
    @Test
    fun identityDoesNotAlterPixels() {
        assertArrayEquals(ColorGradeMatrix.identity(), ColorGradeMatrix.create(ColorGrade(), false), 0f)
    }

    @Test
    fun oneStopExposureDoublesLinearRgb() {
        val matrix = ColorGradeMatrix.create(ColorGrade(exposureStops = 1f), false)
        assertEquals(2f, matrix[0], 0.0001f)
        assertEquals(2f, matrix[5], 0.0001f)
        assertEquals(2f, matrix[10], 0.0001f)
        assertEquals(1f, matrix[15], 0f)
    }

    @Test
    fun zeroSaturationUsesBt709LumaWeights() {
        val matrix = ColorGradeMatrix.create(ColorGrade(saturation = 0f), false)
        assertEquals(0.2126f, matrix[0], 0.0001f)
        assertEquals(0.7152f, matrix[4], 0.0001f)
        assertEquals(0.0722f, matrix[8], 0.0001f)
        assertEquals(matrix[0], matrix[1], 0.0001f)
        assertEquals(matrix[0], matrix[2], 0.0001f)
    }

    @Test
    fun whiteBalanceIsAppliedBeforeSaturation() {
        val matrix = ColorGradeMatrix.create(
            ColorGrade(saturation = 0f, temperature = 0.5f),
            false,
        )
        val input = floatArrayOf(0.2f, 0.4f, 0.6f)
        val balanced = floatArrayOf(input[0] * 1.075f, input[1], input[2] * 0.925f)
        val expected = 0.2126f * balanced[0] + 0.7152f * balanced[1] + 0.0722f * balanced[2]

        for (output in 0..2) {
            val actual = input[0] * matrix[output] +
                input[1] * matrix[4 + output] +
                input[2] * matrix[8 + output] +
                matrix[12 + output]
            assertEquals(expected, actual, 0.0001f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteGradeIsRejected() {
        ColorGrade(exposureStops = Float.NaN)
    }
}
