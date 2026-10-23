package com.ccplayer.core.media.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val clip = Clip(
        id = "a",
        mediaUri = "content://video/a",
        timelineStartUs = 1_000_000,
        sourceInUs = 2_000_000,
        durationUs = 4_000_000,
    )
    private val timeline = Timeline(listOf(VideoTrack("v1", listOf(clip))))

    @Test
    fun splitPreservesTimelineAndSourceMapping() {
        val result = timeline.splitClip("v1", "a", atUs = 2_500_000, newClipId = "b")
        val clips = result.tracks.single().clips
        assertEquals(2, clips.size)
        assertEquals(1_500_000L, clips[0].durationUs)
        assertEquals(2_500_000L, clips[1].timelineStartUs)
        assertEquals(3_500_000L, clips[1].sourceInUs)
        assertEquals(2_500_000L, clips[1].durationUs)
        assertEquals(timeline.durationUs, result.durationUs)
    }

    @Test
    fun newEditClearsRedoBranch() {
        val history = EditHistory(timeline)
        history.execute { it.removeClip("v1", "a") }
        history.undo()
        assertTrue(history.canRedo)
        history.execute { it.trimClip("v1", "a", 2_100_000, 3_000_000) }
        assertFalse(history.canRedo)
        assertEquals(3_000_000L, history.current.tracks.single().clips.single().durationUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingTrimIsRejected() {
        val source = Timeline(
            listOf(
                VideoTrack(
                    "v1",
                    listOf(
                        clip.copy(durationUs = 1_000_000),
                        clip.copy(id = "b", timelineStartUs = 2_000_000),
                    ),
                ),
            ),
        )
        source.trimClip("v1", "a", 0, 2_000_000)
    }
}
