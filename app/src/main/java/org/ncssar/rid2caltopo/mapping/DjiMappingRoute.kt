package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Clipped, distance-triggered capture grids. Pilot 2 retains review/start authority. */
class DjiMappingRoute(val mission: JSONObject) {
    data class Point(val x: Double, val y: Double)
    data class Leg(val start: Point, val end: Point,val passIndex:Int=0)
    val id = mission.getString("missionId")
    val photoTag = "R2C" + id.replace("-", "").take(12)
    private val p = mission.getJSONObject("parameters")
    // Missing marker means a saved legacy route: retain its timed + arrival actions on resume.
    val distanceCapture = p.optString("captureTrigger").let { value ->
        require(value in listOf("", "distance-v1")) { "Unsupported capture trigger policy." }
        value == "distance-v1"
    }
    // Preserve the semantics of retained routes; corrected sequencing belongs to new plans.
    val aircraftFirstHeading = p.optString("cameraHeadingPolicy").let { value ->
        require(value in listOf("", "aircraft-first-v1")) { "Unsupported camera heading policy." }
        value == "aircraft-first-v1"
    }
    private val height = p.getDouble("heightMeters")
    private val clearance=if(p.has("clearanceMeters"))p.getDouble("clearanceMeters") else 15.0
    init {
        require(clearance.isFinite() && clearance>0 && clearance<=500) { "Invalid clearance margin." }
        if(p.has("maximumObstacleHeightMeters")) {
            val obstacle=p.getDouble("maximumObstacleHeightMeters")
            require(obstacle.isFinite() && obstacle>=0 && obstacle<=height-clearance) { "Flight height must allow at least ${Units.current.length(clearance)} above the maximum obstacle top." }
        }
    }
    val heightMeters get() = height
    val returnHomeHeightMeters = max(30.0, height)
    val groundResolutionCmPerPixel = p.getDouble("desiredGroundResolutionCmPerPixel")
    private val gsd = p.getDouble("desiredGroundResolutionCmPerPixel") / 100
    private val camera = mission.getJSONObject("cameraSpecifications")
    private val focalLength = MappingResolution.profiles.firstOrNull { it.id == mission.getString("cameraProfileId") }?.focalLength35mm
    private val lens = if (mission.getString("profile") == "thermal") "ir" else "visable"
    private val ring = mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
    private val lat0 = (0 until ring.length()).map { ring.getJSONArray(it).getDouble(1) }.average()
    private val lon0 = (0 until ring.length()).map { ring.getJSONArray(it).getDouble(0) }.average()
    private val scaleY = 111319.49079327358
    private val scaleX = scaleY * cos(Math.toRadians(lat0))
    val passes=MappingCaptureProfile.passes(mission)
    private val gap=mission.optJSONObject("gapCapturePlan")
    private val standardBrush=mission.optJSONObject("captureProfile")?.optString("id")==MappingCaptureProfile.brushTrees.id
    private val canopyAware=mission.optJSONObject("captureProfile")?.optString("id")==MappingCaptureProfile.canopyTargeted60.id
    private val targeted=canopyAware||mission.optJSONObject("captureProfile")?.optString("id")==MappingCaptureProfile.gapTargeted60.id
    private val targetedPassIndex=if(canopyAware)1 else 2
    private val targetSurfaceHeight=if(standardBrush) {
        require(gap==null)
        p.getDouble("planningSurfaceHeightMeters").also { require(it.isFinite()&&it in 0.0..height-clearance) { "Allow at least ${Units.current.length(clearance)} above canopy and terrain." } }
    } else if(targeted) {
        require(gap!=null&&gap.getInt("version")==(if(canopyAware)2 else 1)&&distanceCapture&&aircraftFirstHeading){"Gap capture requires a current target plan and corrected distance/heading policies."}
        require(gap.getJSONArray("assignmentBoundary").toString()==ring.toString()){"Gap plan belongs to a different target boundary."}
        require(java.util.UUID.fromString(gap.getString("sourceLayerId")).toString()==gap.getString("sourceLayerId"))
        MappingPhotoStore.checkDigest(gap.getString("sourceSurfaceSha256"))
        gap.getDouble("planningSurfaceHeightMeters").also{require(it.isFinite()&&it in 0.0..min(40.0,height-10.0)){"Invalid target surface height."}}
    } else { require(gap==null){"Gap capture legs require the gap-targeted profile."};0.0 }
    private fun referenceScale(pass:MappingCaptureProfile.Pass)=if(standardBrush||targeted&&(canopyAware||pass.pitchDegrees> -87))(height-targetSurfaceHeight)/height else 1.0
    private fun nearEdgeScale(pass:MappingCaptureProfile.Pass):Double {
        val halfVertical=camera.getInt("heightPixels")*gsd/(2*height)
        val tilt=Math.toRadians(90+pass.pitchDegrees)
        return 1/max(1.0,cos(tilt)+halfVertical*sin(tilt))
    }
    // The smallest local along-track derivative occurs at the near image edge.
    fun passSpacing(pass:MappingCaptureProfile.Pass)=camera.getInt("heightPixels") * gsd * (1 - pass.forwardOverlapPercent / 100) * nearEdgeScale(pass).pow(2)*referenceScale(pass)
    val spacing = passes.minOf{passSpacing(it)}
    // Pilot 2's route checklist requires a longer interval than our old one-second policy.
    // Round down to a controller-friendly tenth so the 7x evidence grid exports 1.0 m/s.
    val minimumCaptureInterval = if(focalLength != null)1.5 else 1.0
    private val captureSpeedLimit = (if(p.has("maximumCaptureSpeedMetersPerSecond"))p.getDouble("maximumCaptureSpeedMetersPerSecond") else p.getDouble("speedMetersPerSecond")).also {
        require(it.isFinite() && it in 1.0..15.0){"Maximum capture speed must be between 1 and 15 m/s."}
    }
    val speed = floor(min(captureSpeedLimit, spacing / minimumCaptureInterval) * 10 + 1e-9) / 10
    val transitSpeed = (if(p.has("transitSpeedMetersPerSecond"))p.getDouble("transitSpeedMetersPerSecond") else p.getDouble("speedMetersPerSecond")).also {
        require(it.isFinite() && it in 1.0..15.0){"Transit speed must be between 1 and 15 m/s."}
    }
    val interval = spacing / speed
    val legs: List<Leg>
    val photoEstimate: Int
    val durationEstimateSeconds: Int

    init {
        require(mission.getString("cameraProfileId") in MappingResolution.profiles.map { it.id }) {
            "Select a supported Matrice 4TD camera profile."
        }
        if(focalLength!=null) {
            val profile=MappingResolution.profiles.first { it.id==mission.getString("cameraProfileId") }
            require(camera.getInt("widthPixels")==profile.sensor.widthPixels && camera.getInt("heightPixels")==profile.sensor.heightPixels && abs(camera.getDouble("diagonalFOVDegrees")-profile.sensor.diagonalFovDegrees)<0.01) { "Telephoto mission camera geometry does not match the selected lens. Rebuild this plan." }
        }
        require(height in 5.0..500.0 && speed in 1.0..15.0 && transitSpeed in 1.0..15.0 && interval.isFinite()) { "Increase ground resolution or height so originals can be captured at least $minimumCaptureInterval seconds apart at the minimum route speed of 1 m/s." }
        val polygon = (0 until ring.length()).map { i ->
            val point = ring.getJSONArray(i)
            require(abs(point.getDouble(0) - lon0) < 0.2 && abs(point.getDouble(1) - lat0) < 0.2) { "Split this area into smaller local mapping assignments." }
            val x = (point.getDouble(0) - lon0) * scaleX; val y = (point.getDouble(1) - lat0) * scaleY
            Point(x,y)
        }.toMutableList().apply { if (first() == last()) removeAt(lastIndex) }
        require(polygon.size >= 3) { "Assignment has no usable area." }
        for (i in polygon.indices) for (j in i + 1 until polygon.size) {
            if (j == i + 1 || i == 0 && j == polygon.lastIndex) continue
            require(!intersects(polygon[i], polygon[(i + 1) % polygon.size], polygon[j], polygon[(j + 1) % polygon.size])) { "Repair the assignment's crossing/touching boundary first." }
        }
        val area = abs(polygon.indices.sumOf { i -> val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]; a.x * b.y - b.x * a.y }) / 2
        require(area >= 1.0) { "Assignment has no usable area." }
        val planned = mutableListOf<Leg>()
        passes.forEachIndexed { passIndex,pass ->
            if(targeted&&passIndex==targetedPassIndex) {
                val targets=gap!!.getJSONArray("captureLegs");require(targets.length() in 2..24&&targets.length()%2==0){"Gap plan requires paired north/south capture legs."}
                val targetLegs=(0 until targets.length()).map{i->val item=targets.getJSONObject(i)
                    fun point(name:String):Point {val p=item.getJSONArray(name);require(p.length()==2)
                        val lon=p.getDouble(0);val lat=p.getDouble(1);require(lon.isFinite()&&lat.isFinite())
                        val point=Point((lon-lon0)*scaleX,(lat-lat0)*scaleY)
                        require(point.x in polygon.minOf{it.x}-10..polygon.maxOf{it.x}+10&&point.y in polygon.minOf{it.y}-10..polygon.maxOf{it.y}+10){"Gap optical leg exceeds the target review margin."}
                        return point}
                    val start=point("targetStart");val end=point("targetEnd")
                    require(abs(start.x-end.x)<.001&&distance(start,end) in 10.0..120.0){"Gap captures must be bounded north/south legs."}
                    require(inside(Point((start.x+end.x)/2,(start.y+end.y)/2),polygon)){"Gap capture centre lies outside the target."}
                    Leg(start,end,passIndex)}
                targetLegs.chunked(2).forEach{(a,b)->require(distance(a.start,b.end)<.001&&distance(a.end,b.start)<.001){"Gap patch must have opposing views of the same target leg."}}
                val offset=(height-targetSurfaceHeight)*tan(Math.toRadians(90+pass.pitchDegrees))
                targetLegs.forEach{leg->val sign=if(leg.end.y>leg.start.y)1 else -1
                    planned.add(Leg(Point(leg.start.x,leg.start.y-sign*offset),Point(leg.end.x,leg.end.y-sign*offset),passIndex))}
                return@forEachIndexed
            }
            val passAngle=Math.toRadians(pass.directionDegrees)
            val oriented=polygon.map{Point(it.x*cos(passAngle)-it.y*sin(passAngle),it.x*sin(passAngle)+it.y*cos(passAngle))}
            fun unrotate(point:Point)=Point(point.x*cos(passAngle)+point.y*sin(passAngle),-point.x*sin(passAngle)+point.y*cos(passAngle))
            val minimum = oriented.minOf { it.x }; val maximum = oriented.maxOf { it.x }
            // Near-edge width is the narrowest part of the trapezoid. Keep nadir along-track
            // spacing as a conservative central-patch overlap; full oblique extent is not GSD.
            val tilt=Math.toRadians(90+pass.pitchDegrees)
            val narrowFactor=nearEdgeScale(pass)
            val crossSpacing = camera.getInt("widthPixels") * gsd * narrowFactor * (1 - pass.sideOverlapPercent / 100) * referenceScale(pass)
            require(crossSpacing > 0 && crossSpacing.isFinite()) { "Invalid side overlap." }
            val count = ceil((maximum - minimum) / crossSpacing).toInt().coerceAtLeast(1)
            require(count <= 500) { "Split this mission into smaller flight areas." }
            for (column in 0 until count) {
                val x = minimum + (column + 0.5) * (maximum - minimum) / count
                val hits = oriented.indices.mapNotNull { i ->
                    val a = oriented[i]; val b = oriented[(i + 1) % oriented.size]
                    if ((a.x <= x && b.x > x) || (b.x <= x && a.x > x)) a.y + (x - a.x) * (b.y - a.y) / (b.x - a.x) else null
                }.sorted()
                require(hits.size % 2 == 0) { "Assignment cannot be clipped into a mapping grid." }
                val pairs = hits.chunked(2).filter { it[1] - it[0] > 0.2 }
                val ordered = if (column % 2 == 0) pairs else pairs.reversed()
                ordered.forEach { ends ->
                    val margin = p.getDouble("marginMeters").coerceIn(0.0, 100.0)
                    val a = Point(x, ends[0] - margin); val b = Point(x, ends[1] + margin)
                    val start=if(column%2==0)a else b;val end=if(column%2==0)b else a
                    // Centre the optical ray on the assignment, including on reversed legs.
                    // These aircraft offsets can extend beyond the assignment; pilot clearance is required.
                    val offset=height*referenceScale(pass)*tan(tilt)*(if(column%2==0)1 else -1)
                    planned.add(Leg(unrotate(Point(start.x,start.y-offset)),unrotate(Point(end.x,end.y-offset)),passIndex))
                }
            }
        }
        require(planned.isNotEmpty() && planned.size <= 500) { "No usable grid or too many route points. Split the assignment." }
        legs = planned
        photoEstimate = legs.sumOf { ceil(distance(it.start, it.end) / passSpacing(passes[it.passIndex])).toInt() + 1 }
        val path = legs.flatMap { listOf(it.start, it.end) }
        durationEstimateSeconds = ceil(path.zipWithNext().sumOf { distance(it.first, it.second) } / speed + legs.size * 4).toInt()
    }

    fun coordinate(point: Point): Pair<Double, Double> {
        return lon0 + point.x / scaleX to lat0 + point.y / scaleY
    }
    fun summary() = JSONObject().put("capturePasses",org.json.JSONArray(passes.mapIndexed{index,pass->JSONObject()
            .put("gimbalPitchDegrees",pass.pitchDegrees).put("directionDegrees",pass.directionDegrees)
            .put("forwardOverlapPercent",pass.forwardOverlapPercent).put("sideOverlapPercent",if(targeted&&index==targetedPassIndex)JSONObject.NULL else pass.sideOverlapPercent)
            .put("sideOverlapPolicy",if(targeted&&index==targetedPassIndex)"targeted opposing single lines; no uniform side-overlap claim" else "regular grid")
            .put("captureSpacingMeters",passSpacing(pass)).put("captureIntervalSeconds",passSpacing(pass)/speed).put("legCount",legs.count{it.passIndex==index}).put("opticalCentreOffsetMeters",height*referenceScale(pass)*tan(Math.toRadians(90+pass.pitchDegrees)))
            .put("planningSurfaceHeightMeters",if(standardBrush||targeted&&(canopyAware||index==targetedPassIndex))targetSurfaceHeight else 0.0)
            .put("centreAcrossCmPerPixel",gsd*referenceScale(pass)*100/cos(Math.toRadians(90+pass.pitchDegrees)))
            .put("centreAlongCmPerPixel",gsd*referenceScale(pass)*100/cos(Math.toRadians(90+pass.pitchDegrees)).pow(2))}))
        .put("captureWidthPixels",camera.getInt("widthPixels")).put("captureHeightPixels",camera.getInt("heightPixels"))
        .put("captureTrigger",if(distanceCapture)"multipleDistance" else "multipleTiming").put("photoEstimateIsUpperBound",true)
        .put("cameraHeadingPolicy",if(aircraftFirstHeading)"aircraft-first-v1" else "legacy-gimbal-first")
        .put("routeEndMarginMeters",p.getDouble("marginMeters").coerceIn(0.0,100.0)).put("photoLimit",10000).put("withinPhotoLimit",photoEstimate<=10000)
        .put("flightEstimateIncludes","capture legs, connections and 4 s per leg; excludes takeoff, landing, launch transit, wind and reserve").put("estimatedPhotos", photoEstimate).put("estimatedFlightSeconds", durationEstimateSeconds)
        .put("transitSpeedMetersPerSecond", transitSpeed).put("speedMetersPerSecond", speed).put("captureIntervalSeconds", interval).put("waypointCount", legs.size * 2)
        .put("heightMeters", heightMeters).put("heightReference", "takeoff").put("returnHomeHeightMeters", returnHomeHeightMeters)
        .put("groundResolutionCmPerPixel", groundResolutionCmPerPixel)
        .put("photoTag", photoTag).put("reviewRequired", true)

    /** M4TD Pilot spells its visible-camera storage enum "visable". Preserve saved geometry. */
    fun correctingCameraSelection(archive: ByteArray): ByteArray {
        if (lens != "visable") return archive
        var changed = false
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { target ->
            java.util.zip.ZipInputStream(archive.inputStream()).use { source ->
                var expanded = 0
                var count = 0
                while (true) {
                    val entry = source.nextEntry ?: break
                    require(++count <= 1000) { "Too many saved route entries." }
                    val content = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = source.read(buffer); if (n < 0) break
                        expanded += n
                        require(expanded <= 32 * 1024 * 1024) { "Saved route is too large." }
                        content.write(buffer, 0, n)
                    }
                    val bytes = content.toByteArray()
                    var data = bytes
                    if (entry.name in listOf("wpmz/template.kml", "wpmz/waylines.wpml")) {
                        val xml = bytes.toString(Charsets.UTF_8)
                        var corrected = xml
                        for (field in listOf("imageFormat", "payloadLensIndex")) {
                            corrected = corrected.replace("<wpml:$field>wide</wpml:$field>", "<wpml:$field>visable</wpml:$field>")
                        }
                        changed = changed || corrected != xml
                        data = corrected.toByteArray(Charsets.UTF_8)
                    }
                    target.putNextEntry(java.util.zip.ZipEntry(entry.name)); target.write(data); target.closeEntry()
                }
            }
        }
        return if (changed) output.toByteArray() else archive
    }
    fun kmz(): ByteArray {
        MappingCaptureProfile.requireExportQualified(mission)
        require(photoEstimate<=10000){"Estimated $photoEstimate originals exceed the 10,000-original intake capacity."}
        return ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            listOf("wpmz/template.kml" to xml(true), "wpmz/waylines.wpml" to xml(false)).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
    }

    private fun xml(template: Boolean): String = buildString {
        fun value(name: String, content: Any) { append("<wpml:$name>${escape(content.toString())}</wpml:$name>") }
        fun action(index: Int, function: String, parameters: () -> Unit) {
            append("<wpml:action>"); value("actionId", index); value("actionActuatorFunc", function)
            append("<wpml:actionActuatorFuncParam>"); parameters(); append("</wpml:actionActuatorFuncParam></wpml:action>")
        }
        fun photo() { value("payloadPositionIndex", 0); value("fileSuffix", photoTag); value("payloadLensIndex", lens); value("useGlobalPayloadLensIndex", 0) }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\"><Document>")
        value("author", "RID2Caltopo"); value("createTime", System.currentTimeMillis()); value("updateTime", System.currentTimeMillis())
        append("<wpml:missionConfig>")
        value("flyToWaylineMode", "safely"); value("finishAction", "goHome"); value("exitOnRCLost", "executeLostAction"); value("executeRCLostAction", "goBack")
        value("takeOffSecurityHeight", min(20.0, height)); value("globalTransitionalSpeed", number(transitSpeed)); value("globalRTHHeight", number(returnHomeHeightMeters))
        append("<wpml:droneInfo>"); value("droneEnumValue", 100); value("droneSubEnumValue", 1); append("</wpml:droneInfo>")
        append("<wpml:payloadInfo>"); value("payloadEnumValue", 99); value("payloadSubEnumValue", 0); value("payloadPositionIndex", 0); append("</wpml:payloadInfo></wpml:missionConfig><Folder>")
        value("templateId", 0); value("autoFlightSpeed", number(speed))
        if (template) {
            value("templateType", "waypoint")
            append("<wpml:waylineCoordinateSysParam>"); value("coordinateMode", "WGS84"); value("heightMode", "relativeToStartPoint"); value("positioningType", "GPS"); append("</wpml:waylineCoordinateSysParam>")
            value("globalHeight", number(height)); value("gimbalPitchMode", "usePointSetting"); value("globalWaypointTurnMode", "toPointAndStopWithDiscontinuityCurvature")
            append("<wpml:globalWaypointHeadingParam>"); value("waypointHeadingMode", "followWayline"); append("</wpml:globalWaypointHeadingParam>")
        } else { value("waylineId", 0); value("executeHeightMode", "relativeToStartPoint") }
        legs.flatMap { listOf(it.start, it.end) }.forEachIndexed { index, point ->
            val leg=legs[index/2];val oblique=passes[leg.passIndex].pitchDegrees> -87
            val fixed=aircraftFirstHeading
            val yaw=Math.toDegrees(atan2(leg.end.x-leg.start.x,leg.end.y-leg.start.y))
            val (lon, lat) = coordinate(point)
            append("<Placemark><Point><coordinates>${number(lon)},${number(lat)}</coordinates></Point>")
            value("index", index)
            if (template) { value("useGlobalHeight", 1); value("useGlobalSpeed", 1); value("useGlobalHeadingParam", if(fixed)0 else 1); value("useGlobalTurnParam", 1); value("gimbalPitchAngle", number(passes[leg.passIndex].pitchDegrees))
                if(fixed){append("<wpml:waypointHeadingParam>");value("waypointHeadingMode","smoothTransition");value("waypointHeadingAngle",number(yaw));value("waypointHeadingPathMode","followBadArc");append("</wpml:waypointHeadingParam>")}}
            else {
                value("executeHeight", number(height)); value("waypointSpeed", number(speed))
                append("<wpml:waypointHeadingParam>"); value("waypointHeadingMode", if(fixed)"smoothTransition" else "followWayline")
                if(fixed){value("waypointHeadingAngle",number(yaw));value("waypointHeadingPathMode","followBadArc")};append("</wpml:waypointHeadingParam>")
                append("<wpml:waypointTurnParam>"); value("waypointTurnMode", "toPointAndStopWithDiscontinuityCurvature"); value("waypointTurnDampingDist", 0); append("</wpml:waypointTurnParam>")
            }
            if (index % 2 == 0) {
                append("<wpml:actionGroup>"); value("actionGroupId", index); value("actionGroupStartIndex", index); value("actionGroupEndIndex", index); value("actionGroupMode", "sequence")
                append("<wpml:actionTrigger>"); value("actionTriggerType", "reachPoint"); append("</wpml:actionTrigger>")
                var actionId=0
                if(fixed)action(actionId++,"rotateYaw") {
                    val previous=if(index>0){val prior=legs[index/2-1]
                        Math.toDegrees(atan2(prior.end.x-prior.start.x,prior.end.y-prior.start.y))} else yaw
                    val turn=((yaw-previous+540)%360)-180
                    value("aircraftHeading",number(yaw));value("aircraftPathMode",if(turn<0)"counterClockwise" else "clockwise")
                }
                action(actionId++, "gimbalRotate") {
                    value("payloadPositionIndex", 0); value("gimbalHeadingYawBase", "north"); value("gimbalRotateMode", "absoluteAngle")
                    value("gimbalPitchRotateEnable", 1); value("gimbalPitchRotateAngle", number(passes[legs[index/2].passIndex].pitchDegrees)); value("gimbalRollRotateEnable", 0); value("gimbalRollRotateAngle", 0)
                    value("gimbalYawRotateEnable", if(aircraftFirstHeading||oblique)1 else 0); value("gimbalYawRotateAngle", if(aircraftFirstHeading||oblique)number(yaw) else "0"); value("gimbalRotateTimeEnable", 0); value("gimbalRotateTime", 0)
                }
                (focalLength ?: if(mission.getString("cameraProfileId")=="m4td.visible.wide")24.0 else null)?.let { focal -> action(actionId++, "zoom") { value("payloadPositionIndex", 0); value("focalLength", number(focal)) } }
                if (index == 0) action(actionId++, "customDirName") { value("payloadPositionIndex", 0); value("directoryName", photoTag) }
                if(!distanceCapture)action(actionId, "takePhoto") { photo() }
                append("</wpml:actionGroup>")
                append("<wpml:actionGroup>"); value("actionGroupId", index + 1); value("actionGroupStartIndex", index); value("actionGroupEndIndex", index + 1); value("actionGroupMode", "sequence")
                append("<wpml:actionTrigger>"); value("actionTriggerType", if(distanceCapture)"multipleDistance" else "multipleTiming"); value("actionTriggerParam", number(passSpacing(passes[legs[index/2].passIndex])/(if(distanceCapture)1.0 else speed))); append("</wpml:actionTrigger>")
                action(0, "takePhoto") { photo() }; append("</wpml:actionGroup>")
            }
            append("</Placemark>")
        }
        append("<wpml:payloadParam>"); value("payloadPositionIndex", 0); value("imageFormat", lens); append("</wpml:payloadParam></Folder></Document></kml>")
    }
    companion object {
        private fun inside(p:Point,ring:List<Point>):Boolean {var result=false
            ring.indices.forEach{i->val a=ring[i];val b=ring[(i+1)%ring.size]
                if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)result=!result}
            return result}
        private fun number(value: Double) = String.format(Locale.US, "%.9f", value)
        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        private fun intersects(a: Point, b: Point, c: Point, d: Point): Boolean {
            fun cross(p: Point, q: Point, r: Point) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
            return max(min(a.x,b.x), min(c.x,d.x)) <= min(max(a.x,b.x), max(c.x,d.x)) &&
                max(min(a.y,b.y), min(c.y,d.y)) <= min(max(a.y,b.y), max(c.y,d.y)) && cross(a,b,c) * cross(a,b,d) <= 0 && cross(c,d,a) * cross(c,d,b) <= 0
        }
    }
}
