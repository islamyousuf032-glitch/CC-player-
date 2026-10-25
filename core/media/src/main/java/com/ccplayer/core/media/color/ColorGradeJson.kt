package com.ccplayer.core.media.color

import org.json.JSONArray
import org.json.JSONObject

/** Shared backward-compatible color persistence for player preferences and timeline projects. */
object ColorGradeJson {
    fun encode(grade: ColorGrade): JSONObject = JSONObject()
        .put("exposureStops", grade.exposureStops.toDouble())
        .put("contrast", grade.contrast.toDouble())
        .put("saturation", grade.saturation.toDouble())
        .put("temperature", grade.temperature.toDouble())
        .put("tint", grade.tint.toDouble())
        .put("lift", encodeVector(grade.lift))
        .put("gain", encodeVector(grade.gain))
        .put("offset", encodeVector(grade.offset))

    fun decode(value: JSONObject?): ColorGrade = if (value == null) ColorGrade.IDENTITY else ColorGrade(
        exposureStops = value.optDouble("exposureStops", 0.0).toFloat(),
        contrast = value.optDouble("contrast", 0.0).toFloat(),
        saturation = value.optDouble("saturation", 1.0).toFloat(),
        temperature = value.optDouble("temperature", 0.0).toFloat(),
        tint = value.optDouble("tint", 0.0).toFloat(),
        lift = decodeVector(value.optJSONArray("lift"), RgbVector.ZERO),
        gain = decodeVector(value.optJSONArray("gain"), RgbVector.ONE),
        offset = decodeVector(value.optJSONArray("offset"), RgbVector.ZERO),
    )

    private fun encodeVector(vector: RgbVector): JSONArray = JSONArray()
        .put(vector.red.toDouble())
        .put(vector.green.toDouble())
        .put(vector.blue.toDouble())

    private fun decodeVector(value: JSONArray?, fallback: RgbVector): RgbVector {
        if (value == null || value.length() != 3) return fallback
        return RgbVector(
            value.getDouble(0).toFloat(),
            value.getDouble(1).toFloat(),
            value.getDouble(2).toFloat(),
        )
    }
}
