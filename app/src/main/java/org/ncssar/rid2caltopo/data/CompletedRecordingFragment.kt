package org.ncssar.rid2caltopo.data

import java.io.File

/** A completion event authorizes only that closed file, never a newer live fragment. */
internal fun completedRecordingFragment(sourceRoot: File, completedPath: String): List<File> {
    val file = File(completedPath)
    check(file.canonicalFile.parentFile == sourceRoot.canonicalFile) { "Recording completion is outside its stream directory" }
    return if (file.isFile) listOf(file) else emptyList()
}
