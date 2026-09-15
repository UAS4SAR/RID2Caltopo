package org.ncssar.rid2caltopo.video.surface

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.*

/** Immutable, separately typed surface and ground; safe to query off the UI thread. */
class SurfacePackage private constructor(val metadata: JSONObject, private val surface: FloatArray, private val ground: FloatArray) {
    val id = metadata.getString("id") + "@" + metadata.getString("version")
    val width = metadata.getInt("width")
    val height = metadata.getInt("height")
    val spacing = metadata.getDouble("spacing")
    private val west = metadata.getDouble("west")
    private val south = metadata.getDouble("south")
    private val lat = metadata.getDouble("originLatitude")
    private val lon = metadata.getDouble("originLongitude")
    private val scale = cos(Math.toRadians(lat))
    fun xy(latitude: Double, longitude: Double) = Pair(Math.toRadians(longitude-lon)*R*scale, Math.toRadians(latitude-lat)*R)
    fun coordinate(x: Double, y: Double) = Pair(lat + Math.toDegrees(y/R), lon + Math.toDegrees(x/(R*scale)))
    fun groundAt(latitude: Double, longitude: Double): Double? {
        val (x,y) = xy(latitude,longitude)
        val c = floor((x-west)/spacing).toInt(); val r = floor((y-south)/spacing).toInt()
        return if (c in 0 until width && r in 0 until height) ground[r*width+c].toDouble().takeIf { it.isFinite() } else null
    }
    fun surfaceAt(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite() || abs(latitude)>90 || abs(longitude)>180) return null
        val (x,y) = xy(latitude,longitude)
        val c = floor((x-west)/spacing).toInt(); val r = floor((y-south)/spacing).toInt()
        return if (c in 0 until width && r in 0 until height) surface[r*width+c].toDouble().takeIf { it.isFinite() } else null
    }
    data class Peak(val elevation: Double, val latitude: Double, val longitude: Double, val distance: Double, val ground: Double?)
    data class Analysis(val peak: Peak?, val complete: Boolean, val checked: Int, val missing: Int)
    fun disk(latitude: Double, longitude: Double, radius: Double = RADIUS): Analysis {
        require(latitude.isFinite() && longitude.isFinite() && abs(latitude)<=90 && abs(longitude)<=180 && radius.isFinite() && radius > 0 && radius <= 5000)
        val (x,y) = xy(latitude,longitude)
        return region(x-radius,y-radius,x+radius,y+radius,x,y) { a,b ->
            val dx = max(0.0, abs(a-x)-spacing/2); val dy = max(0.0, abs(b-y)-spacing/2)
            dx*dx+dy*dy <= radius*radius
        }
    }
    /** Assignment polygon or route corridor, using the same cell-footprint uncertainty as disk queries. */
    fun briefing(points: List<Pair<Double,Double>>, corridor: Double, polygon: Boolean, coreOnly: Boolean = false): Analysis {
        require(points.size >= (if (polygon) 3 else 2) && corridor.isFinite() && corridor >= 0 && corridor <= 5000)
        require(points.all { abs(it.first)<=90 && abs(it.second)<=180 })
        val p = points.map { xy(it.first,it.second) }; val pad = corridor + spacing*sqrt(2.0)/2
        val x0=max(p.minOf{it.first}-pad,if(coreOnly) metadata.getDouble("coreWest") else Double.NEGATIVE_INFINITY)
        val y0=max(p.minOf{it.second}-pad,if(coreOnly) metadata.getDouble("coreSouth") else Double.NEGATIVE_INFINITY)
        val x1=min(p.maxOf{it.first}+pad,if(coreOnly) metadata.getDouble("coreWest")+metadata.getInt("coreWidth")-0.001 else Double.POSITIVE_INFINITY)
        val y1=min(p.maxOf{it.second}+pad,if(coreOnly) metadata.getDouble("coreSouth")+metadata.getInt("coreHeight")-0.001 else Double.POSITIVE_INFINITY)
        if(x1<x0 || y1<y0) return Analysis(null,true,0,0)
        return region(x0,y0,x1,y1,p[0].first,p[0].second) { x,y ->
            var inside = false
            if (polygon) for (i in p.indices) {
                val a=p[i]; val b=p[(i+1)%p.size]
                if ((a.second>y)!=(b.second>y) && x < (b.first-a.first)*(y-a.second)/(b.second-a.second)+a.first) inside=!inside
            }
            inside || (0 until if(polygon) p.size else p.size-1).any { i ->
                val a=p[i]; val b=p[(i+1)%p.size]; val dx=b.first-a.first; val dy=b.second-a.second
                val t=if(dx*dx+dy*dy==0.0) 0.0 else (((x-a.first)*dx+(y-a.second)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
                hypot(x-a.first-t*dx,y-a.second-t*dy)<=pad
            }
        }
    }
    private fun region(x0:Double,y0:Double,x1:Double,y1:Double,cx:Double,cy:Double, include:(Double,Double)->Boolean): Analysis {
        val c0=floor((x0-west)/spacing).toInt(); val c1=floor((x1-west)/spacing).toInt()
        val r0=floor((y0-south)/spacing).toInt(); val r1=floor((y1-south)/spacing).toInt()
        require((c1.toLong()-c0+1)*(r1.toLong()-r0+1) <= 4_000_000) { "Analysis extent too large; split preparation" }
        var peak:Peak?=null; var checked=0; var missing=0
        for(r in r0..r1) for(c in c0..c1) {
            val x=west+(c+0.5)*spacing; val y=south+(r+0.5)*spacing
            if(!include(x,y)) continue
            checked++
            if(c !in 0 until width || r !in 0 until height || !surface[r*width+c].isFinite()) { missing++; continue }
            val z=surface[r*width+c].toDouble()
            if(peak==null || z>peak.elevation) {
                val ll=coordinate(x,y)
                peak=Peak(z,ll.first,ll.second,hypot(x-cx,y-cy),ground[r*width+c].toDouble().takeIf{it.isFinite()})
            }
        }
        return Analysis(peak,checked>0 && missing==0,checked,missing)
    }
    fun wireNotes():String {
        val records=metadata.optJSONArray("wireObservations") ?: return "No wire inventory supplied; coverage is not established."
        return (0 until records.length()).joinToString("\n") { i ->
            val w=records.getJSONObject(i)
            "Wire crossing — height unknown at ${w.getDouble("latitude")}, ${w.getDouble("longitude")}; ${w.getString("source")}; observed ${w.getString("date")}; geometry ${w.getString("confidence")}. No span inferred."
        }.ifEmpty { "No wire inventory supplied; coverage is not established." }
    }
    companion object {
        const val RADIUS=60.96
        const val R=6371008.8
        const val MAX_BYTES=40*1024*1024
        fun readBounded(input:java.io.InputStream,limit:Int):ByteArray {
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(out.size()<limit) {
                val count=input.read(buffer,0,minOf(buffer.size,limit-out.size()))
                if(count<0) break
                out.write(buffer,0,count)
            }
            return out.toByteArray()
        }
        fun decode(bytes: ByteArray): SurfacePackage {
            require(bytes.size<=MAX_BYTES)
            val entries=mutableMapOf<String,ByteArray>(); var total=0
            ZipInputStream(bytes.inputStream()).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(entry.name in setOf("manifest.json","surface.f32","ground.f32") && entry.name !in entries)
                    val data=readBounded(zip,MAX_BYTES-total+1); total+=data.size
                    require(total<=MAX_BYTES); entries[entry.name]=data
                }
            }
            require(entries.size==3)
            val m=JSONObject(String(entries.getValue("manifest.json"),Charsets.UTF_8))
            require(m.getInt("schema")==1 && m.getString("layer")=="top-surface" && m.getString("units")=="metres" && m.getString("horizontalCRS")=="R2C_LOCAL_EQUIRECTANGULAR_WGS84")
            listOf("id","version","processingVersion","sourceURL","surveyDate","verticalReference","quality").forEach { require(m.getString(it).isNotBlank()) }
            m.optJSONArray("wireObservations")?.let { wires ->
                require(wires.length()<=1000)
                for(i in 0 until wires.length()) {
                    val wire=wires.getJSONObject(i)
                    require(abs(wire.getDouble("latitude"))<=90 && abs(wire.getDouble("longitude"))<=180)
                    listOf("source","date","confidence").forEach { require(wire.getString(it).isNotBlank()) }
                    require(wire.isNull("heightMeters")) { "Initial wire observations must have unknown height; numeric wire clearance is unsupported" }
                }
            }
            require(m.getString("verticalReference") in setOf("NAVD88 / GEOID12B / metres", "NAVD88 / GEOID18 / metres", "NAVD88 / same-survey paired reference / metres")) { "Unsupported vertical reference" }
            if (m.getString("verticalReference").contains("same-survey")) {
                require(m.optString("referenceGroup").isNotBlank() && m.optString("sourceCRS").contains("5703")) { "Paired survey reference provenance required" }
            }
            val w=m.getInt("width"); val h=m.getInt("height"); val s=m.getDouble("spacing")
            require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=4_000_000 && s.isFinite() && s in 0.25..10.0 && w*s<=10000 && h*s<=10000)
            require(abs(m.getDouble("originLatitude"))<70 && abs(m.getDouble("originLongitude"))<=180)
            require(abs(m.getDouble("west"))<=10000 && abs(m.getDouble("south"))<=10000)
            fun raster(name:String,key:String):FloatArray {
                val b=entries.getValue(name); require(b.size==w*h*4)
                val digest=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
                require(digest==m.getString(key)) { "Surface checksum mismatch" }
                val buffer=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(w*h){buffer.float.also { require(!it.isInfinite()) }}
            }
            return SurfacePackage(m,raster("surface.f32","surfaceSHA256"),raster("ground.f32","groundSHA256"))
        }
    }
}

data class AolState(val feet:Double?=null, val reason:String="Surface package not prepared", val details:String=reason, val status:MeasurementStatus=if(feet==null) MeasurementStatus.Unknown else MeasurementStatus.Available) {
    val label:String get()=measurementLabel(feet,status)
    companion object {
        const val EXPLANATION="AOL · 200 ft radius: height above the highest mapped surface nearby. Negative is not a collision prediction. Positive does not exclude wires or unmapped obstacles. Wires may be absent; no wire clearance is inferred from AOL. Reference assumes a ground launch at the observed takeoff location; elevated launches are unsupported. Aircraft and survey uncertainty are not bounded by pixel size."
        fun calculate(p:SurfacePackage, latitude:Double, longitude:Double, takeoffLatitude:Double, takeoffLongitude:Double, height:Double?, compatibleGround:Double?=null, pointOnly:Boolean=false):AolState {
            if(height==null || !height.isFinite()) return AolState(reason="Takeoff-relative altitude unavailable")
            val ground=compatibleGround ?: p.groundAt(takeoffLatitude,takeoffLongitude) ?: return AolState(reason="Compatible takeoff ground unavailable")
            if (pointOnly) {
                val surface = p.surfaceAt(latitude, longitude) ?: return AolState(reason="Point surface unavailable")
                return AolState((ground+height-surface)/0.3048, "Available", "Point AOL: clearance above the mapped surface at the crosshair; no lateral radius.")
            }
            val a=p.disk(latitude,longitude)
            if(!a.complete) return AolState(reason="Incomplete surface coverage (${a.missing} cells)")
            val peak=a.peak ?: return AolState(reason="Surface coverage unavailable")
            val m=p.metadata
            return AolState((ground+height-peak.elevation)/0.3048,"Available", "$EXPLANATION\nMapped high point: ${peak.latitude}, ${peak.longitude}; ${"%.0f".format(peak.distance/0.3048)} ft away; surface ${"%.0f".format(peak.elevation/0.3048)} ft; ground ${peak.ground?.div(0.3048)} ft.\n${p.id}; survey ${m.getString("surveyDate")}; ${p.spacing} m cells; ${m.getString("verticalReference")}. Full disk covered. Survey age is independent of telemetry age.\n${m.getString("quality")}\n${p.wireNotes()}")
        }
    }
}
