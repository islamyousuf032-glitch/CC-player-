package com.ccplayer.core.media.color

data class RgbVector(val red: Float, val green: Float, val blue: Float) {
    operator fun get(index: Int): Float = when (index) {
        0 -> red
        1 -> green
        2 -> blue
        else -> throw IndexOutOfBoundsException("RGB channel $index")
    }

    companion object {
        val ZERO = RgbVector(0f, 0f, 0f)
        val ONE = RgbVector(1f, 1f, 1f)
    }
}

/** Basic linear-light controls shared by preview and export. */
data class ColorGrade(
    val exposureStops: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val lift: RgbVector = RgbVector.ZERO,
    val gain: RgbVector = RgbVector.ONE,
    val offset: RgbVector = RgbVector.ZERO,
) {
    init {
        exposureStops.requireFiniteIn(-4f..4f, "exposureStops")
        contrast.requireFiniteIn(-1f..1f, "contrast")
        saturation.requireFiniteIn(0f..2f, "saturation")
        temperature.requireFiniteIn(-1f..1f, "temperature")
        tint.requireFiniteIn(-1f..1f, "tint")
        lift.requireFiniteIn(-1f..1f, "lift")
        gain.requireFiniteIn(0f..4f, "gain")
        offset.requireFiniteIn(-1f..1f, "offset")
    }

    val isIdentity: Boolean
        get() = this == IDENTITY

    companion object {
        val IDENTITY = ColorGrade()
    }
}

private fun Float.requireFiniteIn(range: ClosedFloatingPointRange<Float>, name: String) {
    require(isFinite() && this in range) { "$name must be finite and in $range, was $this" }
}

private fun RgbVector.requireFiniteIn(range: ClosedFloatingPointRange<Float>, name: String) {
    red.requireFiniteIn(range, "$name.red")
    green.requireFiniteIn(range, "$name.green")
    blue.requireFiniteIn(range, "$name.blue")
}
