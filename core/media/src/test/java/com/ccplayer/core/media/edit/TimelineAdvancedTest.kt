package com.ccplayer.core.media.edit

import com.ccplayer.core.media.color.ColorGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimelineAdvancedTest {
    private val first = Clip("a", "content://a", 1_000_000, 2_000_000, 4_000_000)
    private val second = Clip("b", "content://b", 5_000_000, 0, 2_000_000)
    private val timeline = Timeline(listOf(VideoTrack("v1", listOf(first, second)), VideoTrack("v2")))

    @Test
    fun splitUsesSourceTimeWhenClipIsRetimed() {
        val retimed = timeline.setClipSpeed("v1", "a", 2, 1, ripple = false)
        val split = retimed.splitClip("v1", "a", 2_000_000, "right")
        val right = split.tracks.first().clips.first { it.id == "right" }
        assertEquals(2_000_000L, right.timelineStartUs)
        assertEquals(4_000_000L, right.sourceInUs)
        assertEquals(2, right.speedNumerator)
    }

    @Test
    fun rippleDeleteAndSpeedPreserveTiming() {
        val deleted = timeline.rippleDelete("v1", "a")
        assertEquals(1_000_000L, deleted.tracks.first().clips.single().timelineStartUs)

        val retimed = timeline.setClipSpeed("v1", "a", 2, 1)
        assertEquals(2_000_000L, retimed.tracks.first().clips.first().durationUs)
        assertEquals(3_000_000L, retimed.tracks.first().clips.first { it.id == "b" }.timelineStartUs)
        assertEquals(4_000_000L, retimed.tracks.first().clips.first().sourceDurationUs)
    }

    @Test
    fun moveSlipSlideAndSnapAreValidated() {
        val moved = timeline.moveClip("v1", "b", "v2", 500_000)
        assertEquals(1, moved.tracks[0].clips.size)
        assertEquals(500_000L, moved.tracks[1].clips.single().timelineStartUs)

        val slipped = timeline.slipClip("v1", "a", 3_000_000, sourceDurationUs = 10_000_000)
        assertEquals(3_000_000L, slipped.tracks.first().clips.first().sourceInUs)
        assertEquals(first.timelineStartUs, slipped.tracks.first().clips.first().timelineStartUs)

        val slid = timeline.slideClip("v1", "b", 6_000_000)
        assertEquals(second.sourceInUs, slid.tracks.first().clips.first { it.id == "b" }.sourceInUs)
        assertEquals(5_000_000L, timeline.snapPosition(4_900_000, listOf(1_000_000, 5_000_000), 200_000))
    }

    @Test
    fun groupedEditIsAtomicAndUsesOneUndoEntry() {
        val history = EditHistory(timeline)
        history.executeGrouped(listOf(
            { current -> current.splitClip("v1", "a", 2_000_000, "right") },
            { current -> current.rippleDelete("v1", "b") },
        ))
        assertEquals(2, history.current.tracks.first().clips.size)
        history.undo()
        assertEquals(timeline, history.current)
        history.redo()
        assertEquals(2, history.current.tracks.first().clips.size)

        val beforeFailure = history.current
        try {
            history.executeGrouped(listOf(
                { current -> current.removeClip("v1", "a") },
                { current -> current.trimClip("v1", "missing", 0, 1) },
            ))
            throw AssertionError("Expected grouped edit failure")
        } catch (_: IllegalStateException) {
            // Expected: no partial removal is committed.
        }
        assertEquals(beforeFailure, history.current)
    }

    @Test
    fun historyCapacityDropsOnlyTheOldestEntry() {
        val history = EditHistory(timeline, maxOperations = 1)
        history.execute { it.removeClip("v1", "a") }
        history.execute { it.removeClip("v1", "b") }
        history.undo()
        assertEquals(1, history.current.tracks.first().clips.size)
        assertEquals("b", history.current.tracks.first().clips.single().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun slipOutsideSourceIsRejected() {
        timeline.slipClip("v1", "a", 8_000_000, sourceDurationUs = 10_000_000)
    }

    @Test
    fun projectJsonRoundTripsGradeSpeedAndTimeline() {
        val graded = first.copy(
            speedNumerator = 3,
            speedDenominator = 2,
            grade = ColorGrade(exposureStops = 1.25f, saturation = 0.8f),
        )
        val project = TimelineProject("Camera edit", Timeline(listOf(VideoTrack("v1", listOf(graded)))))
        val restored = TimelineProjectJson.decode(TimelineProjectJson.encode(project))
        assertEquals(project, restored)
        assertNotEquals(graded.durationUs, restored.timeline.tracks.single().clips.single().sourceDurationUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureProjectVersionIsNotPartiallyLoaded() {
        TimelineProjectJson.decode("""{"version":999,"name":"future","timeline":{"tracks":[]}}""")
    }
}
