package com.ccplayer.core.media.edit

import com.ccplayer.core.media.color.ColorGrade

data class Clip(
    val id: String,
    val mediaUri: String,
    val timelineStartUs: Long,
    val sourceInUs: Long,
    val durationUs: Long,
    val speedNumerator: Int = 1,
    val speedDenominator: Int = 1,
    val grade: ColorGrade = ColorGrade.IDENTITY,
) {
    init {
        require(id.isNotBlank()) { "Clip id cannot be blank" }
        require(mediaUri.isNotBlank()) { "Media URI cannot be blank" }
        require(timelineStartUs >= 0) { "Timeline start cannot be negative" }
        require(sourceInUs >= 0) { "Source in-point cannot be negative" }
        require(durationUs > 0) { "Clip duration must be positive" }
        require(speedNumerator > 0 && speedDenominator > 0) { "Clip speed must be a positive rational" }
        require(Math.multiplyExact(durationUs, speedNumerator.toLong()) / speedDenominator > 0) {
            "Clip speed produces a zero-length source range"
        }
    }

    val timelineEndUs: Long get() = timelineStartUs + durationUs
    val sourceDurationUs: Long get() = Math.multiplyExact(durationUs, speedNumerator.toLong()) / speedDenominator
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
        require(tracks.map(VideoTrack::id).distinct().size == tracks.size) { "Track ids must be unique" }
        val clipIds = tracks.flatMap(VideoTrack::clips).map(Clip::id)
        require(clipIds.distinct().size == clipIds.size) { "Clip ids must be unique across the timeline" }
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
        val leftSourceDuration = Math.multiplyExact(leftDuration, clip.speedNumerator.toLong()) / clip.speedDenominator
        val right = clip.copy(
            id = newClipId,
            timelineStartUs = atUs,
            sourceInUs = clip.sourceInUs + leftSourceDuration,
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

    fun moveClip(trackId: String, clipId: String, targetTrackId: String, newTimelineStartUs: Long): Timeline {
        require(newTimelineStartUs >= 0) { "Timeline start cannot be negative" }
        val source = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        check(tracks.any { it.id == targetTrackId }) { "Unknown track: $targetTrackId" }
        val clip = source.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        val moved = clip.copy(timelineStartUs = newTimelineStartUs)
        val updated = tracks.map { track ->
            val remaining = track.clips.filterNot { it.id == clipId }
            if (track.id == targetTrackId) track.copy(clips = remaining + moved) else track.copy(clips = remaining)
        }
        return Timeline(updated)
    }

    fun rippleDelete(trackId: String, clipId: String): Timeline {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        val updated = track.clips.filterNot { it.id == clipId }.map { later ->
            if (later.timelineStartUs >= clip.timelineEndUs) later.copy(timelineStartUs = later.timelineStartUs - clip.durationUs) else later
        }
        return replaceClips(trackId, updated)
    }

    fun rippleTrim(trackId: String, clipId: String, newSourceInUs: Long, newDurationUs: Long): Timeline {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        require(newDurationUs > 0) { "Clip duration must be positive" }
        val delta = newDurationUs - clip.durationUs
        val updated = track.clips.map { item ->
            when {
                item.id == clipId -> item.copy(sourceInUs = newSourceInUs, durationUs = newDurationUs)
                item.timelineStartUs >= clip.timelineEndUs -> item.copy(timelineStartUs = item.timelineStartUs + delta)
                else -> item
            }
        }
        return replaceClips(trackId, updated)
    }

    /** Changes the source window while preserving the clip's timeline position and duration. */
    fun slipClip(trackId: String, clipId: String, newSourceInUs: Long, sourceDurationUs: Long): Timeline {
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        require(sourceDurationUs >= 0 && newSourceInUs >= 0 && newSourceInUs <= sourceDurationUs - clip.sourceDurationUs) {
            "Slip would move the clip outside its source media"
        }
        return replaceClips(trackId, track.clips.map { if (it.id == clipId) it.copy(sourceInUs = newSourceInUs) else it })
    }

    /** Moves the timeline window without changing the source in-point. */
    fun slideClip(trackId: String, clipId: String, newTimelineStartUs: Long): Timeline =
        moveClip(trackId, clipId, trackId, newTimelineStartUs)

    fun setClipSpeed(trackId: String, clipId: String, numerator: Int, denominator: Int, ripple: Boolean = true): Timeline {
        require(numerator > 0 && denominator > 0) { "Clip speed must be a positive rational" }
        val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("Unknown clip: $clipId")
        val sourceDuration = clip.sourceDurationUs
        val newDuration = Math.multiplyExact(sourceDuration, denominator.toLong()) / numerator
        require(newDuration > 0) { "Speed produces a zero-duration clip" }
        val delta = newDuration - clip.durationUs
        val updated = track.clips.map { item ->
            when {
                item.id == clipId -> item.copy(
                    durationUs = newDuration,
                    speedNumerator = numerator,
                    speedDenominator = denominator,
                )
                ripple && item.timelineStartUs >= clip.timelineEndUs -> item.copy(timelineStartUs = item.timelineStartUs + delta)
                else -> item
            }
        }
        return replaceClips(trackId, updated)
    }

    fun snapPosition(positionUs: Long, candidates: Collection<Long>, thresholdUs: Long): Long {
        require(positionUs >= 0 && thresholdUs >= 0 && candidates.all { it >= 0 }) { "Snap times must be non-negative" }
        val nearest = candidates.minByOrNull { candidate -> Math.abs(candidate - positionUs) } ?: return positionUs
        return if (Math.abs(nearest - positionUs) <= thresholdUs) nearest else positionUs
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
