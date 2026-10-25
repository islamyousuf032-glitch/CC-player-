package com.ccplayer.core.media.color

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorGradeJsonTest {
    @Test
    fun allChannelsRoundTrip() {
        val grade = ColorGrade(
            exposureStops = 0.5f,
            lift = RgbVector(-0.1f, 0.2f, 0.3f),
            gain = RgbVector(1.1f, 0.9f, 1.2f),
            offset = RgbVector(0.01f, -0.02f, 0.03f),
        )
        assertEquals(grade, ColorGradeJson.decode(ColorGradeJson.encode(grade)))
    }

    @Test
    fun oldGradeJsonUsesSafeDefaults() {
        val restored = ColorGradeJson.decode(JSONObject("""{"exposureStops":1.0,"saturation":0.8}"""))
        assertEquals(1f, restored.exposureStops, 0f)
        assertEquals(RgbVector.ZERO, restored.lift)
        assertEquals(RgbVector.ONE, restored.gain)
        assertEquals(RgbVector.ZERO, restored.offset)
    }
}
