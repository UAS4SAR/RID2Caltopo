package org.ncssar.rid2caltopo.data

import android.app.backup.BackupManager
import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.ArrayList
import java.util.Hashtable
import java.util.Locale
import java.util.concurrent.Executors

private val Context.appConfigDataStore: DataStore<AppConfig> by dataStore(
    fileName = "app_config.pb",
    serializer = AppConfigSerializer
)

object AppConfigStore {
    const val SCHEMA_VERSION = 19
    private const val LEGACY_MAX_IDLE_TIME_MINUTES = 120L

    internal fun resolveMaximumIdleMinutes(config: AppConfig): Long = when {
        config.schemaVersion < SCHEMA_VERSION &&
            config.maxIdleTimeMinutes == LEGACY_MAX_IDLE_TIME_MINUTES ->
            CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES
        config.maxIdleTimeMinutes >= 0L -> config.maxIdleTimeMinutes
        else -> CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES
    }

    internal data class LandRestrictionDefaults(
        val enabled: Boolean,
        val showOnMap: Boolean,
        val autoRefresh: Boolean,
        val radiusStatuteMiles: Int
    )

    internal fun resolveLandRestrictionDefaults(config: AppConfig): LandRestrictionDefaults {
        val hasPersistedSelection = config.schemaVersion >= 14 && config.hasLandRestrictions()
        return LandRestrictionDefaults(
            enabled = if (hasPersistedSelection) config.landRestrictions.enabled else true,
            showOnMap = if (hasPersistedSelection) config.landRestrictions.showOnMap else true,
            autoRefresh = if (hasPersistedSelection) config.landRestrictions.autoRefresh else true,
            // radius_nm is the legacy wire/storage name. Android now treats the
            // operator-facing value as statute miles and converts only at query time.
            radiusStatuteMiles = config.landRestrictions.radiusNm.takeIf {
                hasPersistedSelection && it in 1..50
            } ?: 1
        )
    }
    private const val MAX_LOADED_CONFIG_FILES = 6
    private const val TAG = "AppConfigStore"
    private const val DEFAULT_HOME_PROFILE_ID = "home-default"

    private data class PendingConfigWrite(
        val context: Context,
        val config: AppConfig,
        val reason: String
    )

    private val cacheLock = Any()
    @Volatile
    private var cachedConfig: AppConfig? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var initializedDatastorePath: String? = null
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "r2c-app-config-store").apply { isDaemon = true }
    }
    private val backgroundWriter = CoalescingBackgroundWriter<PendingConfigWrite>(
        dispatch = { task -> persistenceExecutor.execute(task) },
        write = { pending -> writeConfigBlockingToDataStore(pending.context, pending.config) },
        onWriteComplete = { pending -> requestBackup(pending.context, pending.reason) },
        onWriteFailed = { pending, error ->
            CaltopoClient.CTWarn(
                TAG,
                "background config persist failed after ${pending.reason}: ${error.javaClass.simpleName}:${error.message}",
                error as? Exception ?: RuntimeException(error)
            )
        }
    )

    @JvmStatic
    @Synchronized
    fun initialize(context: Context) {
        val nextContext = context.applicationContext
        val datastorePath = "${nextContext.filesDir.parent}/files/datastore/app_config.pb"
        if (appContext === nextContext && initializedDatastorePath == datastorePath && cachedConfig != null) return
        appContext = nextContext
        initializedDatastorePath = datastorePath
        CaltopoClient.CTDebug(TAG, "initialize(): datastore path=$datastorePath")
        val config = readConfigBlockingFromDataStore(nextContext)
        synchronized(cacheLock) {
            cachedConfig = config
        }
    }

    @JvmStatic
    fun restoreClientState(context: Context): Any {
        initialize(context)
        val config = currentConfigSnapshot()
        CaltopoClient.CTDebug(
            TAG,
            "restoreClientState(): legacyImportComplete=${config.legacyImportComplete}, ridMappings=${config.ridMappingsCount}, loadedConfigFiles=${config.loadedConfigFilesCount}, archiveUriBlank=${config.archiveLocation.treeUri.isBlank()}"
        )
        return toClientState(config)
    }

    @JvmStatic
    fun exportConfigBytes(context: Context): ByteArray {
        initialize(context)
        return currentConfigSnapshot().toByteArray()
    }

    @JvmStatic
    fun importConfigBytes(context: Context, bytes: ByteArray): Boolean {
        if (!AircraftOrganizationAccess.canEdit()) return false
        initialize(context)
        return try {
            val imported = AppConfig.parseFrom(bytes)
            imported.ridMappingsList.forEach { mapping ->
                val readiness = AircraftReadiness.fromJSON(mapping.readinessJson.takeIf { it.isNotBlank() }?.let { org.json.JSONObject(it) })
                require(readiness.validationErrors().isEmpty()) { "Invalid aircraft readiness in backup." }
            }
            replaceCachedConfigAndEnqueueWrite(context, imported, "importConfigBytes")
            true
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "importConfigBytes(): unable to parse imported config.", e)
            false
        }
    }

    @JvmStatic
    fun hasMeaningfulConfig(context: Context): Boolean {
        initialize(context)
        return hasMeaningfulConfig(currentConfigSnapshot())
    }

    @JvmStatic
    fun persistState(context: Context, state: Any, archivePermissionMissing: Boolean) {
        initialize(context)
        val typedState = state as? ClientClassState ?: return
        updateCachedConfigAndEnqueueWrite(context, "persistState") { current ->
            mergeStateIntoConfig(current, typedState, archivePermissionMissing)
        }
    }

    @JvmStatic
    fun recordLoadedConfigFile(
        context: Context,
        type: String,
        editor: String,
        updated: String
    ): String {
        initialize(context)
        val nowMs = System.currentTimeMillis()
        val display = String.format(
            Locale.US,
            "type:%s, editor:%s, dated:%s loaded at %s",
            type,
            editor,
            updated,
            CaltopoClient.TimeDatestampString(nowMs)
        )
        val dedupeKey = String.format(Locale.US, "type:%s|editor:%s|dated:%s", type, editor, updated)
        val updatedConfig = updateCachedConfigAndEnqueueWrite(context, "recordLoadedConfigFile") { current ->
            current.toBuilder()
                .clearLoadedConfigFiles()
                .addAllLoadedConfigFiles(mergeLoadedConfigFiles(current.loadedConfigFilesList, dedupeKey, display, nowMs))
                .build()
        }
        return loadedConfigFilesDisplay(updatedConfig)
    }

    @JvmStatic
    fun getArchiveSelectionHint(context: Context): String {
        initialize(context)
        return currentConfigSnapshot().archiveLocation.selectionHintUri
    }

    @JvmStatic
    fun getArchiveRequiresRegrant(context: Context): Boolean {
        initialize(context)
        return currentConfigSnapshot().archiveLocation.requiresRegrant
    }

    @JvmStatic
    fun verifyArchiveAccess(context: Context, archivePath: String): Boolean {
        initialize(context)
        if (archivePath.isBlank()) return false
        return try {
            val archiveUri = Uri.parse(archivePath)
            hasPersistedReadPermission(context.contentResolver, archiveUri) &&
                DocumentFile.fromTreeUri(context, archiveUri)?.let { it.exists() && it.isDirectory && it.canRead() } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun currentConfigSnapshot(): AppConfig =
        cachedConfig ?: throw IllegalStateException("AppConfigStore cache not initialized.")

    private fun replaceCachedConfigAndEnqueueWrite(context: Context, config: AppConfig, reason: String): AppConfig {
        synchronized(cacheLock) {
            cachedConfig = config
        }
        enqueueConfigWrite(context, config, reason)
        return config
    }

    private fun updateCachedConfigAndEnqueueWrite(
        context: Context,
        reason: String,
        update: (AppConfig) -> AppConfig
    ): AppConfig {
        val updated = synchronized(cacheLock) {
            val current = cachedConfig ?: throw IllegalStateException("AppConfigStore cache not initialized.")
            update(current).also { cachedConfig = it }
        }
        enqueueConfigWrite(context, updated, reason)
        return updated
    }

    private fun enqueueConfigWrite(context: Context, config: AppConfig, reason: String) {
        backgroundWriter.enqueue(
            PendingConfigWrite(
                context = context.applicationContext,
                config = config,
                reason = reason
            )
        )
    }

    private fun readConfigBlockingFromDataStore(context: Context): AppConfig {
        val dataStoreFile = context.filesDir.resolve("datastore/app_config.pb")
        CaltopoClient.CTDebug(TAG, "readConfigBlockingFromDataStore(): exists=${dataStoreFile.exists()} size=${if (dataStoreFile.exists()) dataStoreFile.length() else 0}")
        return runBlocking {
            context.appConfigDataStore.data
                .catch { ex ->
                    if (ex is IOException) emit(AppConfigSerializer.defaultValue) else throw ex
                }
                .first()
        }
    }

    private fun writeConfigBlockingToDataStore(context: Context, config: AppConfig) {
        runBlocking {
            context.appConfigDataStore.updateData { config }
        }
        val dataStoreFile = context.filesDir.resolve("datastore/app_config.pb")
        CaltopoClient.CTDebug(
            TAG,
            "writeConfigBlockingToDataStore(): exists=${dataStoreFile.exists()} size=${if (dataStoreFile.exists()) dataStoreFile.length() else 0}, ridMappings=${config.ridMappingsCount}, loadedConfigFiles=${config.loadedConfigFilesCount}"
        )
    }

    private fun hasMeaningfulConfig(config: AppConfig): Boolean {
        if (config.schemaVersion != 0) return true
        if (config.legacyImportComplete) return true
        if (config.minDistanceFeet != 0L) return true
        if (config.newTrackDelaySeconds != 0L) return true
        if (config.maxFlatlineToneDurationSeconds != 0L) return true
        if (config.bridgeCheckDistanceFeet != 0L) return true
        if (config.alarmVolumeConfigured) return true
        if (config.alarmVolumePercent != 0) return true
        if (config.maxIdleTimeMinutes != 0L) return true
        if (config.debugLevel != 0) return true
        if (config.coordinateDisplayFormat.isNotBlank()) return true
        if (config.captureVideoStreams) return true
        if (config.usePeers) return true
        if (!config.predictiveHeadEnabled) return true
        if (config.proximityAlertSpacingConfigured) return true
        if (config.proximityAlertSpacingFeet != 0L) return true
        if (config.caltopoTrackFolder.isNotBlank()) return true
        if (config.caltopoDomainAndPort.isNotBlank()) return true
        if (config.caltopoConnectKey.isNotBlank()) return true
        if (config.caltopoCredentials.teamId.isNotBlank()) return true
        if (config.caltopoCredentials.credentialId.isNotBlank()) return true
        if (config.caltopoCredentials.credentialSecret.isNotBlank()) return true
        if (config.incident.isNotBlank()) return true
        if (config.opPeriod.isNotBlank()) return true
        if (config.trackerApiKey.isNotBlank()) return true
        if (config.trackerUrlPrefix.isNotBlank()) return true
        if (config.trackerFaaProxyUrl.isNotBlank()) return true
        if (config.trackerEnrollmentUrl.isNotBlank()) return true
        if (config.archiveLocation.treeUri.isNotBlank()) return true
        if (config.archiveLocation.selectionHintUri.isNotBlank()) return true
        if (config.archiveLocation.requiresRegrant) return true
        if (config.notam.enabled) return true
        if (config.notam.radiusNm != 0) return true
        if (config.notam.autoRefresh) return true
        if (config.notam.refreshIntervalSeconds != 0) return true
        if (config.notam.warnInsideOneNm) return true
        if (config.notam.apiBaseUrl.isNotBlank()) return true
        if (config.notam.tokenUrl.isNotBlank()) return true
        if (config.notam.clientId.isNotBlank()) return true
        if (config.notam.clientSecret.isNotBlank()) return true
        if (config.notam.scope.isNotBlank()) return true
        if (config.notam.lastUpdatedEpochMs != 0L) return true
        if (config.faaRemoteConfig.token.isNotBlank()) return true
        if (config.faaRemoteConfig.payloadEnc.isNotBlank()) return true
        if (config.hasLandRestrictions()) return true
        if (config.ridMappingsCount > 0) return true
        if (config.loadedConfigFilesCount > 0) return true
        if (config.mutualAidTemplate.teamId.isNotBlank()) return true
        if (config.mutualAidTemplate.credentialId.isNotBlank()) return true
        if (config.mutualAidTemplate.credentialSecret.isNotBlank()) return true
        if (config.mutualAidTemplate.domainAndPort.isNotBlank()) return true
        if (config.mutualAidTemplate.sourceLabel.isNotBlank()) return true
        if (config.mutualAidTemplate.targetFolderHint.isNotBlank()) return true
        if (config.mutualAidTemplate.connectKey.isNotBlank()) return true
        if (config.caltopoProfilesCount > 0) return true
        if (config.activeCaltopoProfileId.isNotBlank()) return true
        return false
    }

    internal fun resolveProximitySpacingFeet(config: AppConfig): Long = when {
            config.proximityAlertSpacingConfigured && config.proximityAlertSpacingFeet >= 0L ->
                config.proximityAlertSpacingFeet
            config.schemaVersion in 3..5 && config.proximityAlertSpacingFeet > 0L ->
                config.proximityAlertSpacingFeet
            else -> 100L
        }.coerceAtLeast(50L)

    private fun toClientState(config: AppConfig): ClientClassState {
        val profiles = effectiveProfiles(config)
        val activeProfile = selectActiveProfile(config, profiles)
        val state = ClientClassState()
        state.minDistanceInFeet = if (config.minDistanceFeet > 0) config.minDistanceFeet else CaltopoClient.MIN_DISTANCE_IN_FEET
        state.archivePath = config.archiveLocation.treeUri
        state.caltopoTrackFolder = activeProfile?.trackFolder?.ifBlank { "Drone Tracks" }
            ?: config.caltopoTrackFolder.ifBlank { "Drone Tracks" }
        state.caltopoDomainAndPort = activeProfile?.domainAndPort?.ifBlank { "caltopo.com" }
            ?: config.caltopoDomainAndPort.ifBlank { "caltopo.com" }
        state.caltopoConnectKey = activeProfile?.connectKey ?: config.caltopoConnectKey
        state.caltopoCredentials = activeProfile?.credentials ?: CaltopoCredentials(
            config.caltopoCredentials.teamId,
            config.caltopoCredentials.credentialId,
            config.caltopoCredentials.credentialSecret
        )
        state.caltopoCredentialOrigin = config.caltopoCredentialOrigin.ifBlank {
            CaltopoClient.CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN
        }
        state.newTrackDelayInSeconds = if (config.newTrackDelaySeconds > 0) config.newTrackDelaySeconds else 30
        state.maxFlatlineToneDurationInSeconds = when {
            config.schemaVersion < 10 &&
                config.maxFlatlineToneDurationSeconds == config.newTrackDelaySeconds ->
                CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS
            config.maxFlatlineToneDurationSeconds > 0 -> config.maxFlatlineToneDurationSeconds
            else -> CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS
        }
        state.bridgeCheckDistanceFeet = when {
            config.bridgeCheckDistanceFeet > 0L -> config.bridgeCheckDistanceFeet
            else -> CaltopoClient.DEFAULT_BRIDGE_CHECK_DISTANCE_FEET
        }
        state.alarmVolumeConfigured = config.alarmVolumeConfigured
        state.alarmVolumePercent = when {
            config.alarmVolumeConfigured && config.alarmVolumePercent in 0..100 -> config.alarmVolumePercent
            !config.alarmVolumeConfigured && config.alarmVolumePercent in 1..100 -> config.alarmVolumePercent
            else -> CaltopoClient.DEFAULT_ALARM_VOLUME_PERCENT
        }
        state.debugLevel = config.debugLevel
        state.maxIdleTimeInMinutes = resolveMaximumIdleMinutes(config)
        state.incident = activeProfile?.incident?.ifBlank { "Training" } ?: config.incident.ifBlank { "Training" }
        state.opPeriod = activeProfile?.opPeriod?.ifBlank { "1" } ?: config.opPeriod.ifBlank { "1" }
        state.trackerApiKey = activeProfile?.trackerApiKey ?: config.trackerApiKey
        state.trackerUrlPfx = activeProfile?.trackerUrlPfx ?: config.trackerUrlPrefix
        state.trackerFaaProxyUrl = config.trackerFaaProxyUrl
        state.trackerEnrollmentUrl = config.trackerEnrollmentUrl
        state.coordinateDisplayFormat = config.coordinateDisplayFormat.ifBlank { "decimal" }
        state.captureVideoStreamsFlag = config.captureVideoStreams
        state.usePeersFlag = config.usePeers
        state.standaloneR2cCoordinationEnabled = false // Legacy configs cannot enable standalone flight sharing.
        state.predictiveHeadEnabled = false // Retired; legacy true cannot reactivate projection.
        state.proximityAlertSpacingFeet = resolveProximitySpacingFeet(config)
        state.notamEnabled = config.notam.enabled
        state.notamRadiusNm = when {
            config.notam.radiusNm >= 1 -> config.notam.radiusNm
            else -> 1
        }
        state.notamAutoRefresh = if (config.schemaVersion >= 2) config.notam.autoRefresh else true
        state.notamRefreshIntervalSeconds = when {
            config.notam.refreshIntervalSeconds > 0 -> config.notam.refreshIntervalSeconds
            else -> 1800
        }
        state.notamWarnInsideOneNm = if (config.schemaVersion >= 2) config.notam.warnInsideOneNm else true
        state.notamApiBaseUrl = config.notam.apiBaseUrl
        state.notamTokenUrl = config.notam.tokenUrl
        state.notamClientId = config.notam.clientId
        state.notamClientSecret = config.notam.clientSecret
        state.notamScope = config.notam.scope
        state.notamLastUpdatedEpochMs = config.notam.lastUpdatedEpochMs
        val landRestrictionDefaults = resolveLandRestrictionDefaults(config)
        state.landRestrictionsEnabled = landRestrictionDefaults.enabled
        state.landRestrictionsShowOnMap = landRestrictionDefaults.showOnMap
        state.landRestrictionsAutoRefresh = landRestrictionDefaults.autoRefresh
        state.landRestrictionsRadiusNm = landRestrictionDefaults.radiusStatuteMiles
        state.landRestrictionsLastUpdatedEpochMs = config.landRestrictions.lastUpdatedEpochMs
        state.faaRemoteToken = config.faaRemoteConfig.token
        state.faaConfigLabel = config.faaRemoteConfig.label
        state.faaPayloadEnc = config.faaRemoteConfig.payloadEnc
        state.faaLastValidatedEpochMs = config.faaRemoteConfig.lastValidatedEpochMs
        state.faaConfigStale = config.faaRemoteConfig.stale
        state.faaLastFailureReason = config.faaRemoteConfig.lastFailureReason
        FaaConfigManager.applyCachedPayloadToState(state)
        state.cachedDroneSpecTable = Hashtable<String, CtDroneSpec>(16)
        for (mapping in config.ridMappingsList) {
            val ownerFields = RidMappingRules.resolveOwnerFields(
                ownerName = mapping.ownerName,
                ownerCallsign = mapping.ownerCallsign,
                legacyOwner = mapping.owner,
                mappedId = mapping.mappedId,
                model = mapping.model,
                remoteId = mapping.remoteId
            )
            val spec = CtDroneSpec(
                mapping.remoteId,
                mapping.mappedId,
                mapping.org,
                mapping.model,
                ownerFields.ownerName,
                ownerFields.ownerCallsign
            )
            spec.readiness = AircraftReadiness.fromJSON(
                mapping.readinessJson.takeIf { it.isNotBlank() }?.let { org.json.JSONObject(it) })
            state.cachedDroneSpecTable[spec.remoteId] = spec
        }
        state.configFilesLoaded = loadedConfigFilesDisplay(config)
        state.mutualAidTemplate = fromProtoTemplate(config.mutualAidTemplate)
        state.caltopoProfiles = ArrayList(profiles)
        state.activeCaltopoProfileId = activeProfile?.profileId ?: config.activeCaltopoProfileId
        state.droneSpecTable = Hashtable<String, CtDroneSpec>(16)
        return state
    }

    private fun mergeStateIntoConfig(
        current: AppConfig,
        state: ClientClassState,
        archivePermissionMissing: Boolean
    ): AppConfig {
        val profiles = mutableListOf<CaltopoProfileRecord>().apply {
            addAll(state.caltopoProfiles ?: effectiveProfiles(current))
        }
        val activeProfileId = selectActiveProfileId(current, state, profiles)
        val activeProfile = syncActiveProfileFromState(
            state = state,
            profiles = profiles,
            activeProfileId = activeProfileId
        )
        val builder = current.toBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setLegacyImportComplete(true)
            .setMinDistanceFeet(state.minDistanceInFeet)
            .setNewTrackDelaySeconds(state.newTrackDelayInSeconds)
            .setMaxFlatlineToneDurationSeconds(
                if (state.maxFlatlineToneDurationInSeconds > 0) {
                    state.maxFlatlineToneDurationInSeconds
                } else {
                    CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS
                }
            )
            .setBridgeCheckDistanceFeet(
                if (state.bridgeCheckDistanceFeet > 0) {
                    state.bridgeCheckDistanceFeet
                } else {
                    CaltopoClient.DEFAULT_BRIDGE_CHECK_DISTANCE_FEET
                }
            )
            .setAlarmVolumePercent(state.alarmVolumePercent.coerceIn(0, 100))
            .setAlarmVolumeConfigured(state.alarmVolumeConfigured)
            .setMaxIdleTimeMinutes(state.maxIdleTimeInMinutes)
            .setDebugLevel(state.debugLevel)
            .setCoordinateDisplayFormat(state.coordinateDisplayFormat ?: "decimal")
            .setCaptureVideoStreams(state.captureVideoStreamsFlag)
            .setUsePeers(state.usePeersFlag)
            .setStandaloneR2CCoordinationEnabled(false)
            .setPredictiveHeadEnabled(state.predictiveHeadEnabled)
            .setProximityAlertSpacingFeet(state.proximityAlertSpacingFeet)
            .setProximityAlertSpacingConfigured(true)
            .setCaltopoTrackFolder(activeProfile.trackFolder)
            .setCaltopoDomainAndPort(activeProfile.domainAndPort)
            .setCaltopoConnectKey(activeProfile.connectKey ?: "")
            .setIncident(activeProfile.incident)
            .setOpPeriod(activeProfile.opPeriod)
            .setTrackerApiKey(activeProfile.trackerApiKey)
            .setTrackerUrlPrefix(activeProfile.trackerUrlPfx)
            .setTrackerFaaProxyUrl(state.trackerFaaProxyUrl ?: "")
            .setTrackerEnrollmentUrl(state.trackerEnrollmentUrl ?: "")
            .setNotam(
                AppConfig.NotamConfig.newBuilder()
                    .setEnabled(state.notamEnabled)
                    .setRadiusNm(state.notamRadiusNm)
                    .setAutoRefresh(state.notamAutoRefresh)
                    .setRefreshIntervalSeconds(state.notamRefreshIntervalSeconds)
                    .setWarnInsideOneNm(state.notamWarnInsideOneNm)
                    .setApiBaseUrl(state.notamApiBaseUrl ?: "")
                    .setTokenUrl(state.notamTokenUrl ?: "")
                    .setClientId(if (state.faaPayloadEnc.isNullOrBlank()) state.notamClientId ?: "" else "")
                    .setClientSecret(if (state.faaPayloadEnc.isNullOrBlank()) state.notamClientSecret ?: "" else "")
                    .setScope(state.notamScope ?: "")
                    .setLastUpdatedEpochMs(state.notamLastUpdatedEpochMs)
                    .build()
            )
            .setFaaRemoteConfig(
                AppConfig.FaaRemoteConfig.newBuilder()
                    .setToken(state.faaRemoteToken ?: "")
                    .setLabel(state.faaConfigLabel ?: "")
                    .setPayloadEnc(state.faaPayloadEnc ?: "")
                    .setLastValidatedEpochMs(state.faaLastValidatedEpochMs)
                    .setStale(state.faaConfigStale)
                    .setLastFailureReason(state.faaLastFailureReason ?: "")
                    .build()
            )
            .setLandRestrictions(
                AppConfig.LandRestrictionConfig.newBuilder()
                    .setEnabled(state.landRestrictionsEnabled)
                    .setShowOnMap(state.landRestrictionsShowOnMap)
                    .setAutoRefresh(state.landRestrictionsAutoRefresh)
                    .setRadiusNm(state.landRestrictionsRadiusNm.coerceIn(1, 50))
                    .setLastUpdatedEpochMs(state.landRestrictionsLastUpdatedEpochMs)
                    .build()
            )
            .setCaltopoCredentials(
                AppConfig.CaltopoCredentialsConfig.newBuilder()
                    .setTeamId(activeProfile.credentials.teamId ?: "")
                    .setCredentialId(activeProfile.credentials.credentialId ?: "")
                    .setCredentialSecret(activeProfile.credentials.credentialSecret ?: "")
                    .build()
            )
            .setCaltopoCredentialOrigin(
                state.caltopoCredentialOrigin?.ifBlank {
                    CaltopoClient.CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN
                } ?: CaltopoClient.CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN
            )
            .setActiveCaltopoProfileId(activeProfile.profileId)

        val currentArchive = current.archiveLocation
        val archivePath = state.archivePath ?: ""
        val hint = when {
            archivePath.isNotBlank() -> archivePath
            currentArchive.selectionHintUri.isNotBlank() -> currentArchive.selectionHintUri
            else -> currentArchive.treeUri
        }
        builder.setArchiveLocation(
            AppConfig.ArchiveLocation.newBuilder()
                .setTreeUri(archivePath)
                .setSelectionHintUri(hint)
                .setRequiresRegrant(archivePermissionMissing || (archivePath.isBlank() && currentArchive.requiresRegrant))
                .build()
        )

        builder.clearRidMappings()
        state.cachedDroneSpecTable.values
            .sortedBy { it.remoteId }
            .forEach { spec ->
                builder.addRidMappings(
                    AppConfig.RidMapping.newBuilder()
                        .setRemoteId(spec.remoteId ?: "")
                        .setMappedId(spec.mappedId ?: "")
                        .setOrg(spec.org ?: "")
                        .setModel(spec.model ?: "")
                        .setOwner(spec.owner ?: "")
                        .setOwnerName(spec.ownerName ?: "")
                        .setOwnerCallsign(spec.owner ?: "")
                        .setReadinessJson(spec.readiness.toJSON().toString())
                        .build()
                )
            }

        val importedRecords = if (current.loadedConfigFilesCount == 0) {
            parseLegacyLoadedConfigFiles(state.configFilesLoaded)
        } else {
            current.loadedConfigFilesList
        }
        builder.clearLoadedConfigFiles()
        builder.addAllLoadedConfigFiles(importedRecords.take(MAX_LOADED_CONFIG_FILES))
        builder.setMutualAidTemplate((state.mutualAidTemplate ?: fromProtoTemplate(current.mutualAidTemplate)).toProto())
        builder.clearCaltopoProfiles()
        builder.addAllCaltopoProfiles(profiles.map { it.toProto() })
        return builder.build()
    }

    private fun effectiveProfiles(config: AppConfig): List<CaltopoProfileRecord> {
        if (config.caltopoProfilesCount > 0) {
            return config.caltopoProfilesList.map { fromProtoProfile(it) }
        }
        return listOfNotNull(migrateLegacyProfile(config))
    }

    private fun selectActiveProfile(
        config: AppConfig,
        profiles: List<CaltopoProfileRecord>
    ): CaltopoProfileRecord? {
        if (profiles.isEmpty()) return null
        val requestedId = config.activeCaltopoProfileId
        return profiles.firstOrNull { it.profileId == requestedId }
            ?: profiles.firstOrNull { it.profileType == "HOME" }
            ?: profiles.first()
    }

    private fun selectActiveProfileId(
        current: AppConfig,
        state: ClientClassState,
        profiles: List<CaltopoProfileRecord>
    ): String {
        val requested = state.activeCaltopoProfileId
        if (!requested.isNullOrBlank() && profiles.any { it.profileId == requested }) return requested
        val existing = current.activeCaltopoProfileId
        if (existing.isNotBlank() && profiles.any { it.profileId == existing }) return existing
        return profiles.firstOrNull { it.profileType == "HOME" }?.profileId
            ?: profiles.firstOrNull()?.profileId
            ?: DEFAULT_HOME_PROFILE_ID
    }

    private fun migrateLegacyProfile(config: AppConfig): CaltopoProfileRecord? {
        val hasLegacy =
            config.caltopoCredentials.teamId.isNotBlank() ||
                config.caltopoCredentials.credentialId.isNotBlank() ||
                config.caltopoCredentials.credentialSecret.isNotBlank() ||
                config.caltopoDomainAndPort.isNotBlank() ||
                config.caltopoTrackFolder.isNotBlank() ||
                config.incident.isNotBlank() ||
                config.opPeriod.isNotBlank() ||
                config.trackerApiKey.isNotBlank() ||
                config.trackerUrlPrefix.isNotBlank()
        if (!hasLegacy) return null
        return CaltopoProfileRecord(
            DEFAULT_HOME_PROFILE_ID,
            "Default",
            "HOME",
            CaltopoCredentials(
                config.caltopoCredentials.teamId,
                config.caltopoCredentials.credentialId,
                config.caltopoCredentials.credentialSecret
            ),
            config.caltopoDomainAndPort.ifBlank { "caltopo.com" },
            config.caltopoTrackFolder.ifBlank { "Drone Tracks" },
            config.incident.ifBlank { "Training" },
            config.opPeriod.ifBlank { "1" },
            config.trackerApiKey,
            config.trackerUrlPrefix,
            false,
            0L,
            false,
            "",
            "",
            "",
            "",
            0L,
            ""
        ).also { it.connectKey = config.caltopoConnectKey }
    }

    private fun buildDefaultHomeProfile(state: ClientClassState): CaltopoProfileRecord =
        CaltopoProfileRecord(
            DEFAULT_HOME_PROFILE_ID,
            "Default",
            "HOME",
            state.caltopoCredentials ?: CaltopoCredentials(),
            state.caltopoDomainAndPort ?: "caltopo.com",
            state.caltopoTrackFolder ?: "Drone Tracks",
            state.incident ?: "Training",
            state.opPeriod ?: "1",
            state.trackerApiKey ?: "",
            state.trackerUrlPfx ?: "",
            false,
            0L,
            false,
            "",
            "",
            "",
            "",
            0L,
            ""
        ).also { it.connectKey = state.caltopoConnectKey ?: "" }

    private fun syncActiveProfileFromState(
        state: ClientClassState,
        profiles: MutableList<CaltopoProfileRecord>,
        activeProfileId: String
    ): CaltopoProfileRecord {
        val base = profiles.firstOrNull { it.profileId == activeProfileId } ?: buildDefaultHomeProfile(state)
        val synced = CaltopoProfileRecord(
            if (base.profileId.isNotBlank()) base.profileId else DEFAULT_HOME_PROFILE_ID,
            if (base.displayName.isNotBlank()) base.displayName else "Default",
            if (base.profileType.isNotBlank()) base.profileType else "HOME",
            state.caltopoCredentials ?: base.credentials ?: CaltopoCredentials(),
            state.caltopoDomainAndPort ?: base.domainAndPort,
            state.caltopoTrackFolder ?: base.trackFolder,
            state.incident ?: base.incident,
            state.opPeriod ?: base.opPeriod,
            state.trackerApiKey ?: base.trackerApiKey,
            state.trackerUrlPfx ?: base.trackerUrlPfx,
            base.autoConnect,
            base.expiresAtEpochMs,
            base.quietRemoveOnExpiry,
            base.sourceLabel,
            base.targetMapId,
            base.targetMapTitle,
            base.targetFolderHint,
            base.importedAtEpochMs,
            base.importDedupeKey
        ).also { it.connectKey = state.caltopoConnectKey ?: base.connectKey ?: "" }
        val idx = profiles.indexOfFirst { it.profileId == synced.profileId }
        if (idx >= 0) {
            profiles[idx] = synced
        } else {
            profiles.add(synced)
        }
        state.caltopoProfiles = ArrayList(profiles)
        state.activeCaltopoProfileId = synced.profileId
        return synced
    }

    private fun fromProtoProfile(profile: AppConfig.CaltopoProfile): CaltopoProfileRecord =
        CaltopoProfileRecord(
            profile.profileId,
            profile.displayName,
            when (profile.profileType) {
                AppConfig.CaltopoProfileType.CALTOPO_PROFILE_TYPE_MUTUAL_AID -> "MUTUAL_AID"
                else -> "HOME"
            },
            CaltopoCredentials(
                profile.teamId,
                profile.credentialId,
                profile.credentialSecret
            ),
            profile.domainAndPort,
            profile.trackFolder,
            profile.incident,
            profile.opPeriod,
            profile.trackerApiKey,
            profile.trackerUrlPrefix,
            profile.autoConnect,
            profile.expiresAtEpochMs,
            profile.quietRemoveOnExpiry,
            profile.sourceLabel,
            profile.targetMapId,
            profile.targetMapTitle,
            profile.targetFolderHint,
            profile.importedAtEpochMs,
            profile.importDedupeKey
        ).also { it.connectKey = profile.connectKey }

    private fun mergeLoadedConfigFiles(
        current: List<AppConfig.LoadedConfigFileRecord>,
        dedupeKey: String,
        display: String,
        loadedAtMs: Long
    ): List<AppConfig.LoadedConfigFileRecord> {
        val newRecord = AppConfig.LoadedConfigFileRecord.newBuilder()
            .setDedupeKey(dedupeKey)
            .setDisplayText(display)
            .setLoadedAtEpochMs(loadedAtMs)
            .build()
        val tail = if (current.firstOrNull()?.dedupeKey == dedupeKey) current.drop(1) else current
        return buildList {
            add(newRecord)
            addAll(tail.take(MAX_LOADED_CONFIG_FILES - 1))
        }
    }

    private fun parseLegacyLoadedConfigFiles(raw: String?): List<AppConfig.LoadedConfigFileRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_LOADED_CONFIG_FILES)
            .asReversed()
            .mapIndexed { index, line ->
                AppConfig.LoadedConfigFileRecord.newBuilder()
                    .setDedupeKey(line)
                    .setDisplayText(line)
                    .setLoadedAtEpochMs(index.toLong())
                    .build()
            }
    }

    private fun loadedConfigFilesDisplay(config: AppConfig): String = config.loadedConfigFilesList
        .joinToString(separator = "\n") { it.displayText }

    private fun CaltopoProfileRecord.toProto(): AppConfig.CaltopoProfile =
        AppConfig.CaltopoProfile.newBuilder()
            .setProfileId(profileId)
            .setDisplayName(displayName)
            .setProfileType(
                if (profileType == "MUTUAL_AID") {
                    AppConfig.CaltopoProfileType.CALTOPO_PROFILE_TYPE_MUTUAL_AID
                } else {
                    AppConfig.CaltopoProfileType.CALTOPO_PROFILE_TYPE_HOME
                }
            )
            .setTeamId(credentials.teamId ?: "")
            .setCredentialId(credentials.credentialId ?: "")
            .setCredentialSecret(credentials.credentialSecret ?: "")
            .setDomainAndPort(domainAndPort)
            .setTrackFolder(trackFolder)
            .setIncident(incident)
            .setOpPeriod(opPeriod)
            .setTrackerApiKey(trackerApiKey)
            .setTrackerUrlPrefix(trackerUrlPfx)
            .setAutoConnect(autoConnect)
            .setExpiresAtEpochMs(expiresAtEpochMs)
            .setQuietRemoveOnExpiry(quietRemoveOnExpiry)
            .setSourceLabel(sourceLabel)
            .setTargetMapId(targetMapId)
            .setTargetMapTitle(targetMapTitle)
            .setTargetFolderHint(targetFolderHint)
            .setImportedAtEpochMs(importedAtEpochMs)
            .setImportDedupeKey(importDedupeKey)
            .setConnectKey(connectKey ?: "")
            .build()

    private fun fromProtoTemplate(template: AppConfig.MutualAidTemplate): MutualAidTemplateRecord =
        MutualAidTemplateRecord(
            template.teamId,
            template.credentialId,
            template.credentialSecret,
            template.domainAndPort.ifBlank { "caltopo.com" },
            template.sourceLabel,
            template.targetFolderHint
        ).also { it.connectKey = template.connectKey }

    private fun MutualAidTemplateRecord.toProto(): AppConfig.MutualAidTemplate =
        AppConfig.MutualAidTemplate.newBuilder()
            .setTeamId(teamId ?: "")
            .setCredentialId(credentialId ?: "")
            .setCredentialSecret(credentialSecret ?: "")
            .setDomainAndPort(domainAndPort ?: "")
            .setSourceLabel(sourceLabel ?: "")
            .setTargetFolderHint(targetFolderHint ?: "")
            .setConnectKey(connectKey ?: "")
            .build()

    private fun hasPersistedReadPermission(resolver: ContentResolver, uri: Uri): Boolean {
        val permissions: List<UriPermission> = resolver.persistedUriPermissions
        return permissions.any { permission -> permission.uri == uri && permission.isReadPermission }
    }

    private fun requestBackup(context: Context, reason: String) {
        try {
            BackupManager(context).dataChanged()
            CaltopoClient.CTDebug(TAG, "requestBackup(): signaled BackupManager after $reason")
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "requestBackup(): unable to signal BackupManager after $reason", e)
        }
        GoogleDriveConfigSync.scheduleUpload(context, reason)
    }
}
