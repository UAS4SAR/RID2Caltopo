package org.ncssar.rid2caltopo.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class ManagedVideoSessionRecording(
    val sessionId: String,
    val droneDesignator: String,
    val file: File,
    val recordedAt: Instant,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val codec: String,
    val bitrateBps: Long = 0L,
)

/**
 * App-private catalog of captures made during the current incident.
 *
 * MediaMTX fragments still follow the normal archive path. A merged copy is
 * retained here across app restarts during the same incident so an authorized
 * browser can request direct WebRTC playback without cloud media storage.
 */
object ManagedVideoSessionRecordingCatalog {
    private const val TAG = "ManagedVideoRecordingCatalog"
    private const val ROOT_NAME = "managed-video-session-recordings"
    private const val MAX_ARCHIVE_RECOVERY_RECORDINGS = 4
    private const val MIN_FREE_SPACE_AFTER_RECOVERY_BYTES = 256L * 1024L * 1024L
    internal const val TRACK_ASSOCIATION_GRACE_MS = 30_000L
    private var initializedIncidentKey = ""
    private var recoveredArchiveKey = ""
    private val recordingMetadataCache = mutableMapOf<String, CachedRecordingMetadata>()

    private data class CachedRecordingMetadata(
        val fingerprint: RecordingFingerprint,
        val recording: ManagedVideoSessionRecording?,
    )

    internal data class RecordingFingerprint(
        val length: Long,
        val lastModified: Long,
    )

    @Synchronized
    fun initialize(context: Context) {
        val incidentKey = currentIncidentKey()
        if (initializedIncidentKey == incidentKey) return
        activeRoot(context, incidentKey).mkdirs()
        initializedIncidentKey = incidentKey
    }

    @Synchronized
    fun retain(
        context: Context,
        mergedFile: File,
        streamPath: String,
    ): File? {
        initialize(context)
        if (!mergedFile.isFile || mergedFile.length() <= 0L) return null
        val safePath = streamPath
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "recording" }
        val sessionId = ManagedVideoStreamPresence.latestSessionId(
            streamPath,
            streamPath.substringAfterLast('/'),
        )
            ?: sessionIdForPath("$safePath/${mergedFile.name}")
        val destination = File(activeRoot(context), "${safePath}__${sessionId}__${mergedFile.name}")
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        return try {
            mergedFile.copyTo(temporary, overwrite = true)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            destination.setLastModified(System.currentTimeMillis())
            destination
        } catch (error: Exception) {
            temporary.delete()
            CaltopoClient.CTWarn(TAG, "Unable to retain current-session recording: ${error.message}")
            null
        }
    }

    @Synchronized
    fun snapshot(context: Context): List<ManagedVideoSessionRecording> {
        initialize(context)
        val files = activeRoot(context)
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .toList()
        val currentPaths = files.mapTo(mutableSetOf()) { it.absolutePath }
        recordingMetadataCache.keys.retainAll(currentPaths)
        return files
            .asSequence()
            .mapNotNull(::readRecordingCached)
            .sortedByDescending { it.recordedAt }
            .take(20)
            .toList()
    }

    @Synchronized
    fun find(context: Context, sessionId: String): ManagedVideoSessionRecording? =
        snapshot(context).firstOrNull { it.sessionId == sessionId }

    /**
     * Return the operator-facing archive name without the catalog's private
     * designator/session identity prefix or the remux staging marker.
     */
    fun downloadFileName(recording: ManagedVideoSessionRecording): String =
        downloadFileName(recording.file.name)

    internal fun downloadFileName(catalogFileName: String): String {
        val parts = catalogFileName.split("__", limit = 3)
        val embeddedName = parts.getOrNull(2)
            ?.takeIf {
                parts.getOrNull(1)?.let { sessionId ->
                    runCatching { UUID.fromString(sessionId) }.isSuccess
                } == true
            }
            ?: catalogFileName
        return embeddedName.replace(Regex("(?i)\\.tmp(?=\\.mp4$)"), "")
    }

    suspend fun recoverCurrentIncidentFromArchive(context: Context) = withContext(Dispatchers.IO) {
        val mapId = CaltopoMap.GetMapId().trim()
        val archiveUri = CaltopoClient.GetArchiveUri()?.toString().orEmpty()
        if (mapId.isEmpty() || archiveUri.isEmpty()) return@withContext
        val recoveryKey = "$archiveUri|$mapId"
        synchronized(this@ManagedVideoSessionRecordingCatalog) {
            if (recoveredArchiveKey == recoveryKey) return@withContext
        }
        val archiveRoot = CaltopoClient.GetArchiveDir()
            ?.takeIf { it.isDirectory }
            ?: return@withContext
        val matchingDesignatorsByDirectory = archiveRoot.listFiles()
            .asSequence()
            .filter { it.isDirectory && it.name.orEmpty().startsWith("tracks-") }
            .mapNotNull { dayDirectory ->
                val designators = matchingArchiveDesignators(
                    dayDirectory.listFiles()
                        .asSequence()
                        .filter { it.isFile && it.name.orEmpty().endsWith(".json", true) }
                        .mapNotNull { metadata ->
                            runCatching {
                                context.contentResolver.openInputStream(metadata.uri)
                                    ?.bufferedReader()
                                    ?.use { it.readText() }
                            }.getOrNull()
                        }
                        .toList(),
                    mapId,
                )
                designators.takeIf { it.isNotEmpty() }?.let { dayDirectory to it }
            }
            .toList()
        val archiveRecordings = matchingDesignatorsByDirectory
            .flatMap { (dayDirectory, designators) ->
                dayDirectory.listFiles()
                    .asSequence()
                    .filter { it.isDirectory }
                    .filter { directory ->
                        directory.name.orEmpty().trim().lowercase() in designators
                    }
                    .flatMap { it.listFiles().asSequence() }
                    .filter { it.isFile && it.name.orEmpty().endsWith(".mp4", true) }
                    .toList()
            }
            .distinctBy { it.uri.toString() }
            .sortedByDescending(DocumentFile::lastModified)
            .take(MAX_ARCHIVE_RECOVERY_RECORDINGS)
        val destinationRoot = activeRoot(context).also(File::mkdirs)
        var recovered = 0
        for (source in archiveRecordings) {
            val designator = source.parentFile?.name.orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "recording" }
            val sessionId = sessionIdForPath(source.uri.toString())
            val destination = File(
                destinationRoot,
                "${designator}__${sessionId}__${source.name ?: "recording.mp4"}",
            )
            if (destination.isFile && destination.length() > 0L) continue
            val sourceLength = source.length().coerceAtLeast(0L)
            if (destinationRoot.usableSpace - sourceLength < MIN_FREE_SPACE_AFTER_RECOVERY_BYTES) {
                CaltopoClient.CTWarn(TAG, "Archive recording recovery stopped: insufficient private storage")
                break
            }
            val temporary = File(destinationRoot, ".${destination.name}.tmp")
            val copied = runCatching {
                context.contentResolver.openInputStream(source.uri).use { input ->
                    requireNotNull(input) { "Archive recording could not be opened" }
                    FileOutputStream(temporary).use(input::copyTo)
                }
                check(temporary.length() > 0L) { "Archive recording was empty" }
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                source.lastModified().takeIf { it > 0L }?.let(destination::setLastModified)
            }.onFailure { error ->
                temporary.delete()
                CaltopoClient.CTWarn(TAG, "Unable to recover archived recording: ${error.message}")
            }.isSuccess
            if (copied) recovered += 1
        }
        synchronized(this@ManagedVideoSessionRecordingCatalog) {
            recoveredArchiveKey = recoveryKey
        }
        CaltopoClient.CTInfo(
            TAG,
            "Archive recording recovery matched=${archiveRecordings.size} copied=$recovered map=$mapId",
        )
    }

    internal fun matchingArchiveDesignators(
        metadataDocuments: List<String>,
        mapId: String,
    ): Set<String> = metadataDocuments.asSequence()
        .flatMap { document ->
            runCatching {
                val features = JSONObject(document).optJSONArray("features")
                    ?: return@runCatching emptySequence<String>()
                (0 until features.length()).asSequence().mapNotNull { index ->
                    val properties = features.optJSONObject(index)?.optJSONObject("properties")
                    val r2c = properties?.optJSONObject("r2c_prop")
                    if (r2c?.optString("map_id")?.trim() != mapId) return@mapNotNull null
                    r2c.optString("mid").trim().lowercase().takeIf(String::isNotEmpty)
                }
            }.getOrDefault(emptySequence())
        }
        .toSet()

    @JvmStatic
    fun findForTrack(
        context: Context,
        trackStartedAtMs: Long,
        trackEndedAtMs: Long,
        vararg candidates: String,
    ): ManagedVideoSessionRecording? = selectForTrack(
        recordings = snapshot(context),
        trackStartedAtMs = trackStartedAtMs,
        trackEndedAtMs = trackEndedAtMs,
        candidates = candidates.asList(),
    )

    /**
     * Select the recording that overlaps this exact track interval. Matching
     * only the designator is unsafe because a later track can otherwise reuse
     * the previous flight's most recent recording.
     */
    internal fun selectForTrack(
        recordings: List<ManagedVideoSessionRecording>,
        trackStartedAtMs: Long,
        trackEndedAtMs: Long,
        candidates: List<String>,
    ): ManagedVideoSessionRecording? {
        if (trackStartedAtMs <= 0L || trackEndedAtMs < trackStartedAtMs) return null
        val normalized = candidates
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalized.isEmpty()) return null
        val allowedStart = trackStartedAtMs - TRACK_ASSOCIATION_GRACE_MS
        val allowedEnd = trackEndedAtMs + TRACK_ASSOCIATION_GRACE_MS
        return recordings
            .asSequence()
            .filter { it.droneDesignator.trim().lowercase() in normalized }
            .map { recording ->
                val recordingEndMs = recording.recordedAt.toEpochMilli()
                val recordingStartMs = recordingEndMs - recording.durationMs.coerceAtLeast(0L)
                val overlapsAllowedWindow = recordingEndMs >= allowedStart && recordingStartMs <= allowedEnd
                if (!overlapsAllowedWindow) return@map null
                val overlapMs = (
                    minOf(trackEndedAtMs, recordingEndMs) -
                        maxOf(trackStartedAtMs, recordingStartMs)
                    ).coerceAtLeast(0L)
                val boundaryDistanceMs = minOf(
                    kotlin.math.abs(recordingStartMs - trackStartedAtMs),
                    kotlin.math.abs(recordingEndMs - trackEndedAtMs),
                )
                Triple(recording, overlapMs, boundaryDistanceMs)
            }
            .filterNotNull()
            .sortedWith(
                compareByDescending<Triple<ManagedVideoSessionRecording, Long, Long>> { it.second }
                    .thenBy { it.third }
                    .thenByDescending { it.first.recordedAt }
            )
            .map { it.first }
            .firstOrNull()
    }

    fun advertisement(
        recording: ManagedVideoSessionRecording,
        thumbnail: ManagedVideoThumbnail? = null,
    ): ManagedVideoStreamAdvertisement = ManagedVideoStreamAdvertisement(
        recording.sessionId,
        recording.droneDesignator,
        recording.width,
        recording.height,
        recording.fps,
        recording.bitrateBps,
        recording.codec,
        "recording",
        recording.recordedAt.toString(),
        recording.durationMs,
        thumbnail?.revision.orEmpty(),
        thumbnail?.jpegBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        recording.file.length(),
    )

    private fun readRecording(file: File): ManagedVideoSessionRecording? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val extractor = MediaExtractor()
            var formatFps = 0.0
            var codec = ""
            try {
                extractor.setDataSource(file.absolutePath)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("video/")) continue
                    codec = when (mime) { "video/avc" -> "h264"; "video/hevc" -> "hevc"; else -> mime.removePrefix("video/") }
                    formatFps = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: 0.0
                    break
                }
            } catch (error: Exception) {
                // Optional source details must not hide an otherwise readable recording.
                CaltopoClient.CTWarn(TAG, "Unable to read video track details for ${file.name}: ${error.message}")
            } finally { extractor.release() }
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull() ?: 0.0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val captureFps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: 0.0
            val fps = listOf(formatFps, captureFps, if (durationMs > 0) frameCount * 1000 / durationMs else 0.0)
                .firstOrNull { it.isFinite() && it > 0 } ?: 0.0
            val rotated = ((rotation % 180) + 180) % 180 == 90
            val nameParts = file.name.split("__", limit = 3)
            val designator = nameParts.firstOrNull().orEmpty().ifBlank { "Recording" }
            val embeddedSessionId = nameParts.getOrNull(1)
                ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ManagedVideoSessionRecording(
                sessionId = embeddedSessionId ?: sessionIdForPath(file.absolutePath),
                droneDesignator = designator,
                file = file,
                recordedAt = Instant.ofEpochMilli(file.lastModified()),
                durationMs = durationMs,
                width = if (rotated) height else width,
                height = if (rotated) width else height,
                fps = fps,
                codec = codec,
                bitrateBps = bitrate,
            )
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "Unable to inspect ${file.name}: ${error.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun readRecordingCached(file: File): ManagedVideoSessionRecording? {
        val path = file.absolutePath
        val fingerprint = RecordingFingerprint(
            length = file.length(),
            lastModified = file.lastModified(),
        )
        recordingMetadataCache[path]
            ?.takeIf { it.fingerprint == fingerprint }
            ?.let { return it.recording }
        return readRecording(file).also { recording ->
            recordingMetadataCache[path] = CachedRecordingMetadata(fingerprint, recording)
        }
    }

    private fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    internal fun sessionIdForPath(path: String): String =
        UUID.nameUUIDFromBytes(path.toByteArray()).toString()

    private fun activeRoot(
        context: Context,
        incidentKey: String = currentIncidentKey(),
    ): File {
        val scopeID = UUID.nameUUIDFromBytes(incidentKey.toByteArray()).toString()
        return File(root(context), scopeID)
    }

    private fun currentIncidentKey(): String {
        val mapId = CaltopoMap.GetMapId().trim()
        if (mapId.isNotEmpty()) return "map:$mapId"
        return "incident:${CaltopoClient.GetIncident().trim().lowercase()}"
    }
}

data class ManagedVideoThumbnail(
    val revision: String,
    val jpegBytes: ByteArray,
)
