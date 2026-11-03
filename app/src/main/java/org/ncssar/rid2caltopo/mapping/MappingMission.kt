package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.util.Xml
import org.json.JSONObject
import java.io.StringWriter
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

data class MappingLaunchLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float,
    val capturedAtMillis: Long
) {
    fun isUsable(): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0f && capturedAtMillis > 0L
}

/** Shared mission-plan knobs. Saved missions that predate them read as 10 m / 0.2 m / fill on. */
object MappingMissionDefaults {
    const val STANDARD_CELL_METERS=0.2
    val surfaceCellOptions=listOf(0.2,0.1,0.4)
    const val MINIMUM_MARGIN_METERS=10.0
    const val MAXIMUM_MARGIN_METERS=100.0
    /** Half a photo width at plan height, at least 10 m, never above the 100 m planner limit. */
    fun defaultMarginMeters(widthPixels:Int,gsdCmPerPixel:Double):Double {
        val width=widthPixels*(gsdCmPerPixel/100)
        return if(width.isFinite()) max(MINIMUM_MARGIN_METERS,0.5*width).coerceAtMost(MAXIMUM_MARGIN_METERS) else MINIMUM_MARGIN_METERS
    }
    fun validCell(value:Double)=surfaceCellOptions.any{abs(it-value)<1e-9}
    fun cell(parameters:JSONObject?):Double {
        val value=parameters?.optDouble("surfaceCellMeters",STANDARD_CELL_METERS) ?: STANDARD_CELL_METERS
        return surfaceCellOptions.firstOrNull{abs(it-value)<1e-9} ?: STANDARD_CELL_METERS
    }
    fun fillUnknown2D(parameters:JSONObject?)=parameters?.optBoolean("fillUnknown2DAreas",true) ?: true
    fun margin(parameters:JSONObject?):Double =
        (parameters?.optDouble("marginMeters",MINIMUM_MARGIN_METERS) ?: MINIMUM_MARGIN_METERS).let{if(it.isFinite())it else MINIMUM_MARGIN_METERS}.coerceIn(0.0,MAXIMUM_MARGIN_METERS)
}

/** Original CalTopo geometry is retained; the station performs the full topology check. */
class MappingMission(private val feature: JSONObject, val incidentId: String, val launchLocation: MappingLaunchLocation? = null, val mapName: String = "") {
    val id: String = UUID.randomUUID().toString()
    private val createdAt = Instant.now().toString()
    val title: String = feature.optJSONObject("properties")?.optString("title")?.ifBlank { "Assignment" } ?: "Assignment"

    init {
        require(incidentId.isNotBlank()) { "Connect an incident map first." }
        require(feature.optJSONObject("properties")?.optString("class") == "Assignment") { "Select a search assignment." }
        val geometry = feature.optJSONObject("geometry")
        val rings = geometry?.optJSONArray("coordinates")
        require(geometry?.optString("type") == "Polygon" && rings?.length() == 1) { "Select a single polygon without holes. Split multipart areas in CalTopo first." }
        val points = rings!!.getJSONArray(0)
        require(points.length() in 3..2000) { "Assignment needs 3–2000 vertices." }
        for (i in 0 until points.length()) {
            val point = points.getJSONArray(i)
            val lon = point.getDouble(0); val lat = point.getDouble(1)
            require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -85.0..85.0) { "Assignment contains unsupported coordinates." }
        }
    }

    fun json(thermal: Boolean, desiredGsdCmPerPixel: Double? = null): JSONObject =
        json(MappingResolution.profiles.first { it.id == if (thermal) "m4td.thermal" else "m4td.visible.wide" }, desiredGsdCmPerPixel)

    fun json(profile: MappingResolution.Profile, desiredGsdCmPerPixel: Double? = null, capture:MappingCaptureProfile.Profile=MappingCaptureProfile.nadir, canopyHeightMeters:Double=20.0, maximumObstacleHeightMeters:Double?=null, clearanceMeters:Double=15.0, maximumCaptureSpeedMetersPerSecond:Double?=null,transitSpeedMetersPerSecond:Double?=null,marginMeters:Double?=null,surfaceCellMeters:Double=MappingMissionDefaults.STANDARD_CELL_METERS,fillUnknown2DAreas:Boolean=true): JSONObject = JSONObject().apply {
        val camera = profile.sensor
        val thermal = profile.thermal
        require(capture==MappingCaptureProfile.nadir||profile.isVisibleWide){"Oblique comparisons require the visible-wide camera."}
        if(profile.isVisibleWide)put("captureProfile",capture.json())
        val targetGsd = desiredGsdCmPerPixel ?: MappingResolution.gsdCmPerPixel(60.0, camera)!!
        require(targetGsd.isFinite() && targetGsd in 0.2..100.0) { "Ground resolution must be between ${Units.current.resolution(0.2)} and ${Units.current.resolution(100.0)}." }
        val targetHeight = MappingResolution.heightMetersForGsd(targetGsd, camera)
            ?.takeIf { it in 5.0..500.0 } ?: throw IllegalArgumentException("Ground resolution must produce a mission height from ${Units.current.length(5.0)} to ${Units.current.length(500.0)}.")
        put("type", "r2c_mapping_mission"); put("version", 1); put("missionId", id)
        put("incidentId", incidentId); put("mapName", mapName); put("assignmentId", feature.getString("id")); put("title", title)
        put("createdAt", createdAt); put("feature", JSONObject(feature.toString()))
        // Logical intake target travels with the mission manifest. The eventual LAN URL and
        // one-time session credential are deliberately negotiated separately at transfer time.
        put("photoIntake", JSONObject().apply {
            put("missionId", id); put("collection", "originals")
            put("preserveOriginalBytes", true)
            put("captureMode", if (thermal) "thermal" else "visible")
            put("transport", "r2c_active_lan_session")
        })
        launchLocation?.takeIf(MappingLaunchLocation::isUsable)?.let {
            put("launchLocation", JSONObject().put("latitude",it.latitude).put("longitude",it.longitude)
                .put("horizontalAccuracyMeters",it.horizontalAccuracyMeters).put("capturedAt",java.time.Instant.ofEpochMilli(it.capturedAtMillis).toString()))
        }
        put("profile", if (thermal) "thermal" else "daylight-rgb")
        put("aircraftModel", profile.aircraft); put("cameraProfileId", profile.id); put("cameraName", profile.camera)
        put("cameraSpecifications", JSONObject().apply {
            put("widthPixels", camera.widthPixels); put("heightPixels", camera.heightPixels)
            put("diagonalFOVDegrees", camera.diagonalFovDegrees)
            if(!thermal)put("captureResolutionMode",profile.captureResolutionMode)
        })
        put("parameters", JSONObject().apply {
            require(clearanceMeters.isFinite() && clearanceMeters>0 && clearanceMeters<=500) { "Enter a positive clearance margin no greater than ${Units.current.length(500.0)}." }
            put("clearanceMeters",clearanceMeters)
            maximumObstacleHeightMeters?.let { obstacle ->
                require(obstacle.isFinite() && obstacle>=0 && obstacle<=targetHeight-clearanceMeters) { "Flight height must allow at least ${Units.current.length(clearanceMeters)} above the maximum obstacle top. Select a longer lens to preserve the requested detail, or choose a larger distance per pixel (coarser resolution)." }
                put("maximumObstacleHeightMeters",obstacle)
            }
            if(capture==MappingCaptureProfile.brushTrees) {
                require(canopyHeightMeters.isFinite() && canopyHeightMeters>=0 && canopyHeightMeters<=targetHeight-clearanceMeters) { "Allow at least ${Units.current.length(clearanceMeters)} above the planned canopy/terrain height." }
                put("planningSurfaceHeightMeters",canopyHeightMeters)
            }
            put("heightMeters", targetHeight); put("desiredGroundResolutionCmPerPixel", targetGsd)
            put("speedMetersPerSecond", if (thermal) 2 else 4)
            transitSpeedMetersPerSecond?.let { speed ->
                require(speed.isFinite() && speed in 1.0..15.0) { "Enter a transit speed from ${Units.current.length(1.0,2)}/s to ${Units.current.length(15.0,2)}/s." }
                put("transitSpeedMetersPerSecond",speed)
            }
            maximumCaptureSpeedMetersPerSecond?.let { speed ->
                require(speed.isFinite() && speed in 1.0..15.0) { "Enter a maximum capture speed from ${Units.current.length(1.0,2)}/s to ${Units.current.length(15.0,2)}/s." }
                put("maximumCaptureSpeedMetersPerSecond",speed)
            }
            put("forwardOverlapPercent", if (thermal) 85 else 80); put("sideOverlapPercent", if (thermal) 80 else 70)
            put("directionDegrees", 0)
            val margin=marginMeters ?: MappingMissionDefaults.defaultMarginMeters(camera.widthPixels,targetGsd)
            require(margin.isFinite() && margin in 0.0..MappingMissionDefaults.MAXIMUM_MARGIN_METERS) { "Edge overrun must be from ${Units.current.length(0.0)} to ${Units.current.length(MappingMissionDefaults.MAXIMUM_MARGIN_METERS)}." }
            put("marginMeters", margin)
            require(MappingMissionDefaults.validCell(surfaceCellMeters)) { "Choose Fine, Standard or Coarse 3D surface detail." }
            put("surfaceCellMeters", surfaceCellMeters); put("fillUnknown2DAreas", fillUnknown2DAreas)
            put("captureTrigger", "distance-v1")
            put("cameraHeadingPolicy", "aircraft-first-v1")
            put("orthophotoResolutionCm", if (thermal) 15 else minOf(5.0,targetGsd)); put("fastOrthophoto", !thermal)
        })
    }

    fun boundaryKml(): String {
        val points = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        val coordinates = (0 until points.length()).map { i ->
            val p = points.getJSONArray(i); "${p.getDouble(0)},${p.getDouble(1)},0"
        }.toMutableList()
        if (coordinates.first() != coordinates.last()) coordinates.add(coordinates.first())
        val writer = StringWriter(); val xml = Xml.newSerializer()
        xml.setOutput(writer); xml.startDocument("UTF-8", true)
        fun open(name: String) { xml.startTag("", name) }
        fun close(name: String) { xml.endTag("", name) }
        open("kml"); xml.attribute("", "xmlns", "http://www.opengis.net/kml/2.2")
        open("Document"); open("Placemark"); open("name"); xml.text(title); close("name")
        open("Polygon"); open("outerBoundaryIs"); open("LinearRing"); open("coordinates")
        xml.text(coordinates.joinToString(" ")); close("coordinates"); close("LinearRing"); close("outerBoundaryIs")
        close("Polygon"); close("Placemark"); close("Document"); close("kml"); xml.endDocument()
        return writer.toString()
    }
}
