package org.ncssar.rid2caltopo.mapping
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class MappingParityFixtureTest {
    @Test fun standardCanopyRouteMatchesSharedContract() {
        val directory=File("../tools/mapping/fixtures")
        val mission=JSONObject(File(directory,"standard-canopy-mission.json").readText())
        val route=DjiMappingRoute(mission)
        val baseline=File(directory,"standard-canopy-route.json")
        val expected=JSONObject(baseline.readText());assertEquals(expected.getInt("photos"),route.photoEstimate);assertEquals(expected.getInt("seconds"),route.durationEstimateSeconds);assertEquals(expected.getDouble("speed"),route.speed,1e-9)
        val legs=expected.getJSONArray("legs");assertEquals(legs.length(),route.legs.size)
        route.legs.forEachIndexed{i,leg->val e=legs.getJSONObject(i);assertEquals(e.getInt("pass"),leg.passIndex)
            listOf("start" to route.coordinate(leg.start),"end" to route.coordinate(leg.end)).forEach{(key,p)->assertEquals(e.getJSONArray(key).getDouble(0),p.first,1e-9);assertEquals(e.getJSONArray(key).getDouble(1),p.second,1e-9)}}
    }
}
