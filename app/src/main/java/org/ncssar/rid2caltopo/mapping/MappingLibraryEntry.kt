package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.content.Context
import android.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** One library row, whether backed by a flight or an imported map. */
internal data class MappingLibraryEntry(
    val id: String, val name: String, val capturedAt: String, val plannedAt: String,
    val hasPlan: Boolean, val photoCount: Int, val has2D: Boolean, val has3D: Boolean,
    val stage: String, val storage: MappingStorageUsage?, val center: Pair<Double, Double>?,
    val flight: MappingFlightStatus? = null, val layer: MappingLayer? = null
) {
    val sortTime: LocalDateTime? get() = localCapture(capturedAt) ?: plannedTime(plannedAt)
    val timeLabel: String get() = localCapture(capturedAt)?.let { "Captured: ${display.format(it)}" }
        ?: if (photoCount > 0 || has2D || has3D) "Capture time unavailable" + (plannedTime(plannedAt)?.let { " · planned ${display.format(it)}" } ?: "")
        else plannedTime(plannedAt)?.let { "Not captured · planned ${display.format(it)}" } ?: "Capture time unavailable"
    val imageStatus: String get() = if (layer != null) "$photoCount photos used in map · Originals: not stored here" else if (photoCount > 0) "$photoCount originals on device" else "no originals on device"
    val statusLabel: String get() = "Plan: ${if (hasPlan) "prepared" else "unavailable"} · Images: ${imageStatus} · 2D: ${if (has2D) "available" else "not available"} · 3D: ${if (has3D) "available" else "not available"}"
    fun distanceLabel(location: Pair<Double, Double>?): String {
        if (location == null) return "Distance: current location unavailable"
        val destination = center ?: return "Distance: mission location unavailable"
        val lat1 = Math.toRadians(location.first); val lat2 = Math.toRadians(destination.first)
        val delta = Math.toRadians(destination.second - location.second)
        val a = sin((lat2-lat1)/2).pow(2) + cos(lat1)*cos(lat2)*sin(delta/2).pow(2)
        val meters = 6371000.0 * 2 * asin(sqrt(a.coerceIn(0.0,1.0)))
        return "Distance to area center: " + Units.current.distance(meters)
    }
    companion object {
        private val display = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
        fun localCapture(value: String): LocalDateTime? = runCatching {
            LocalDateTime.parse(value.replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"), "$1-$2-$3").replace(' ', 'T'))
        }.getOrNull()
        fun plannedTime(value: String): LocalDateTime? = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
        fun sorted(entries: List<MappingLibraryEntry>, newestFirst: Boolean): List<MappingLibraryEntry> {
            val known = entries.filter { it.sortTime != null }.sortedWith(compareBy<MappingLibraryEntry> { it.sortTime }.thenBy { it.id })
            return (if (newestFirst) known.reversed() else known) + entries.filter { it.sortTime == null }.sortedBy { it.id }
        }
    }
}

internal object MappingLibraryRepository {
    private val captureCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private fun json(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()
    fun capture(root: File): String {
        val records = File(root,"inventory").listFiles().orEmpty().mapNotNull(::json).filter { it.optBoolean("complete") }
            .sortedBy { it.optString("name") }
        // Original filenames are chronological for the supported controller. Inspect one header,
        // caching by its identity, without decoding photos during each library refresh.
        val record = records.firstOrNull() ?: return ""
        val digest = record.optString("sha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return ""
        val file = File(root,"originals/$digest.jpg")
        val signature = "$digest:${file.length()}:${file.lastModified()}"
        captureCache[root.path]?.takeIf { it.first == signature }?.let { return it.second }
        val time = runCatching { ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty() }.getOrDefault("")
        captureCache[root.path] = signature to time
        return time
    }
    private fun center(points: List<List<Double>>): Pair<Double,Double>? {
        val good = points.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() && it[0] in -180.0..180.0 && it[1] in -90.0..90.0 }
        if (good.isEmpty()) return null
        return (good.minOf { it[1] } + good.maxOf { it[1] })/2 to (good.minOf { it[0] } + good.maxOf { it[0] })/2
    }
    fun list(context: Context, live: MappingProcessingState?, includeStorage: Boolean = true): List<MappingLibraryEntry> {
        val flights = MappingFlightRepository.list(context.filesDir, live=live, includeStorage=includeStorage)
        val owned = flights.flatMap { it.resultIds }.toSet()
        val entries = flights.map { flight ->
            val root = File(context.filesDir,"mapping-missions/${flight.id}")
            val mission = json(File(root,"mission.json"))
            val reports = flight.resultIds.mapNotNull { json(File(context.filesDir,"mapping-layers/$it/layer.json")) }
            val captureTime = capture(root).takeIf { MappingLibraryEntry.localCapture(it) != null } ?: reports.map { it.optString("captureTimeLocal") }.filter { MappingLibraryEntry.localCapture(it) != null }.minOrNull().orEmpty()
            val points = runCatching {
                val ring = mission!!.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
                (0 until ring.length()).map { index -> ring.getJSONArray(index).let { listOf(it.getDouble(0), it.getDouble(1)) } }
            }.getOrDefault(emptyList())
            val has3D = flight.resultIds.any { MappingLibraryFiles.contains(File(context.filesDir,"mapping-layers/$it/surface"), "glb") }
            MappingLibraryEntry("flight:${flight.id}",flight.title,captureTime,flight.createdAt,flight.hasRoute,flight.photoCount,
                flight.resultIds.any { MappingLibraryFiles.contains(File(context.filesDir,"mapping-layers/$it"), "png") },has3D,flight.stage,flight.storage,center(points),flight=flight)
        }
        return entries + MappingLayer.allLocal(context).filterNot { it.id in owned }.map { layer ->
            val usage = if (includeStorage) MappingMissionStorage.layer(context.filesDir,layer.id) else null
            val bounds = layer.bounds
            MappingLibraryEntry("layer:${layer.id}",layer.title,layer.capturedAt,"",false,layer.photoCount,true,
                MappingLibraryFiles.contains(File(context.filesDir,"mapping-layers/${layer.id}/surface"), "glb"),
                "Imported map · originals not stored here",usage,center(layer.assignmentBoundary) ?: if(bounds.size==4) (bounds[1]+bounds[3])/2 to (bounds[0]+bounds[2])/2 else null,layer=layer)
        }
    }
}

/** Match the map's chosen reference without presenting an old or manual fix as live GPS. */
internal data class MappingLibraryLocation(val coordinates:Pair<Double,Double>,val note:String) {
    companion object {
        fun from(latitude:Double,longitude:Double,ageMillis:Long?,manual:Boolean):MappingLibraryLocation? {
            if(!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0)return null
            val note=when {
                manual -> "manual map location"
                ageMillis==null || ageMillis<0 -> "last known location; age unavailable"
                ageMillis<=120000 -> ""
                ageMillis<3600000 -> "last known location, ${ageMillis/60000} min old"
                ageMillis<86400000 -> "last known location, ${ageMillis/3600000} hr old"
                else -> "last known location, ${ageMillis/86400000} days old"
            }
            return MappingLibraryLocation(latitude to longitude,note)
        }
    }
}
