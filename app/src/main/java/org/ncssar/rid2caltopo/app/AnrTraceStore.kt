package org.ncssar.rid2caltopo.app

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.DocumentFileCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal const val ANR_TRACE_DIRECTORY = "anr-traces"
internal const val MAX_ANR_TRACE_BYTES = 4L * 1024L * 1024L

internal data class BoundedCopyResult(val bytesCopied: Long, val truncated: Boolean)

internal fun copyAtMost(input: InputStream, output: OutputStream, maxBytes: Long): BoundedCopyResult {
    require(maxBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (copied < maxBytes) {
        val requested = minOf(buffer.size.toLong(), maxBytes - copied).toInt()
        val read = input.read(buffer, 0, requested)
        if (read < 0) return BoundedCopyResult(copied, false)
        output.write(buffer, 0, read)
        copied += read
    }
    return BoundedCopyResult(copied, input.read() >= 0)
}

internal fun anrTraceFilename(timestamp: Long, pid: Int): String = "anr_${timestamp}_${pid}.txt"

internal object AnrTraceStore {
    private const val PREFS = "r2c_anr_trace_capture"

    @RequiresApi(Build.VERSION_CODES.R)
    fun capture(context: Context, info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR) return null
        val captureKey = "${info.timestamp}:${info.pid}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(captureKey, false)) return null
        val traceInput = info.traceInputStream ?: return null
        val filename = anrTraceFilename(info.timestamp, info.pid)
        val traceDir = File(context.filesDir, ANR_TRACE_DIRECTORY)
        org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(traceDir)
        if (!traceDir.exists() && !traceDir.mkdirs()) return null
        val traceFile = File(traceDir, filename)

        val result = traceInput.use { input ->
            FileOutputStream(traceFile).use { output ->
                copyAtMost(input, output, MAX_ANR_TRACE_BYTES)
            }
        }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retain(traceFile)
        prefs.edit().putBoolean(captureKey, true).apply()

        val archiveResult = runCatching {
            val trackDir = CaltopoClient.GetTodaysTrackDir() ?: return@runCatching false
            val document = DocumentFileCompat.createFileWithExactName(trackDir, "text/plain", filename)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
                traceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

        return "Captured ANR trace file=$filename bytes=${result.bytesCopied} " +
            "truncated=${result.truncated} archiveCopy=$archiveResult"
    }
}
