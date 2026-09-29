package org.ncssar.rid2caltopo.airspace

import org.json.JSONObject

object FaaUasFacilityMapParser {
    fun parse(body: String): List<FaaUasFacilityMapRecord> {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let {
            val message = it.optString("message", "FAA Facility Map returned an error.")
            throw AirspaceServiceFailure(message, it.optInt("code") == 429 || message.contains("too many requests", ignoreCase = true))
        }
        val features = root.optJSONArray("features") ?: return emptyList()
        return buildList {
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val attributes = feature.optJSONObject("attributes") ?: continue
                add(attributes.toFacilityMapRecord(feature.optJSONObject("geometry")))
            }
        }
    }

    private fun JSONObject.toFacilityMapRecord(geometry: JSONObject?): FaaUasFacilityMapRecord {
        return FaaUasFacilityMapRecord(
            objectId = optLong("OBJECTID", -1L),
            ceilingFeet = if (has("CEILING") && !isNull("CEILING")) optInt("CEILING") else null,
            unit = optString("UNIT"),
            primaryAirportFaaId = optString("APT1_FAAID"),
            primaryAirportIcao = optString("APT1_ICAO"),
            primaryAirportName = optString("APT1_NAME"),
            laancAvailable = optInt("APT1_LAANC", 0) == 1,
            airspaceClasses = (1..5).mapNotNull { index ->
                optString("AIRSPACE_$index").trim().ifBlank { null }
            },
            rings = geometry.parseRings()
        )
    }

    private fun JSONObject?.parseRings(): List<List<AirspaceCoordinate>> {
        val source = this?.optJSONArray("rings") ?: return emptyList()
        return buildList {
            for (ringIndex in 0 until source.length()) {
                val ring = source.optJSONArray(ringIndex) ?: continue
                val coordinates = buildList {
                    for (coordinateIndex in 0 until ring.length()) {
                        val pair = ring.optJSONArray(coordinateIndex) ?: continue
                        if (pair.length() < 2) continue
                        val longitude = pair.optDouble(0, Double.NaN)
                        val latitude = pair.optDouble(1, Double.NaN)
                        if (latitude.isFinite() && longitude.isFinite() &&
                            latitude in -90.0..90.0 && longitude in -180.0..180.0
                        ) {
                            add(AirspaceCoordinate(latitude, longitude))
                        }
                    }
                }
                if (coordinates.size >= 3) add(coordinates)
            }
        }
    }
}
