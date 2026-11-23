package org.ncssar.rid2caltopo.data

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/** The archive folder operations a finished recording's copy needs. */
internal interface RecordingArchiveFolder {
    /** Size of [name] in bytes, or null when there is no such file. */
    fun length(name: String): Long?
    fun create(name: String): Boolean
    fun openOutput(name: String): OutputStream?
    /** True when [name] is gone afterwards. */
    fun delete(name: String): Boolean
    /** The name the file ended up with, or null when the rename failed. */
    fun rename(from: String, to: String): String?
}

/**
 * Copies a finished recording into the archive so that the final name only ever holds a
 * complete file: the bytes go to a hidden partial name, the size is checked, and only then is
 * it renamed. Field 2026-11-23: Android froze the app 44 MB into a 1.1 GB copy that wrote the
 * final name directly, leaving an unplayable file that looked finished.
 */
internal object RecordingArchiveCopy {
    enum class Placement {
        /** Nothing has the name yet. */
        CREATE,
        /** A shorter file has the name: an earlier copy that was cut off. It is replaced. */
        REPLACE_SHORTER,
        /** Same size: an earlier copy finished before the app stopped. Nothing to copy. */
        ALREADY_COMPLETE,
        /** A longer file has the name, so it is another recording: use a "-2" style name. */
        NEW_NAME,
    }

    data class Result(val name: String, val placement: Placement, val bytesCopied: Long)

    fun placement(existingLength: Long?, sourceLength: Long): Placement = when {
        existingLength == null -> Placement.CREATE
        existingLength < sourceLength -> Placement.REPLACE_SHORTER
        existingLength == sourceLength -> Placement.ALREADY_COMPLETE
        else -> Placement.NEW_NAME
    }

    fun partialName(targetName: String): String = ".$targetName.partial"

    fun copy(
        source: File,
        folder: RecordingArchiveFolder,
        preferredName: String,
        availableName: (String) -> String,
        beforeWrite: (Long) -> Unit = {},
    ): Result {
        val sourceLength = source.length()
        val placement = placement(folder.length(preferredName), sourceLength)
        if (placement == Placement.ALREADY_COMPLETE) return Result(preferredName, placement, 0L)
        val target = if (placement == Placement.NEW_NAME) availableName(preferredName) else preferredName
        val partial = partialName(target)
        // A partial left by a copy that was cut off is never a finished recording.
        if (folder.length(partial) != null) check(folder.delete(partial)) { "Unable to remove the earlier partial copy '$partial'" }
        beforeWrite(sourceLength)
        check(folder.create(partial)) { "Unable to create recording file '$partial'" }
        var finished = false
        try {
            FileInputStream(source).use { input ->
                (folder.openOutput(partial) ?: throw IllegalStateException("Unable to open output stream for '$partial'"))
                    .use { output -> input.copyTo(output) }
            }
            val written = folder.length(partial)
            check(written == sourceLength) {
                "Recording copy is incomplete: '$target' has $written of $sourceLength bytes"
            }
            if (placement == Placement.REPLACE_SHORTER) {
                check(folder.delete(target)) { "Unable to replace the incomplete recording '$target'" }
            }
            val finalName = folder.rename(partial, target)
                ?: throw IllegalStateException("Unable to rename the finished recording to '$target'")
            finished = true
            return Result(finalName, placement, sourceLength)
        } finally {
            if (!finished) folder.delete(partial)
        }
    }
}

/** [RecordingArchiveFolder] over a plain directory (tests and app-private storage). */
internal class FileRecordingArchiveFolder(private val dir: File) : RecordingArchiveFolder {
    override fun length(name: String): Long? = File(dir, name).takeIf { it.isFile }?.length()
    override fun create(name: String): Boolean = File(dir, name).let { it.isFile || it.createNewFile() }
    override fun openOutput(name: String): OutputStream? = File(dir, name).outputStream()
    override fun delete(name: String): Boolean = File(dir, name).let { !it.exists() || it.delete() }
    override fun rename(from: String, to: String): String? =
        if (File(dir, from).renameTo(File(dir, to))) to else null
}
