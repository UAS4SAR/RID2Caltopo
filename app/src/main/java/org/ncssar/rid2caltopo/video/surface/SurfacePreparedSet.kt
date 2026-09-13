package org.ncssar.rid2caltopo.video.surface

import org.json.JSONObject

/** Validates the complete core grid; an index alone is never proof of reusable coverage. */
internal object SurfacePreparedSet {
    fun validate(index: JSONObject, read: (String) -> ByteArray) {
        val w=index.getInt("width");val h=index.getInt("height")
        require(w in 1..4000 && h in 1..4000)
        val expected=mutableSetOf<String>()
        for(y in 0 until h step 1000) for(x in 0 until w step 1000) expected+="tile-$y-$x.aol"
        val entries=index.getJSONArray("entries")
        require(entries.length()==expected.size)
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i);val name=entry.getString("file")
            require(expected.remove(name)) { "Unexpected or duplicate AOL tile" }
            val m=SurfacePackage.decode(read(name)).metadata
            val parts=name.removeSuffix(".aol").split('-');val y=parts[1].toInt();val x=parts[2].toInt()
            require(m.getDouble("originLatitude")==index.getDouble("originLatitude") && m.getDouble("originLongitude")==index.getDouble("originLongitude"))
            require(m.getString("referenceGroup")==index.getString("referenceGroup") && m.getDouble("spacing")==1.0)
            require(m.getDouble("coreWest")== -w/2.0+x && m.getDouble("coreSouth")== -h/2.0+y)
            require(m.getInt("coreWidth")==minOf(1000,w-x) && m.getInt("coreHeight")==minOf(1000,h-y))
            entry.put("metadata",m)
        }
    }
    fun contains(width: Int,height: Int,west: Double,south: Double,east: Double,north: Double): Boolean =
        west-62 >= -width/2.0-0.001 && east+62 <= width/2.0+0.001 && south-62 >= -height/2.0-0.001 && north+62 <= height/2.0+0.001
    fun fresh(prepared: Long, now: Long, maxAge: Long): Boolean = prepared>0 && prepared<=now && now-prepared<maxAge
}
