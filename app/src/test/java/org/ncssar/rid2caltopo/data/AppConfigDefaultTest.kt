package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigDefaultTest {
    @Test
    fun `proximity defaults to 100 and imported settings have a 50 foot floor`() {
        assertEquals(100L, ClientClassState().proximityAlertSpacingFeet)
        assertEquals(100L, AppConfigStore.resolveProximitySpacingFeet(AppConfig.getDefaultInstance()))
        for (schema in listOf(3, 5, AppConfigStore.SCHEMA_VERSION)) {
            for (feet in listOf(0L, 40L, 50L, 75L, 99L, 100L, 250L)) {
                val config = AppConfig.newBuilder().setSchemaVersion(schema)
                    .setProximityAlertSpacingConfigured(true).setProximityAlertSpacingFeet(feet).build()
                assertEquals(maxOf(50L, feet), AppConfigStore.resolveProximitySpacingFeet(config))
            }
        }
    }


    @Test
    fun `new installs use a thirty-minute RID idle timeout`() {
        assertEquals(
            CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES,
            AppConfigSerializer.defaultValue.maxIdleTimeMinutes,
        )
    }

    @Test
    fun `legacy default timeout migrates to thirty minutes`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION - 1)
            .setMaxIdleTimeMinutes(120)
            .build()

        assertEquals(
            CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES,
            AppConfigStore.resolveMaximumIdleMinutes(config),
        )
    }

    @Test
    fun `explicit nonlegacy timeout remains intact`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION - 1)
            .setMaxIdleTimeMinutes(45)
            .build()

        assertEquals(45L, AppConfigStore.resolveMaximumIdleMinutes(config))
    }

    @Test
    fun `missing protected-land config uses safe one-mile defaults`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
            .build()

        val defaults = AppConfigStore.resolveLandRestrictionDefaults(config)

        assertTrue(defaults.enabled)
        assertTrue(defaults.showOnMap)
        assertTrue(defaults.autoRefresh)
        assertEquals(1, defaults.radiusStatuteMiles)
    }

    @Test
    fun `explicit protected-land choices remain intact`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
            .setLandRestrictions(
                AppConfig.LandRestrictionConfig.newBuilder()
                    .setEnabled(false)
                    .setShowOnMap(false)
                    .setAutoRefresh(false)
                    .setRadiusNm(7)
            )
            .build()

        val defaults = AppConfigStore.resolveLandRestrictionDefaults(config)

        assertFalse(defaults.enabled)
        assertFalse(defaults.showOnMap)
        assertFalse(defaults.autoRefresh)
        assertEquals(7, defaults.radiusStatuteMiles)
    }

    @Test
    fun `client state starts with one-mile safety defaults`() {
        val state = ClientClassState()

        assertTrue(state.landRestrictionsEnabled)
        assertTrue(state.landRestrictionsShowOnMap)
        assertEquals(1, state.landRestrictionsRadiusNm)
        assertEquals(1, state.notamRadiusNm)
    }
}
