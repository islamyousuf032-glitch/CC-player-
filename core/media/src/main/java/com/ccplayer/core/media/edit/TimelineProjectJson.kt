package com.ccplayer.core.media.edit

import com.ccplayer.core.media.color.ColorGrade
import org.json.JSONArray
import org.json.JSONObject

const val TIMELINE_PROJECT_VERSION = 1

data class TimelineProject(
    val name: String = "Untitled",
    val timeline: Timeline = Timeline(),
) {
    init { require(name.isNotBlank()) { "Project name cannot be blank" } }
}

/** Versioned, deterministic project serialization. Unknown future versions are rejected, never partially loaded. */
object TimelineProjectJson {
    fun encode(project: TimelineProject): String = JSONObject()
        .put("version", TIMELINE_PROJECT_VERSION)
        .put("name", project.name)
        .put("timeline", encodeTimeline(project.timeline))
        .toString(2)

    fun decode(value: String): TimelineProject {
        val root = JSONObject(value)
        val version = root.getInt("version")
        require(version == TIMELINE_PROJECT_VERSION) { "Unsupported project version: $version" }
        return TimelineProject(root.getString("name"), decodeTimeline(root.getJSONObject("timeline")))
    }

    private fun encodeTimeline(timeline: Timeline): JSONObject {
        val tracks = JSONArray()
        timeline.tracks.forEach { track ->
            val clips = JSONArray()
            track.clips.forEach { clip ->
                clips.put(
                    JSONObject()
                        .put("id", clip.id)
                        .put("mediaUri", clip.mediaUri)
                        .put("timelineStartUs", clip.timelineStartUs)
                        .put("sourceInUs", clip.sourceInUs)
                        .put("durationUs", clip.durationUs)
                        .put("speedNumerator", clip.speedNumerator)
                        .put("speedDenominator", clip.speedDenominator)
                        .put("grade", encodeGrade(clip.grade)),
                )
            }
            tracks.put(JSONObject().put("id", track.id).put("clips", clips))
        }
        return JSONObject().put("tracks", tracks)
    }

    private fun decodeTimeline(value: JSONObject): Timeline {
        val tracks = value.getJSONArray("tracks")
        return Timeline(
            (0 until tracks.length()).map { trackIndex ->
                val track = tracks.getJSONObject(trackIndex)
                val clips = track.getJSONArray("clips")
                VideoTrack(
                    id = track.getString("id"),
                    clips = (0 until clips.length()).map { clipIndex ->
                        val clip = clips.getJSONObject(clipIndex)
                        Clip(
                            id = clip.getString("id"),
                            mediaUri = clip.getString("mediaUri"),
                            timelineStartUs = clip.getLong("timelineStartUs"),
                            sourceInUs = clip.getLong("sourceInUs"),
                            durationUs = clip.getLong("durationUs"),
                            speedNumerator = clip.optInt("speedNumerator", 1),
                            speedDenominator = clip.optInt("speedDenominator", 1),
                            grade = decodeGrade(clip.optJSONObject("grade")),
                        )
                    },
                )
            },
        )
    }

    private fun encodeGrade(grade: ColorGrade): JSONObject = JSONObject()
        .put("exposureStops", grade.exposureStops.toDouble())
        .put("contrast", grade.contrast.toDouble())
        .put("saturation", grade.saturation.toDouble())
        .put("temperature", grade.temperature.toDouble())
        .put("tint", grade.tint.toDouble())

    private fun decodeGrade(value: JSONObject?): ColorGrade = if (value == null) ColorGrade.IDENTITY else ColorGrade(
        exposureStops = value.optDouble("exposureStops", 0.0).toFloat(),
        contrast = value.optDouble("contrast", 0.0).toFloat(),
        saturation = value.optDouble("saturation", 1.0).toFloat(),
        temperature = value.optDouble("temperature", 0.0).toFloat(),
        tint = value.optDouble("tint", 0.0).toFloat(),
    )
}
