package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject

internal object DjiCloudTestFixture {
    fun mission(thermal:Boolean=false):JSONObject {
        val feature=JSONObject("""{"type":"Feature","id":"assignment-1","properties":{"class":"Assignment","title":"Mapping test"},"geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.998,39],[-120.998,39.002],[-121,39.002],[-121,39]]]}}""")
        return MappingMission(feature,"incident-1",mapName="Test incident").json(thermal,if(thermal)12.0 else 2.0)
    }
    fun credentials()=DjiCloudCredentials("123456","synthetic-test-app-key-0000000000","synthetic-test-license-not-an-actual-DJI-credential")
}
