package com.ccplayer.core.media.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProfileTest {
    @Test
    fun builtInProfilesHaveValidContainerPairs() {
        ExportProfiles.current.forEach { profile ->
            assertEquals(emptyList<String>(), ExportProfiles.validate(profile))
        }
        assertEquals(emptyList<String>(), ExportProfiles.validate(ExportProfiles.PRORES))
    }

    @Test
    fun media3CannotSilentlyClaimProRes() {
        val invalid = ExportProfiles.PRORES.copy(backend = ExportBackend.MEDIA3)
        assertTrue(ExportProfiles.validate(invalid).isNotEmpty())
    }
}
