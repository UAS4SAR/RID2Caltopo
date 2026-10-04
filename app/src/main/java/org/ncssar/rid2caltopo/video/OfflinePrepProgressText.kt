package org.ncssar.rid2caltopo.video

import java.net.ConnectException
import org.ncssar.rid2caltopo.video.surface.SurfaceServerUnreachableException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.floor

/** Where AOL preparation is; drives the AOL progress line. */
internal enum class OfflinePrepAolStage { Waiting, Files, Tiles, Complete, Reused, Failed }

/** Download Map job state, used only to choose wording. */
internal enum class OfflinePrepRunState { Running, Cancelling, Cancelled, Finished, Failed }

/**
 * Pure wording for the Download Map progress panel. iOS mirrors this in
 * OperationalOfflineProgressText (R2CCore); keep the two in step.
 */
internal object OfflinePrepProgressText {
    const val FAILED_AOL_PHASE = "Failed: AOL"

    /** Closes the window while the download keeps running in the background. */
    const val HIDE_LABEL = "Hide"
    /** Stops the running download. */
    const val CANCEL_DOWNLOAD_LABEL = "Cancel download"
    const val AUTO_CLOSE_DELAY_MSEC = 1_500L

    /**
     * Download Map closes itself after a fully successful run (map, terrain and any AOL processing),
     * but only when it is showing at that moment. Failures stay open for Retry. Auto-close counts
     * as Close: it ends the session and deletes raw AOL lidar files.
     */
    fun shouldAutoClose(phase: String, failTotal: Int, aolFailed: Boolean, dialogShown: Boolean): Boolean =
        dialogShown && phase == "Complete" && failTotal == 0 && !aolFailed

    /** Status for an active download shown outside the window (notification, Live View chip on iOS). */
    fun activeDownloadStatus(p: OfflinePrepProgress, inFlight: Boolean, cancelRequested: Boolean): String? = when {
        !inFlight -> null
        cancelRequested -> "Cancelling map download…"
        p.totalBytes <= 0L -> "Downloading map…"
        else -> "Downloading map… ${(p.fraction * 100.0).toInt().coerceIn(0, 100)}%"
    }

    fun runState(progress: OfflinePrepProgress, inFlight: Boolean, cancelRequested: Boolean): OfflinePrepRunState = when {
        inFlight && cancelRequested -> OfflinePrepRunState.Cancelling
        inFlight -> OfflinePrepRunState.Running
        progress.phase == "Cancelled" -> OfflinePrepRunState.Cancelled
        progress.phase.startsWith("Failed") && progress.aolFailure == null -> OfflinePrepRunState.Failed
        else -> OfflinePrepRunState.Finished
    }

    /** True when the headline should be shown as an error. */
    fun hasFailure(p: OfflinePrepProgress, state: OfflinePrepRunState): Boolean = when (state) {
        OfflinePrepRunState.Failed -> true
        OfflinePrepRunState.Finished -> partFailed(p, Part.Map) || partFailed(p, Part.Terrain) || p.aolFailure != null
        else -> false
    }

    fun headline(p: OfflinePrepProgress, state: OfflinePrepRunState): String = when (state) {
        OfflinePrepRunState.Running -> runningHeadline(p)
        OfflinePrepRunState.Cancelling -> "Cancelling download…"
        OfflinePrepRunState.Cancelled -> "Download cancelled"
        OfflinePrepRunState.Failed -> "Download failed — retry available"
        OfflinePrepRunState.Finished -> finishedHeadline(p)
    }

    /** One clearly labelled line per selected part, in download order. */
    fun partLines(p: OfflinePrepProgress, state: OfflinePrepRunState): List<String> = buildList {
        if (p.includesMap) {
            val layers = if (p.tileLayers.isEmpty()) "" else " (${p.tileLayers.joinToString(", ")})"
            add("Map tiles$layers: ${p.tileCompleted}/${p.tileTotal} — ${counts(p.hits, p.fetched, p.failed)}")
        }
        if (p.includesDem) {
            add("Terrain (DEM): ${p.demCompleted}/${p.demTotal} — ${counts(p.demHits, p.demFetched, p.demFailed)}")
        }
        if (p.includesAol) add(aolLine(p, state))
    }

    fun aolLine(p: OfflinePrepProgress, state: OfflinePrepRunState): String {
        val files = "lidar files ${p.aolFilesCompleted}/${p.aolFilesTotal}"
        val keptNote = if (p.aolFilesKept > 0) " — ${p.aolFilesKept} kept from last attempt" else ""
        val tiles = "building 1 m tiles ${p.aolTilesCompleted}/${p.aolTilesTotal}"
        return when (p.aolStage) {
            OfflinePrepAolStage.Waiting -> when {
                state != OfflinePrepRunState.Running && state != OfflinePrepRunState.Cancelling -> "AOL: not started"
                p.aolFilesTotal > 0 -> "AOL: waiting for map and terrain — ${p.aolFilesTotal} lidar files, ${p.aolTilesTotal} 1 m tiles"
                else -> "AOL: waiting for map and terrain"
            }
            OfflinePrepAolStage.Files -> "AOL: $files$keptNote"
            OfflinePrepAolStage.Tiles -> "AOL: $tiles"
            OfflinePrepAolStage.Complete -> "AOL: complete — ${p.aolTilesTotal} 1 m tiles from ${p.aolFilesTotal} lidar files"
            OfflinePrepAolStage.Reused -> "AOL: already prepared — using cached tiles"
            OfflinePrepAolStage.Failed -> {
                val reason = p.aolFailure ?: "stopped"
                val where = when {
                    p.aolFilesTotal > 0 && p.aolFilesCompleted >= p.aolFilesTotal -> " — stopped at $tiles"
                    p.aolFilesTotal > 0 -> " — stopped at $files"
                    else -> ""
                }
                "AOL failed: $reason$where"
            }
        }
    }

    /** Whole-job line, labelled as overall. Rate and ETA only while actively downloading. */
    fun overallLine(p: OfflinePrepProgress, state: OfflinePrepRunState, formatRate: (Long) -> String): String {
        val items = "${p.completed}/${p.total} items"
        return when (state) {
            OfflinePrepRunState.Running ->
                if (p.includesAol) "Overall: $items — map and terrain first, then AOL; time remaining varies with lidar size"
                else "Overall: ${percent(p, state)}% · $items · ${formatRate(p.bytesPerSec.toLong())}/s · ETA ${p.etaSeconds?.let(::duration) ?: "--:--"}"
            else -> "Overall: ${percent(p, state)}% · $items"
        }
    }

    /** Extra context: current AOL activity while running, or why the whole job stopped. */
    fun noteLine(p: OfflinePrepProgress, state: OfflinePrepRunState): String? = when {
        state == OfflinePrepRunState.Running && p.includesAol &&
            (p.aolStage == OfflinePrepAolStage.Files || p.aolStage == OfflinePrepAolStage.Tiles) &&
            p.phase.isNotBlank() -> "Now: ${p.phase}"
        state == OfflinePrepRunState.Failed && p.failure != null -> "Stopped: ${p.failure}"
        else -> null
    }

    fun percent(p: OfflinePrepProgress, state: OfflinePrepRunState): Int =
        if (state == OfflinePrepRunState.Finished && !hasFailure(p, state)) 100
        else floor(p.fraction * 100.0).toInt().coerceIn(0, 100)

    /**
     * Plain words for a transfer failure. [service] names who we were talking to,
     * e.g. "USGS". Walks the cause chain; falls back to the message.
     */
    fun describeFailure(error: Throwable, service: String): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        val timeoutMs = Regex("""after ([0-9][0-9,]*) ?ms""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        val http = Regex("""HTTP ([0-9]{3})""").find(text)?.groupValues?.get(1)
        return when {
            // Already worded for the operator, with the host (AOL lidar server that never accepted a connection).
            chain.any { it is SurfaceServerUnreachableException } -> chain.first { it is SurfaceServerUnreachableException }.message.orEmpty()
            chain.any { it is UnknownHostException } -> "couldn't reach $service (no network or name lookup failed)"
            chain.any { it is SocketTimeoutException } || timeoutMs != null -> {
                val after = timeoutMs?.let { " after ${seconds(it)}" }.orEmpty()
                if (timeoutMs != null || text.contains("connect", ignoreCase = true)) "couldn't reach $service (timed out$after)"
                else "$service stopped responding (timed out)"
            }
            chain.any { it is ConnectException || it is NoRouteToHostException } -> "couldn't reach $service"
            http != null -> "$service returned HTTP $http"
            else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        }
    }

    private enum class Part { Map, Terrain }

    private fun partFailed(p: OfflinePrepProgress, part: Part) = when (part) {
        Part.Map -> p.includesMap && p.failed > 0
        Part.Terrain -> p.includesDem && p.demFailed > 0
    }

    private fun runningHeadline(p: OfflinePrepProgress): String {
        if (p.includesAol && (p.aolStage == OfflinePrepAolStage.Files || p.aolStage == OfflinePrepAolStage.Tiles)) return "Preparing AOL…"
        if (p.total <= 0) return "Preparing…"
        val active = buildList {
            if (p.includesMap && p.tileCompleted < p.tileTotal) add("map tiles")
            if (p.includesDem && p.demCompleted < p.demTotal) add("terrain")
        }
        return when {
            active.isNotEmpty() -> "Downloading ${joinWords(active)}…"
            p.includesAol && p.aolStage == OfflinePrepAolStage.Waiting -> "Preparing AOL…"
            else -> "Finishing…"
        }
    }

    private fun finishedHeadline(p: OfflinePrepProgress): String {
        val complete = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (p.includesMap) { if (partFailed(p, Part.Map)) failed += "map tiles" else complete += "map" }
        if (p.includesDem) { if (partFailed(p, Part.Terrain)) failed += "terrain" else complete += "terrain" }
        val aolFailed = p.includesAol && p.aolFailure != null
        if (p.includesAol && !aolFailed) complete += "AOL"
        val segments = buildList {
            if (complete.isNotEmpty()) add("${joinWords(complete)} complete")
            if (failed.isNotEmpty()) add("${joinWords(failed)} had failures")
            if (aolFailed) add("AOL failed")
        }
        val text = segments.joinToString("; ").ifEmpty { "Download complete" }.replaceFirstChar { it.uppercase(Locale.US) }
        return if (failed.isNotEmpty() || aolFailed) "$text — retry available" else text
    }

    private fun counts(cached: Int, downloaded: Int, failed: Int) = "$cached cached, $downloaded downloaded, $failed failed"

    private fun joinWords(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }

    private fun seconds(ms: Long): String =
        if (ms % 1000L == 0L) "${ms / 1000L} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)

    fun duration(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val h = safe / 3600L
        val m = (safe % 3600L) / 60L
        val s = safe % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
