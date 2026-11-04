package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Keeps CalTopo clue Marker and MapMediaObject Point geometries aligned.
 * Local clue lat/lng is authoritative; media bytes are never re-uploaded here.
 */
object CluePhotoGeometrySync {
    /** Repair MapMediaObject when it is farther than this from the Marker. */
    const val DRIFT_REPAIR_METERS = 1.0

    @JvmStatic
    @JvmOverloads
    fun needsRepair(
        markerLat: Double,
        markerLng: Double,
        linkLat: Double,
        linkLng: Double,
        thresholdMeters: Double = DRIFT_REPAIR_METERS,
    ): Boolean {
        if (!markerLat.isFinite() || !markerLng.isFinite() || !linkLat.isFinite() || !linkLng.isFinite()) {
            return false
        }
        if (abs(markerLat - linkLat) <= 1e-7 && abs(markerLng - linkLng) <= 1e-7) return false
        val relative = RidGeometry.relativePosition(markerLat, markerLng, linkLat, linkLng) ?: return false
        return relative.distanceMeters > thresholdMeters
    }

    /**
     * Reads Point coordinates from a CalTopo Feature (or Feature nested under "feature").
     * Returns lat/lng; null when geometry is absent or not a 2D Point.
     */
    @JvmStatic
    fun pointLatLng(featureOrResult: JSONObject?): DoubleArray? {
        if (featureOrResult == null) return null
        val feature = when {
            featureOrResult.has("geometry") -> featureOrResult
            featureOrResult.optJSONObject("feature")?.has("geometry") == true ->
                featureOrResult.getJSONObject("feature")
            else -> featureOrResult
        }
        val geometry = feature.optJSONObject("geometry") ?: return null
        if (!geometry.optString("type").equals("Point", ignoreCase = true) &&
            geometry.has("type")
        ) {
            return null
        }
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        val lng = coordinates.optDouble(0, Double.NaN)
        val lat = coordinates.optDouble(1, Double.NaN)
        if (!lat.isFinite() || !lng.isFinite()) return null
        return doubleArrayOf(lat, lng)
    }

    @JvmStatic
    fun pointGeometry(lat: Double, lng: Double): JSONObject {
        return JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(lng).put(lat))
    }

    /** Copy properties for a geometry-only MapMediaObject edit (never rewrite media bytes). */
    @JvmStatic
    fun copyProperties(source: JSONObject?): JSONObject {
        if (source == null) return JSONObject()
        val props = source.optJSONObject("properties") ?: return JSONObject()
        return JSONObject(props.toString())
    }
}
