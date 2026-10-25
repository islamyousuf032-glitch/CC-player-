package com.ccplayer.core.media.edit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Disk persistence for versioned projects. A new project never destroys the previous file before replacement. */
object TimelineProjectFile {
    private const val MAX_PROJECT_BYTES = 64L * 1024L * 1024L

    fun read(file: File): TimelineProject {
        require(file.length() <= MAX_PROJECT_BYTES) { "Project file is too large to load safely" }
        return TimelineProjectJson.decode(file.readText(Charsets.UTF_8))
    }

    fun writeAtomic(project: TimelineProject, file: File) {
        val parent = file.absoluteFile.parentFile ?: throw IOException("Project has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create project directory")
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val bytes = TimelineProjectJson.encode(project).toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() > MAX_PROJECT_BYTES) throw IOException("Project exceeds the 64 MiB persistence limit")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
