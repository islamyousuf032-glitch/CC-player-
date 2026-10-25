package com.ccplayer.core.media.edit

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimelineProjectFileTest {
    @Test
    fun atomicStoreRoundTripsWithoutTemporaryFiles() {
        val directory = Files.createTempDirectory("cc-player-project").toFile()
        try {
            val file = directory.resolve("edit.ccplayer.json")
            val project = TimelineProject(
                "Test",
                Timeline(listOf(VideoTrack("v1", listOf(Clip("a", "content://a", 0, 0, 1_000_000))))),
            )
            TimelineProjectFile.writeAtomic(project, file)
            assertEquals(project, TimelineProjectFile.read(file))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

            val replacement = project.copy(name = "Replacement")
            TimelineProjectFile.writeAtomic(replacement, file)
            assertEquals(replacement, TimelineProjectFile.read(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}
