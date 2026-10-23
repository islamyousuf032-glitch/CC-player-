package com.ccplayer.core.media.color

/** Basic linear-light controls shared by preview and export. */
data class ColorGrade(
    val exposureStops: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
) {
    init {
        exposureStops.requireFiniteIn(-4f..4f, "exposureStops")
        contrast.requireFiniteIn(-1f..1f, "contrast")
        saturation.requireFiniteIn(0f..2f, "saturation")
        temperature.requireFiniteIn(-1f..1f, "temperature")
        tint.requireFiniteIn(-1f..1f, "tint")
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
