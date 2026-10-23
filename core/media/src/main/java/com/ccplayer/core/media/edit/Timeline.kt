package com.ccplayer.core.media.edit

import com.ccplayer.core.media.color.ColorGrade

data class Clip(
    val id: String,
    val mediaUri: String,
    val timelineStartUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
    val grade: ColorGrade = ColorGrade.IDENTITY,
) {
    init {
        require(id.isNotBlank()) { "Clip id cannot be blank" }
        require(mediaUri.isNotBlank()) { "Media URI cannot be blank" }
        require(timelineStartUs >= 0) { "Timeline start cannot be negative" }
        require(sourceInUs >= 0) { "Source in-point cannot be negative" }
        require(durationUs > 0) { "Clip duration must be positive" }
    }

    val timelineEndUs: Long get() = timelineStartUs + durationUs
}

data class VideoTrack(
    val id: String,
    val clips: List<Clip> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Track id cannot be blank" }
        require(clips.map(Clip::id).distinct().size == clips.size) { "Clip ids must be unique" }
        clips.sortedBy(Clip::timelineStartUs).zipWithNext().forEach { (left, right) ->
            require(left.timelineEndUs <= right.timelineStartUs) {
                "Clips ${left.id} and ${right.id} overlap"
            }
        }
    }
}

data class Timeline(val tracks: List<VideoTrack> = emptyList()) {
    init {
        require(tracks.map(VideoTrack::id).distinct().size == tracks.size) {
            "Track ids must be unique"
        }
    }

    val durationUs: Long get() = tracks.flatMap(VideoTrack::clips).maxOfOrNull(Clip::timelineEndUs) ?: 0L

    fun splitClip(trackId: String, clipId: String, atUs: Long, newClipId: String): Timeline {
        require(newClipId.isNotBlank()) { "New clip id cannot be blank" }
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        require(atUs > clip.timelineStartUs && atUs < clip.timelineEndUs) {
            "Split point must be inside the clip"
        }
        require(tracks.flatMap(VideoTrack::clips).none { it.id == newClipId }) {
            "Clip id already exists: $newClipId"
        }

        val leftDuration = atUs - clip.timelineStartUs
        val right = clip.copy(
            id = newClipId,
            timelineStartUs = atUs,
            sourceInUs = clip.sourceInUs + leftDuration,
            durationUs = clip.durationUs - leftDuration,
        )
        val left = clip.copy(durationUs = leftDuration)
        return replaceClips(trackId, track.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
    }

    fun trimClip(trackId: String, clipId: String, newSourceInUs: Long, newDurationUs: Long): Timeline {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        check(track.clips.any { it.id == clipId }) { "Unknown clip: $clipId" }
        val updated = track.clips.map { clip ->
            if (clip.id == clipId) clip.copy(sourceInUs = newSourceInUs, durationUs = newDurationUs) else clip
        }
        return replaceClips(trackId, updated)
    }

    fun removeClip(trackId: String, clipId: String): Timeline {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        return replaceClips(trackId, track.clips.filterNot { it.id == clipId })
    }

    private fun replaceClips(trackId: String, clips: List<Clip>): Timeline = copy(
        tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips) else it },
    )
}

/** Immutable undo/redo history. A new edit always clears the redo branch. */
class EditHistory(initial: Timeline) {
    private val undo = ArrayDeque<Timeline>()
    private val redo = ArrayDeque<Timeline>()

    var current: Timeline = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun execute(edit: (Timeline) -> Timeline): Timeline {
        val next = edit(current)
        if (next != current) {
            undo.addLast(current)
            current = next
            redo.clear()
        }
        return current
    }

    fun undo(): Timeline {
        check(canUndo) { "Nothing to undo" }
        redo.addLast(current)
        current = undo.removeLast()
        return current
    }

    fun redo(): Timeline {
        check(canRedo) { "Nothing to redo" }
        undo.addLast(current)
        current = redo.removeLast()
        return current
    }
}
