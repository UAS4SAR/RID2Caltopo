package org.ncssar.rid2caltopo.notam

import org.junit.Assert.*
import org.junit.Test

class NotamMapFocusTest {
    private fun notice(geometries: List<NotamGeometry>) = NearbyNotam(
        id = "notice", title = "Service notice", summary = "", distanceNm = 0.0,
        intersectsPilotBubble = true, geometries = geometries
    )

    @Test fun fitsEntireAreaEvenWhenPilotIsInside() {
        val west = NotamLatLng(35.0, -131.0)
        val east = NotamLatLng(35.0, -107.0)
        val north = NotamLatLng(45.0, -119.0)
        val south = NotamLatLng(25.0, -119.0)
        val area = notice(listOf(NotamGeometry.Collection(listOf(
            NotamGeometry.Polygon(listOf(listOf(west, north, east, south, west))),
            NotamGeometry.Point(NotamLatLng(Double.NaN, -119.0))
        ))))
        assertEquals(listOf(west, north, east, south), area.mapCoordinates())
    }

    @Test fun missingGeometryDoesNotInventPilotLocation() {
        assertTrue(notice(emptyList()).mapCoordinates().isEmpty())
        assertTrue(notice(listOf(NotamGeometry.Point(NotamLatLng(91.0, 0.0)))).mapCoordinates().isEmpty())
    }

    @Test fun repeatedSelectionsGetDistinctRequests() {
        val points = listOf(NotamLatLng(35.0, -119.0))
        assertNotEquals(NotamMapFocusRequest(coordinates = points).id, NotamMapFocusRequest(coordinates = points).id)
    }

    @Test fun serviceAreaRadiusIsNotMisrepresentedAsPolygonOrRestriction() {
        val humanized = NotamHumanizer.humanize(
            reference = "KZOA 6/5757/2026",
            notamText = "AIRSPACE ADS-B SER MAY NOT BE AVBL WI AN AREA DEFINED AS 590NM RADIUS OF 352024N1190648W",
            rawText = "", effectiveText = "Effective 2026-10-01T09:00:00Z to 2026-10-01T11:00:00Z",
            proximityText = "HERE", intersectsPilotBubble = true, horizontalIntersectsPilotBubble = true,
            verticallyIntersectsPilotBand = true, classification = "FDC", scheduleText = ""
        )
        assertTrue(humanized.summary.contains("590 NM radius"))
        assertTrue(humanized.summary.contains("ADS-B services may be unavailable"))
        assertFalse(humanized.details.contains("Polygon"))
        assertFalse(humanized.details.contains("restriction"))
    }
}
