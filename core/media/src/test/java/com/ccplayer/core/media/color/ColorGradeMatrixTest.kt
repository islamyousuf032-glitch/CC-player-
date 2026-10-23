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

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteGradeIsRejected() {
        ColorGrade(exposureStops = Float.NaN)
    }
}
