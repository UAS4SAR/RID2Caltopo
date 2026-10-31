package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaltopoPositionReportQueryTest {
    @Test
    fun positionReportsUseGet() {
        assertEquals(CaltopoSession.CtsMethod_t.GET, CaltopoSession.positionReportMethod())
    }

    @Test
    fun signedPersonalPositionReportDoesNotConsultTheCookieProbe() {
        val op = CaltopoOp(null)
        op.goNaked = true
        op.positionReport = true
        op.personalAuthorization = CaltopoPersonalSession.Authorization(
            CaltopoPersonalAccountConnection.SIGNED_ACCOUNT_GRANT,
            "4J0LF02",
        )
        assertFalse(CaltopoSession.personalCookieRequired(op))
    }

    @Test
    fun cookieSessionPositionReportStillUsesThePersonalCookie() {
        val op = CaltopoOp(null)
        op.goNaked = true
        op.positionReport = true
        op.personalAuthorization = CaltopoPersonalSession.Authorization(
            "11111111-1111-1111-1111-111111111111",
            "4J0LF02",
        )
        assertTrue(CaltopoSession.personalCookieRequired(op))
    }

    @Test
    fun positionReportsUseConfiguredConnectKeyWithLegacyFallback() {
        assertEquals(
            "/api/v1/position/report/NCSSAR-UAS",
            CaltopoSession.positionReportPath(" NCSSAR-UAS "),
        )
        assertEquals(
            "/api/v1/position/report/DRONE",
            CaltopoSession.positionReportPath(""),
        )
        assertEquals(
            "/api/v1/position/report/TEAM%2FDRONE",
            CaltopoSession.positionReportPath("TEAM/DRONE"),
        )
        assertEquals(
            "FLEET:NCSSAR-UAS-RID01",
            CaltopoSession.liveTrackDeviceId("RID01", " NCSSAR-UAS "),
        )
        assertEquals(
            "FLEET:DRONE-RID01",
            CaltopoSession.liveTrackDeviceId("RID01", ""),
        )
    }

    @Test
    fun positionQueryContainsColonQualifiedAircraftAndCameraParameters() {
        val parameters = CaltopoSession.buildPositionQueryParameters(
            1600.8,
            CtDroneSpec.PositionTelemetry(140.0, 19.4384449, 92.0),
            CaltopoCameraMetadata(
                "https://r2c-tracker.com/t/Bz2DZg",
                "https://r2c-tracker.com/ncssar/api/v1/video/thumbnail/session-1",
                111.46,
                -37.0,
                37.703125,
                21.207031,
            ),
        )

        assertEquals(5252L, parameters.getLong("aircraft:altitude"))
        assertEquals(140.0, parameters.getDouble("aircraft:altitude_rate"), 0.0)
        assertEquals(19.4384449, parameters.getDouble("aircraft:gs"), 0.0)
        assertEquals(92.0, parameters.getDouble("aircraft:track"), 0.0)
        assertEquals(
            "https://r2c-tracker.com/t/Bz2DZg",
            parameters.getString("camera:external_url"),
        )
        assertTrue(parameters.has("camera:thumbnail_url"))
        assertEquals(111.46, parameters.getDouble("camera:azimuth"), 0.0)
        assertEquals(-37.0, parameters.getDouble("camera:tilt"), 0.0)
        assertEquals(37.703125, parameters.getDouble("camera:fov_width"), 0.0)
        assertEquals(21.207031, parameters.getDouble("camera:fov_height"), 0.0)
        assertFalse(parameters.has("aircraft"))
        assertFalse(parameters.has("camera"))
    }

    @Test
    fun positionQueryOmitsEmptyAndNonFiniteValues() {
        val parameters = CaltopoSession.buildPositionQueryParameters(
            -1000.0,
            CtDroneSpec.PositionTelemetry(Double.NaN, null, Double.POSITIVE_INFINITY),
            CaltopoCameraMetadata("", null),
        )

        assertFalse(parameters.has("aircraft:altitude"))
        assertFalse(parameters.has("aircraft:altitude_rate"))
        assertFalse(parameters.has("aircraft:gs"))
        assertFalse(parameters.has("aircraft:track"))
        assertFalse(parameters.has("camera:external_url"))
        assertFalse(parameters.has("camera:thumbnail_url"))
    }

    @Test
    fun debugSummaryShowsWireFieldsWithoutTrackerPaths() {
        val parameters = CaltopoSession.buildPositionQueryParameters(
            1600.8,
            CtDroneSpec.PositionTelemetry(140.0, 19.4384449, 92.0),
            CaltopoCameraMetadata(
                "https://r2c-tracker.com/t/private-tablet-code",
                "https://r2c-tracker.com/org/api/v1/video/thumbnail/private-session",
                111.46,
                -37.0,
                37.703125,
                21.207031,
            ),
        )

        val summary = CaltopoSession.positionReportDiagnosticSummary(parameters)

        assertTrue(summary.contains("aircraft:altitude=5252"))
        assertTrue(summary.contains("camera:external_url=present(scheme=https,host=r2c-tracker.com)"))
        assertTrue(summary.contains(
            "camera:thumbnail_url=present(scheme=https,host=r2c-tracker.com,cacheBust=missing)",
        ))
        assertFalse(summary.contains("private-tablet-code"))
        assertFalse(summary.contains("private-session"))
    }
}
