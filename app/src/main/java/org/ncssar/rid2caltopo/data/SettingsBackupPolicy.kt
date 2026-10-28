package org.ncssar.rid2caltopo.data

/** An allowlist shared by Drive and OS backup/restore. Never copy unknown fields. */
object SettingsBackupPolicy {
    @JvmStatic fun settingsOnly(source: AppConfig): AppConfig = copySettings(AppConfig.newBuilder(), source).build()

    /** Restore preferences without importing or erasing this installation's access. */
    @JvmStatic fun restore(current: AppConfig, backup: AppConfig): AppConfig =
        copySettings(current.toBuilder(), settingsOnly(backup)).build()

    private fun copySettings(target: AppConfig.Builder, source: AppConfig): AppConfig.Builder = target
        .setSchemaVersion(AppConfigStore.SCHEMA_VERSION).setLegacyImportComplete(true)
        .setMinDistanceFeet(source.minDistanceFeet).setNewTrackDelaySeconds(source.newTrackDelaySeconds)
        .setMaxIdleTimeMinutes(source.maxIdleTimeMinutes).setMaxFlatlineToneDurationSeconds(source.maxFlatlineToneDurationSeconds)
        .setDebugLevel(source.debugLevel).setCoordinateDisplayFormat(source.coordinateDisplayFormat)
        .setCaptureVideoStreams(source.captureVideoStreams)
        .setProximityAlertSpacingFeet(source.proximityAlertSpacingFeet).setProximityAlertSpacingConfigured(source.proximityAlertSpacingConfigured)
        .setBridgeCheckDistanceFeet(source.bridgeCheckDistanceFeet)
        .setAlarmVolumePercent(source.alarmVolumePercent).setAlarmVolumeConfigured(source.alarmVolumeConfigured)
        .setNotam(AppConfig.NotamConfig.newBuilder().setEnabled(source.notam.enabled).setRadiusNm(source.notam.radiusNm)
            .setAutoRefresh(source.notam.autoRefresh).setRefreshIntervalSeconds(source.notam.refreshIntervalSeconds)
            .setWarnInsideOneNm(source.notam.warnInsideOneNm))
        .setLandRestrictions(AppConfig.LandRestrictionConfig.newBuilder().setEnabled(source.landRestrictions.enabled)
            .setShowOnMap(source.landRestrictions.showOnMap).setAutoRefresh(source.landRestrictions.autoRefresh)
            .setRadiusNm(source.landRestrictions.radiusNm).setLastUpdatedEpochMs(source.landRestrictions.lastUpdatedEpochMs))
        .clearRidMappings().addAllRidMappings(source.ridMappingsList.map { mapping ->
            AppConfig.RidMapping.newBuilder().setRemoteId(mapping.remoteId).setMappedId(mapping.mappedId)
                .setOrg(mapping.org).setModel(mapping.model).setOwner(mapping.owner)
                .setOwnerName(mapping.ownerName).setOwnerCallsign(mapping.ownerCallsign)
                .setReadinessJson(runCatching {
                    if (mapping.readinessJson.isBlank()) "" else AircraftReadiness.fromJSON(org.json.JSONObject(mapping.readinessJson)).toJSON().toString()
                }.getOrDefault("")).build()
        })
}
