package org.ncssar.rid2caltopo.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog

object MediaMTXRecordingSync {
    private const val TAG = "MediaMTXRecordingSync"
    private const val DEFAULT_SAMPLE_BUFFER_SIZE = 1024 * 1024
    private const val NORMALIZED_VIDEO_FPS = 30

    @JvmStatic
    fun getRecordingStagingDir(context: Context): File =
        File(context.filesDir, "mediamtx-recordings")

    @JvmStatic
    fun syncAll(context: Context, streamPath: String? = null) {
        val todaysTrackDir = CaltopoClient.GetTodaysTrackDir()
        val stagingRoot = getRecordingStagingDir(context)
        if (!stagingRoot.exists()) return

        try {
            if (streamPath.isNullOrBlank()) {
                syncDirectoryTree(context, todaysTrackDir, stagingRoot, "")
            } else {
                syncStreamPath(context, todaysTrackDir, File(stagingRoot, streamPath), streamPath)
            }
            deleteEmptyDirectories(stagingRoot)
            CaltopoClient.CTDebug(TAG, "syncAll() completed path=$streamPath")
        } catch (e: Exception) {
            CaltopoClient.CTError(TAG, "syncAll() raised for path=$streamPath", e)
        }
    }

    private fun syncDirectoryTree(
        context: Context,
        todaysTrackDir: DocumentFile?,
        root: File,
        relativePath: String,
    ) {
        val children = root.listFiles()?.sortedBy { it.name } ?: return
        val mediaFiles = children.filter { it.isFile && isMediaFragment(it) }
        if (mediaFiles.isNotEmpty()) {
            syncStreamPath(context, todaysTrackDir, root, relativePath)
        }
        children
            .filter { it.isDirectory }
            .forEach { child ->
                val childRelative = if (relativePath.isBlank()) child.name else "$relativePath/${child.name}"
                syncDirectoryTree(context, todaysTrackDir, child, childRelative)
            }
    }

    private fun syncStreamPath(
        context: Context,
        todaysTrackDir: DocumentFile?,
        sourceRoot: File,
        streamPath: String,
    ) {
        if (!sourceRoot.exists()) return
        val fragments = sourceRoot.listFiles()
            ?.filter { it.isFile && isMediaFragment(it) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (fragments.isEmpty()) return

        val mergedFile = tempMergedRecordingFile(context, streamPath, fragments.first())

        try {
            if (org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge.preserveSEIOriginals) {
                // Retain original MediaMTX bytes before normalized remux/deletion.
                val researchRoot = File(context.filesDir, "video-sei-originals")
                val researchDir = File(researchRoot, streamPath.replace(Regex("[^A-Za-z0-9_-]"), "_"))
                check(researchDir.isDirectory || researchDir.mkdirs()) { "Cannot preserve research originals" }
                fragments.forEach { fragment ->
                    val original = File(researchDir, fragment.name)
                    if (!original.exists()) fragment.copyTo(original)
                    check(original.length() == fragment.length()) { "Incomplete research original" }
                }
                CaltopoClient.CTLog("RESEARCH", "DjiSeiPayload", "Preserved original fragments path=${researchDir.absolutePath}")
            }
            remuxMp4Sequence(fragments, mergedFile)
            if (todaysTrackDir != null) {
                val targetDir = ensureTargetDir(todaysTrackDir, streamPath)
                val preferredName = archiveRecordingFileName(streamPath, fragments.first())
                copyFileInto(
                    resolver = context.contentResolver,
                    sourceFile = mergedFile,
                    targetDir = targetDir,
                    targetName = availableTargetName(targetDir, preferredName),
                )
            } else {
                CaltopoClient.CTWarn(
                    TAG,
                    "Track archive unavailable; retaining recording for tracker access only path=$streamPath",
                )
            }
            ManagedVideoSessionRecordingCatalog.retain(
                context = context,
                mergedFile = mergedFile,
                streamPath = streamPath,
            )
            fragments.forEach { fragment ->
                if (!fragment.delete()) {
                    CaltopoClient.CTWarn(TAG, "Unable to delete staged fragment ${fragment.absolutePath}")
                }
            }
        } finally {
            if (mergedFile.exists() && !mergedFile.delete()) {
                CaltopoClient.CTWarn(TAG, "Unable to delete temp merged recording ${mergedFile.absolutePath}")
            }
        }
        deleteEmptyDirectories(sourceRoot)
    }

    private fun remuxMp4Sequence(inputFiles: List<File>, outputFile: File) {
        val firstInput = inputFiles.firstOrNull()
            ?: throw IllegalArgumentException("No input files to remux.")
        val firstLayout = readTrackLayout(firstInput)
        if (firstLayout.trackIndices.isEmpty()) {
            throw IllegalStateException("No audio/video tracks found in ${firstInput.absolutePath}")
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackMap = LinkedHashMap<Int, Int>()
        firstLayout.trackIndices.forEach { trackIndex ->
            val format = firstLayout.formats.getValue(trackIndex)
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
                format.setInteger(MediaFormat.KEY_FRAME_RATE, NORMALIZED_VIDEO_FPS)
            }
            muxerTrackMap[trackIndex] = muxer.addTrack(format)
        }
        muxer.start()

        try {
            val maximumSampleSize = maxSampleBufferSize(inputFiles, firstLayout.trackIndices)
            val readBuffer = ByteBuffer.allocateDirect(maximumSampleSize)
            val writeBuffer = ByteBuffer.allocateDirect(maximumSampleSize * 2)
            var nextSegmentOffsetUs = 0L
            inputFiles.forEach { inputFile ->
                nextSegmentOffsetUs = appendMp4IntoMuxer(
                    inputFile = inputFile,
                    muxer = muxer,
                    muxerTrackMap = muxerTrackMap,
                    trackFormats = firstLayout.formats,
                    readBuffer = readBuffer,
                    writeBuffer = writeBuffer,
                    segmentOffsetUs = nextSegmentOffsetUs,
                )
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    private fun appendMp4IntoMuxer(
        inputFile: File,
        muxer: MediaMuxer,
        muxerTrackMap: Map<Int, Int>,
        trackFormats: Map<Int, MediaFormat>,
        readBuffer: ByteBuffer,
        writeBuffer: ByteBuffer,
        segmentOffsetUs: Long,
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)
        muxerTrackMap.keys.forEach(extractor::selectTrack)

        return try {
            var segmentFirstPtsUs = Long.MIN_VALUE
            var segmentLastWrittenPtsUs = segmentOffsetUs
            val lastWrittenPtsUsByTrack = mutableMapOf<Int, Long>()
            val h264Assemblers = trackFormats.mapNotNull { (trackIndex, format) ->
                if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    trackIndex to H264ArchiveAccessUnitAssembler()
                } else {
                    null
                }
            }.toMap()
            val h264Cadences = h264Assemblers.keys.associateWith {
                H264ArchiveCadence(NORMALIZED_VIDEO_FPS)
            }
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            fun writeSample(trackIndex: Int, sample: H264ArchiveAccessUnitAssembler.Sample) {
                val muxerTrackIndex = muxerTrackMap.getValue(trackIndex)
                val cadence = h264Cadences[trackIndex]
                val requestedPresentationTimeUs = if (
                    cadence != null && sample.data.hasVclH264NalUnit()
                ) {
                    cadence.nextPresentationTimeUs(segmentOffsetUs)
                } else {
                    sample.presentationTimeUs
                }
                val lastTrackPtsUs = lastWrittenPtsUsByTrack[trackIndex]
                val presentationTimeUs = if (
                    lastTrackPtsUs != null && requestedPresentationTimeUs <= lastTrackPtsUs
                ) {
                    lastTrackPtsUs + 1L
                } else {
                    requestedPresentationTimeUs
                }
                require(sample.data.size <= writeBuffer.capacity()) {
                    "Combined media sample exceeds archive buffer capacity"
                }
                writeBuffer.clear()
                writeBuffer.put(sample.data)
                writeBuffer.flip()
                bufferInfo.set(0, sample.data.size, presentationTimeUs, sample.flags)
                muxer.writeSampleData(muxerTrackIndex, writeBuffer, bufferInfo)
                lastWrittenPtsUsByTrack[trackIndex] = presentationTimeUs
                segmentLastWrittenPtsUs = maxOf(segmentLastWrittenPtsUs, presentationTimeUs)
            }

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val muxerTrackIndex = muxerTrackMap[trackIndex]
                if (muxerTrackIndex == null) {
                    extractor.advance()
                    continue
                }

                readBuffer.clear()
                val sampleSize = extractor.readSampleData(readBuffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (segmentFirstPtsUs == Long.MIN_VALUE) {
                    segmentFirstPtsUs = if (sampleTimeUs >= 0L) sampleTimeUs else 0L
                }
                val presentationTimeUs = segmentOffsetUs + maxOf(0L, sampleTimeUs - segmentFirstPtsUs)
                val sampleData = ByteArray(sampleSize)
                readBuffer.position(0)
                readBuffer.get(sampleData)
                val sample = H264ArchiveAccessUnitAssembler.Sample(
                    data = sampleData,
                    presentationTimeUs = presentationTimeUs,
                    flags = extractor.sampleFlags,
                )
                val readySamples = h264Assemblers[trackIndex]?.offer(sample) ?: listOf(sample)
                readySamples.forEach { ready -> writeSample(trackIndex, ready) }
                extractor.advance()
            }

            h264Assemblers.forEach { (trackIndex, assembler) ->
                assembler.discardTrailingSei()
                CaltopoClient.CTDebug(
                    TAG,
                    "Normalized recording segment file=${inputFile.name}" +
                        " track=$trackIndex frames=${h264Cadences.getValue(trackIndex).pictureCount}" +
                        " mergedSei=${assembler.mergedSeiCount}" +
                        " discardedSei=${assembler.discardedSeiCount}" +
                        " fps=$NORMALIZED_VIDEO_FPS",
                )
            }

            maxOf(
                segmentLastWrittenPtsUs + 1L,
                h264Cadences.values.maxOfOrNull { cadence ->
                    cadence.endOffsetUs(segmentOffsetUs)
                } ?: segmentOffsetUs,
            )
        } finally {
            extractor.release()
        }
    }

    private fun readTrackLayout(inputFile: File): TrackLayout {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)
        return try {
            val trackIndices = mutableListOf<Int>()
            val formats = LinkedHashMap<Int, MediaFormat>()
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) continue
                trackIndices += trackIndex
                formats[trackIndex] = format
            }
            TrackLayout(trackIndices = trackIndices, formats = formats)
        } finally {
            extractor.release()
        }
    }

    private fun maxSampleBufferSize(inputFiles: List<File>, trackIndices: List<Int>): Int {
        var maxSize = DEFAULT_SAMPLE_BUFFER_SIZE
        inputFiles.forEach { inputFile ->
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)
            try {
                trackIndices.forEach { trackIndex ->
                    if (trackIndex >= extractor.trackCount) return@forEach
                    val format = extractor.getTrackFormat(trackIndex)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSize = maxOf(maxSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            } finally {
                extractor.release()
            }
        }
        return maxSize
    }

    private fun tempMergedRecordingFile(context: Context, streamPath: String, firstFragment: File): File {
        val timestamp = recordingTimestamp(firstFragment)
        val designator = archiveDesignator(streamPath)
        val safeTimestamp = timestamp.ifBlank { fallbackTimestamp() }
        val fileName = "${designator}_${safeTimestamp}.tmp.mp4"
        return File(getMergedRecordingRoot(context), "$streamPath/$fileName").apply {
            parentFile?.mkdirs()
        }
    }

    private fun getMergedRecordingRoot(context: Context): File =
        File(context.filesDir, "mediamtx-recordings-merged")

    private fun archiveRecordingFileName(streamPath: String, firstFragment: File): String {
        val designator = archiveDesignator(streamPath)
        val timestamp = recordingTimestamp(firstFragment).ifBlank { fallbackTimestamp() }
        return "${designator}_${timestamp}.mp4"
    }

    private fun archiveDesignator(streamPath: String): String =
        streamPath.substringAfterLast('/').ifBlank { "recording" }

    private fun recordingTimestamp(firstFragment: File): String {
        return archiveTimestampFromFragmentName(firstFragment.name).orEmpty()
    }

    private fun fallbackTimestamp(): String =
        SimpleDateFormat("ddMMMyyyy_HHmmss_z", Locale.US).format(Date())

    internal fun archiveTimestampFromFragmentName(
        name: String,
        localZone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        Regex("""(?:^|_)(\d{2}[A-Z][a-z]{2}\d{4}_\d{6}_[A-Za-z]{1,8}[+-]\d{4})(?:-|\.)""")
            .find(name)
            ?.let { return it.groupValues[1] }
        Regex("""(?:^|_)(\d{2}[A-Z][a-z]{2}\d{4}_\d{6}_[A-Za-z]{1,8})(?:-|\.)""")
            .find(name)
            ?.let { return it.groupValues[1] }
        Regex("""(?:^|_)(\d{2}[A-Z][a-z]{2}\d{4}_\d{6})(?:-|\.)""")
            .find(name)
            ?.let { match ->
                return try {
                    LocalDateTime.parse(
                        match.groupValues[1],
                        DateTimeFormatter.ofPattern("ddMMMyyyy_HHmmss", Locale.US),
                    ).atZone(localZone).format(
                        DateTimeFormatter.ofPattern("ddMMMyyyy_HHmmss_z", Locale.US)
                    )
                } catch (_: DateTimeException) {
                    null
                }
            }
        val match = Regex(
            """(?:^|_)(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})"""
        ).find(name) ?: return null
        return try {
            val utcTimestamp = LocalDateTime.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt(),
                match.groupValues[6].toInt(),
            )
            utcTimestamp
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(localZone)
                .format(DateTimeFormatter.ofPattern("ddMMMyyyy_HHmmss_z", Locale.US))
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun ensureTargetDir(root: DocumentFile, streamPath: String): DocumentFile {
        var current = root
        streamPath
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                current = current.findFile(segment)
                    ?: current.createDirectory(segment)
                    ?: throw IllegalStateException("Unable to create recording directory '$segment'")
            }
        return current
    }

    private fun availableTargetName(targetDir: DocumentFile, preferredName: String): String {
        if (targetDir.findFile(preferredName) == null) return preferredName
        val extension = preferredName.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isEmpty()) preferredName else preferredName.removeSuffix(".$extension")
        for (sequence in 2..999) {
            val candidate = if (extension.isEmpty()) "$stem-$sequence" else "$stem-$sequence.$extension"
            if (targetDir.findFile(candidate) == null) return candidate
        }
        throw IllegalStateException("Unable to allocate recording filename for '$preferredName'")
    }

    private fun copyFileInto(
        resolver: ContentResolver,
        sourceFile: File,
        targetDir: DocumentFile,
        targetName: String = sourceFile.name,
    ) {
        val existing = targetDir.findFile(targetName)
        val target = existing ?: targetDir.createFile(mimeTypeFor(targetName), targetName)
        if (target == null) {
            throw IllegalStateException("Unable to create recording file '$targetName'")
        }

        org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt()?.let { org.ncssar.rid2caltopo.app.FlightStorage.prepareWrite(it, sourceFile.length()) }
        FileInputStream(sourceFile).use { input ->
            resolver.openOutputStream(target.uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Unable to open output stream for '$targetName'")
        }
        org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt()?.let { context ->
            org.ncssar.rid2caltopo.app.FlightStorage.documentChanged(context, target)
        }
    }

    private fun deleteEmptyDirectories(root: File): Boolean {
        if (!root.exists()) return true
        if (root.isFile) return false

        var isEmpty = true
        root.listFiles()?.forEach { child ->
            val childEmpty = if (child.isDirectory) deleteEmptyDirectories(child) else false
            if (child.isDirectory && childEmpty) {
                if (!child.delete()) {
                    isEmpty = false
                }
            } else {
                isEmpty = false
            }
        }
        return isEmpty
    }

    private fun isMediaFragment(file: File): Boolean =
        mimeTypeFor(file.name).startsWith("video/")

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "fmp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

    private data class TrackLayout(
        val trackIndices: List<Int>,
        val formats: Map<Int, MediaFormat>,
    )
}
