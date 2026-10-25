package com.ccplayer.core.media.color

import kotlin.math.pow

/**
 * Builds an Android/OpenGL column-major RGBA matrix in linear light.
 *
 * Operation order is exposure/white balance -> saturation -> contrast around a scene-linear 0.18
 * pivot -> per-channel lift/gain/offset. Alpha is untouched and values are deliberately not clamped,
 * preserving HDR headroom.
 * This is the basic grading stage, not a replacement for a future OCIO/ACES transform.
 */
object ColorGradeMatrix {
    private val bt709 = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    private val bt2020 = floatArrayOf(0.2627f, 0.6780f, 0.0593f)

    fun create(grade: ColorGrade, useHdr: Boolean): FloatArray {
        if (grade.isIdentity) return identity()

        val exposure = 2f.pow(grade.exposureStops)
        val contrast = 1f + grade.contrast
        val contrastOffset = 0.18f * (1f - contrast)
        val luma = if (useHdr) bt2020 else bt709

        // These are intentionally small, symmetric draft white-balance gains. Camera-specific
        // chromatic adaptation belongs in the color-management stage, not in this basic matrix.
        val gains = floatArrayOf(
            (1f + 0.15f * grade.temperature + 0.05f * grade.tint) * exposure,
            (1f - 0.10f * grade.tint) * exposure,
            (1f - 0.15f * grade.temperature + 0.05f * grade.tint) * exposure,
        )

        val result = FloatArray(16)
        for (output in 0..2) {
            val outputGain = grade.gain[output]
            for (input in 0..2) {
                val identityWeight = if (output == input) grade.saturation else 0f
                val saturationWeight = identityWeight + luma[input] * (1f - grade.saturation)
                // Column-major: white balance, saturation, contrast, then output-channel gain.
                val value = outputGain * contrast * saturationWeight * gains[input]
                result[input * 4 + output] = value
            }
            result[3 * 4 + output] = 0f
            result[12 + output] = outputGain * contrastOffset +
                grade.lift[output] * (1f - outputGain) + grade.offset[output]
        }
        result[15] = 1f
        return result
    }

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
