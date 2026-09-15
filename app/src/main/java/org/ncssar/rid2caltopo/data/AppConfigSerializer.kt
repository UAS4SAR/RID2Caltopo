package org.ncssar.rid2caltopo.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object AppConfigSerializer : Serializer<AppConfig> {
    override val defaultValue: AppConfig = AppConfig.newBuilder()
        .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
        .setMinDistanceFeet(CaltopoClient.MIN_DISTANCE_IN_FEET)
        .setNewTrackDelaySeconds(30)
        .setMaxFlatlineToneDurationSeconds(CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS)
        .setBridgeCheckDistanceFeet(CaltopoClient.DEFAULT_BRIDGE_CHECK_DISTANCE_FEET)
        .setAlarmVolumePercent(CaltopoClient.DEFAULT_ALARM_VOLUME_PERCENT)
        .setAlarmVolumeConfigured(false)
        .setMaxIdleTimeMinutes(CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES)
        .setDebugLevel(-1)
        .setCoordinateDisplayFormat("decimal")
        .setCaptureVideoStreams(true)
        .setUsePeers(true)
        .setCaltopoTrackFolder("Drone Tracks")
        .setCaltopoDomainAndPort("caltopo.com")
        .setIncident("Training")
        .setOpPeriod("1")
        .setNotam(
            AppConfig.NotamConfig.newBuilder()
                .setEnabled(false)
                .setRadiusNm(2)
                .setAutoRefresh(true)
                .setRefreshIntervalSeconds(90)
                .setWarnInsideOneNm(true)
                .build()
        )
        .build()

    override suspend fun readFrom(input: InputStream): AppConfig {
        try {
            return AppConfig.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Unable to read app config proto.", e)
        }
    }

    override suspend fun writeTo(t: AppConfig, output: OutputStream) {
        t.writeTo(output)
    }
}
