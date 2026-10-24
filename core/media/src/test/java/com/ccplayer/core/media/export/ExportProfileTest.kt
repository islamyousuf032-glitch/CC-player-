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
    fun nativeProfilesCarryExactEncoderContracts() {
        ExportProfiles.current.filter { it.usesNativeBackend }.forEach { profile ->
            assertTrue(profile.id, !profile.encoderName.isNullOrBlank())
            assertTrue(profile.id, !profile.pixelFormat.isNullOrBlank())
            assertTrue(profile.id, profile.container.extension.isNotBlank())
        }
    }

    @Test
    fun hardwareAndSoftwareApvAreDistinctProfiles() {
        assertTrue(ExportProfiles.APV.id != ExportProfiles.APV_SOFTWARE.id)
        assertTrue(ExportProfiles.APV.backend != ExportProfiles.APV_SOFTWARE.backend)
        assertEquals(7, ExportProfiles.current.count { it.backend == ExportBackend.OPEN_APV })
    }

    @Test
    fun media3CannotSilentlyClaimProRes() {
        val invalid = ExportProfiles.PRORES.copy(backend = ExportBackend.MEDIA3)
        assertTrue(ExportProfiles.validate(invalid).isNotEmpty())
    }
}
