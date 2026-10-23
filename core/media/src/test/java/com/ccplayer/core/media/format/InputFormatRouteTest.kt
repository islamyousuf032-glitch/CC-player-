package com.ccplayer.core.media.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFormatRouteTest {
    @Test
    fun mcrawUsesTheNativeDraftRouteWithoutClaimingRealtimePlayback() {
        val route = InputFormatRouter.route("Camera Day.MCRAW")
        assertEquals(InputBackend.MCRAW_NATIVE, route.backend)
        assertFalse(route.currentlyPlayable)
    }

    @Test
    fun movIsNotMistakenForAProresCodec() {
        val route = InputFormatRouter.route("interview.mov")
        assertEquals(InputBackend.PLATFORM_FIRST, route.backend)
        assertTrue(route.currentlyPlayable)
        assertTrue(route.detail.contains("embedded codec"))
    }

    @Test
    fun professionalAndCommonImageSequencesAreExplicitlyPending() {
        for (name in listOf("shot_0001.exr", "plate_0001.dpx", "frame_0001.png")) {
            val route = InputFormatRouter.route(name)
            assertEquals(InputBackend.IMAGE_SEQUENCE, route.backend)
            assertFalse(route.currentlyPlayable)
        }
    }

    @Test
    fun unknownContainerIsProbedRatherThanRejectedByExtension() {
        val route = InputFormatRouter.route("clip-without-extension")
        assertEquals(InputBackend.PLATFORM_FIRST, route.backend)
        assertTrue(route.detail.contains("probe"))
    }
}
