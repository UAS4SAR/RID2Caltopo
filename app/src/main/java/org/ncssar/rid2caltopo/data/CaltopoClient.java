
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.LoggingLevelName;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Pair;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.app.AppIdleAlarmReceiver;
import org.ncssar.rid2caltopo.app.MediaMTXService;
import org.ncssar.rid2caltopo.app.R2CActivity;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.app.ScanningService;
import org.ncssar.rid2caltopo.notam.NotamCenter;
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter;
import org.ncssar.rid2caltopo.ui.ProximityAlertCenter;
import org.ncssar.rid2caltopo.video.PairedVideoFlightActivity;
import org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry;
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime;
import com.google.firebase.analytics.FirebaseAnalytics;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class CaltopoProfileRecord {
    public String profileId;
    public String displayName;
    public String profileType;
    public CaltopoCredentials credentials;
    public String domainAndPort;
    public String trackFolder;
    public String incident;
    public String opPeriod;
    public String trackerApiKey;
    public String trackerUrlPfx;
    public boolean autoConnect;
    public long expiresAtEpochMs;
    public boolean quietRemoveOnExpiry;
    public String sourceLabel;
    public String targetMapId;
    public String targetMapTitle;
    public String targetFolderHint;
    public long importedAtEpochMs;
    public String importDedupeKey;
    public String connectKey;

    public CaltopoProfileRecord() {
        this.profileId = "";
        this.displayName = "";
        this.profileType = "HOME";
        this.credentials = new CaltopoCredentials();
        this.domainAndPort = "caltopo.com";
        this.trackFolder = "Drone Tracks";
        this.incident = "Training";
        this.opPeriod = "1";
        this.trackerApiKey = "";
        this.trackerUrlPfx = "";
        this.autoConnect = false;
        this.expiresAtEpochMs = 0L;
        this.quietRemoveOnExpiry = false;
        this.sourceLabel = "";
        this.targetMapId = "";
        this.targetMapTitle = "";
        this.targetFolderHint = "";
        this.importedAtEpochMs = 0L;
        this.importDedupeKey = "";
        this.connectKey = "";
    }

    public CaltopoProfileRecord(
            @NonNull String profileId,
            @NonNull String displayName,
            @NonNull String profileType,
            @NonNull CaltopoCredentials credentials,
            @NonNull String domainAndPort,
            @NonNull String trackFolder,
            @NonNull String incident,
            @NonNull String opPeriod,
            @NonNull String trackerApiKey,
            @NonNull String trackerUrlPfx,
            boolean autoConnect,
            long expiresAtEpochMs,
            boolean quietRemoveOnExpiry,
            @NonNull String sourceLabel,
            @NonNull String targetMapId,
            @NonNull String targetMapTitle,
            @NonNull String targetFolderHint,
            long importedAtEpochMs,
            @NonNull String importDedupeKey
    ) {
        this.profileId = profileId;
        this.displayName = displayName;
        this.profileType = profileType;
        this.credentials = credentials;
        this.domainAndPort = domainAndPort;
        this.trackFolder = trackFolder;
        this.incident = incident;
        this.opPeriod = opPeriod;
        this.trackerApiKey = trackerApiKey;
        this.trackerUrlPfx = trackerUrlPfx;
        this.autoConnect = autoConnect;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.quietRemoveOnExpiry = quietRemoveOnExpiry;
        this.sourceLabel = sourceLabel;
        this.targetMapId = targetMapId;
        this.targetMapTitle = targetMapTitle;
        this.targetFolderHint = targetFolderHint;
        this.importedAtEpochMs = importedAtEpochMs;
        this.importDedupeKey = importDedupeKey;
        this.connectKey = "";
    }
}

class MutualAidTemplateRecord {
    public String teamId;
    public String credentialId;
    public String credentialSecret;
    public String domainAndPort;
    public String sourceLabel;
    public String targetFolderHint;
    public String connectKey;

    public MutualAidTemplateRecord() {
        this.teamId = "";
        this.credentialId = "";
        this.credentialSecret = "";
        this.domainAndPort = "caltopo.com";
        this.sourceLabel = "";
        this.targetFolderHint = "MAI";
        this.connectKey = "";
    }

    public MutualAidTemplateRecord(
            @NonNull String teamId,
            @NonNull String credentialId,
            @NonNull String credentialSecret,
            @NonNull String domainAndPort,
            @NonNull String sourceLabel,
            @NonNull String targetFolderHint
    ) {
        this.teamId = teamId;
        this.credentialId = credentialId;
        this.credentialSecret = credentialSecret;
        this.domainAndPort = domainAndPort;
        this.sourceLabel = sourceLabel;
        this.targetFolderHint = targetFolderHint;
        this.connectKey = "";
    }
}

/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState {
    public long minDistanceInFeet;
    public String archivePath;
    public String caltopoTrackFolder;
    public String caltopoDomainAndPort;
    public String caltopoConnectKey;
    public CaltopoCredentials caltopoCredentials;
    public String caltopoCredentialOrigin;
    public long newTrackDelayInSeconds;
    public long maxFlatlineToneDurationInSeconds;
    public long bridgeCheckDistanceFeet;
    public int alarmVolumePercent;
    public boolean alarmVolumeConfigured;
    public int debugLevel;
    public long maxIdleTimeInMinutes;
    public String incident;
    public String opPeriod;
    public String trackerApiKey;
    public String trackerUrlPfx;
    public String trackerFaaProxyUrl;
    public String trackerEnrollmentUrl;
    public String coordinateDisplayFormat;
    public boolean captureVideoStreamsFlag;
    public boolean predictiveHeadEnabled;
    public long proximityAlertSpacingFeet;
    public boolean notamEnabled;
    public int notamRadiusNm;
    public boolean notamAutoRefresh;
    public int notamRefreshIntervalSeconds;
    public boolean notamWarnInsideOneNm;
    public String notamApiBaseUrl;
    public String notamTokenUrl;
    public String notamClientId;
    public String notamClientSecret;
    public String notamScope;
    public long notamLastUpdatedEpochMs;
    public boolean landRestrictionsEnabled;
    public boolean landRestrictionsShowOnMap;
    public boolean landRestrictionsAutoRefresh;
    public int landRestrictionsRadiusNm;
    public long landRestrictionsLastUpdatedEpochMs;
    public String faaRemoteToken;
    public String faaConfigLabel;
    public String faaPayloadEnc;
    public long faaLastValidatedEpochMs;
    public boolean faaConfigStale;
    public String faaLastFailureReason;
    public Hashtable<String, CtDroneSpec> cachedDroneSpecTable;  // Table to map remoteIDs to their data
    public String configFilesLoaded;
    public MutualAidTemplateRecord mutualAidTemplate;
    public ArrayList<CaltopoProfileRecord> caltopoProfiles;
    public String activeCaltopoProfileId;
    transient public Hashtable<String, CtDroneSpec> droneSpecTable; // app lifespan only.
    transient public boolean usePeersFlag; // newline separated list of configfile specs that we're loaded.
    public boolean standaloneR2cCoordinationEnabled;

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        archivePath = "";
        caltopoTrackFolder = "Drone Tracks";
        caltopoCredentials = new CaltopoCredentials();
        caltopoCredentialOrigin = CaltopoClient.CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN;
        caltopoDomainAndPort = "caltopo.com";
        caltopoConnectKey = "";
        usePeersFlag = true;
        standaloneR2cCoordinationEnabled = false;
        newTrackDelayInSeconds = 30;
        maxFlatlineToneDurationInSeconds = CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS;
        bridgeCheckDistanceFeet = CaltopoClient.DEFAULT_BRIDGE_CHECK_DISTANCE_FEET;
        alarmVolumePercent = CaltopoClient.DEFAULT_ALARM_VOLUME_PERCENT;
        alarmVolumeConfigured = false;
        maxIdleTimeInMinutes = CaltopoClient.DEFAULT_MAX_IDLE_TIME_MINUTES;
        debugLevel = -1; // undefined.
        incident = "Training";
        opPeriod = "1";
        trackerApiKey = "";
        trackerUrlPfx = "";
        trackerFaaProxyUrl = "";
        trackerEnrollmentUrl = "";
        coordinateDisplayFormat = "decimal";
        captureVideoStreamsFlag = false;
        predictiveHeadEnabled = true;
        proximityAlertSpacingFeet = 40L;
        notamEnabled = true;
        notamRadiusNm = 1;
        notamAutoRefresh = true;
        notamRefreshIntervalSeconds = 1800;
        notamWarnInsideOneNm = true;
        notamApiBaseUrl = "";
        notamTokenUrl = "";
        notamClientId = "";
        notamClientSecret = "";
        notamScope = "";
        notamLastUpdatedEpochMs = 0L;
        landRestrictionsEnabled = true;
        landRestrictionsShowOnMap = true;
        landRestrictionsAutoRefresh = true;
        landRestrictionsRadiusNm = 1;
        landRestrictionsLastUpdatedEpochMs = 0L;
        faaRemoteToken = "";
        faaConfigLabel = "";
        faaPayloadEnc = "";
        faaLastValidatedEpochMs = 0L;
        faaConfigStale = false;
        faaLastFailureReason = "";
        configFilesLoaded = "";
        mutualAidTemplate = new MutualAidTemplateRecord();
        caltopoProfiles = new ArrayList<>();
        activeCaltopoProfileId = "";
        cachedDroneSpecTable = new Hashtable<>(16);
        droneSpecTable = new Hashtable<>(16);
    }
    @Override
    @NonNull
    public String toString() {
        CaltopoCredentials cred = caltopoCredentials;
        String domainAndPort = "";
        String teamId = "";
        String credId = "";
        String credSecret = "";
        if (null != caltopoDomainAndPort && !caltopoDomainAndPort.isEmpty()) {
            domainAndPort = caltopoDomainAndPort;
        }
        if (null != cred) {
            if (null != cred.teamId && !cred.teamId.isEmpty()) {
                // teamId = cfg.teamId;
                teamId = "###";
            }
            if (null != cred.credentialId && !cred.credentialId.isEmpty()) {
                // credId = cfg.credentialId;
                credId = "######";
            }
            if (null != cred.credentialSecret && !cred.credentialSecret.isEmpty()) {
                // credSecret = cfg.credentialSecret;
                credSecret = "###########";
            }
        }

        return String.format(Locale.US,
                """
                        vers:'%d', minDist:'%d' ft, usePeersFlag:'%s', captureVideoStreamsFlag:'%s'
                        newTrackDelayInSec:%d, maxFlatlineToneDurationInSec:%d, bridgeCheckDistanceFeet:%d, alarmVolumePercent:%d, alarmVolumeConfigured:%s, debugLevel:%s, maxIdleTimeInMinutes:%d, incident:%s, opPeriod:%s, coordinateDisplayFormat:%s
                        predictiveHeadEnabled:%s, proximityAlertSpacingFeet:%d
                        notamEnabled:%s, notamRadiusNm:%d, notamAutoRefresh:%s, notamRefreshIntervalSeconds:%d, notamWarnInsideOneNm:%s
                        notamApiBaseUrl:'%s', notamTokenUrl:'%s', notamClientId:'%s', notamClientSecret:'%s', notamScope:'%s', notamLastUpdatedEpochMs:%d
                        landRestrictionsEnabled:%s, landRestrictionsShowOnMap:%s, landRestrictionsAutoRefresh:%s, landRestrictionsRadiusNm:%d, landRestrictionsLastUpdatedEpochMs:%d
                        faaConfigLabel:'%s', faaTokenPresent:%s, faaPayloadCached:%s, faaStale:%s, faaLastValidatedEpochMs:%d, faaLastFailureReason:'%s'
                        activeCaltopoProfileId:'%s', caltopoProfiles:%d, maTemplateConfigured:%s
                        archivePath: '%s', caltopoTrackFolder: '%s', caltopoDomainAndPort:%s,
                        teamId: '%s', credId: '%s' credSecret: '%s', dronespecs: %s,\n loaded configFiles:\n  %s""",
                AppConfigStore.SCHEMA_VERSION, minDistanceInFeet, usePeersFlag, captureVideoStreamsFlag,
                newTrackDelayInSeconds, maxFlatlineToneDurationInSeconds, bridgeCheckDistanceFeet,
                alarmVolumePercent, alarmVolumeConfigured, LoggingLevelName(debugLevel), maxIdleTimeInMinutes,
                incident, opPeriod, coordinateDisplayFormat, predictiveHeadEnabled, proximityAlertSpacingFeet,
                notamEnabled, notamRadiusNm, notamAutoRefresh, notamRefreshIntervalSeconds, notamWarnInsideOneNm,
                notamApiBaseUrl, notamTokenUrl, notamClientId.isEmpty() ? "" : "######",
                notamClientSecret.isEmpty() ? "" : "###########", notamScope, notamLastUpdatedEpochMs,
                landRestrictionsEnabled, landRestrictionsShowOnMap, landRestrictionsAutoRefresh,
                landRestrictionsRadiusNm, landRestrictionsLastUpdatedEpochMs,
                faaConfigLabel, !faaRemoteToken.isEmpty(), !faaPayloadEnc.isEmpty(), faaConfigStale,
                faaLastValidatedEpochMs, faaLastFailureReason,
                activeCaltopoProfileId, caltopoProfiles != null ? caltopoProfiles.size() : 0,
                mutualAidTemplate != null && CaltopoCredentials.sniffTest(
                        new CaltopoCredentials(
                                mutualAidTemplate.teamId,
                                mutualAidTemplate.credentialId,
                                mutualAidTemplate.credentialSecret
                        )
                ),
                archivePath, caltopoTrackFolder, domainAndPort, teamId, credId, credSecret,
                CaltopoClient.DroneSpecStringRep(cachedDroneSpecTable),
                configFilesLoaded.replaceAll("\\n", "  \n"));
    }
}

public class CaltopoClient implements CtDroneSpec.CtDroneSpecListener {
    public interface ClientSettingsListener {
        void settingsChanged();
    }

    // CaltopoClient CLASS VARS:
    private static ClientSettingsListener SettingsListener = null;

    static final long MIN_DISTANCE_IN_FEET = 2;
    static final long MIN_NEW_TRACK_DELAY_IN_SECONDS = 15;
    public static final long DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS = 5;
    public static final long LOSS_OF_SIGNAL_TONE_DURATION_SECONDS = 2;
    public static final long OUT_OF_RANGE_DISTANCE_FEET = 300;
    public static final long RETURN_TO_TAKEOFF_DISTANCE_FEET = 30;
    public static final long TAKEOFF_NEAR_TABLET_DISTANCE_FEET = 50;
    public static final long HOME_LOCATION_MAX_TABLET_AGE_MS = 60 * 1000;
    public static final int DEFAULT_ALARM_VOLUME_PERCENT = 100;
    public static final long DEFAULT_MAX_IDLE_TIME_MINUTES = 30;
    static final long DEFAULT_BRIDGE_CHECK_DISTANCE_FEET = 20;
    static final long MainThreadId = Process.myTid();
    static final long ProcessId = Process.myPid();
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    public static final String CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN = "unknown";
    public static final String CALTOPO_CREDENTIAL_ORIGIN_INDEPENDENT = "independent";
    public static final String CALTOPO_CREDENTIAL_ORIGIN_TRACKER = "tracker";
    private static final String ICON_LATENCY_TAG = "RidIconLatency";
    public static final int DebugLevelError = 0;
    public static final int DebugLevelWarn = 1;
    public static final int DebugLevelDebug = 2;
    public static final int DebugLevelInfo = 3;
    public static int DebugLevel = DebugLevelDebug;
    private static final Object DebugTagFilterLock = new Object();
    private static boolean DebugTagFilterEnabled = false;
    private static final Set<String> DebugTagFilter = new HashSet<>();
    private static boolean DebugTagRegistryInitialized = false;
    private static final int LivePublishThreadPoolSize = 2;
    private static final int GeoJsonStatsThreadPoolSize = 2;
    private static final int ArchiveScanThreadPoolSize = 1;
    private static Hashtable<String, CaltopoClient> ClientMap;
    private static ExecutorService LivePublishExecutorPool = null;
    private static ExecutorService GeoJsonStatsExecutorPool = null;
    private static ExecutorService ArchiveScanExecutorPool = null;
    private static ClientClassState Ccstate = null;
    private static final String BACKUP_PREFS_NAME = "rid2caltopo_public_state";
    private static final String BACKUP_KEY_ARCHIVE_PATH = "archive_path_uri";
    private static final String BACKUP_KEY_ARCHIVE_HINT = "archive_hint_uri";
    private static String LogFilePath;
    private static final int STARTUP_LOG_BUFFER_BYTES = 256 * 1024;
    private static OutputStream DebugOutputStream = new DeferredLogOutputStream(STARTUP_LOG_BUFFER_BYTES);
    private static long BytesWrittenToDebugOutputStream;
    private static final long MAX_SIZE_DEBUG_OUTPUT = 10000000;
    private static CopyOnWriteArrayList<CtDroneSpec.DroneSpecsChangedListener> DroneSpecsChangedListeners = new CopyOnWriteArrayList<>();
    private static CopyOnWriteArrayList<CtDroneSpec.DroneConfirmationCandidateListener> DroneConfirmationCandidateListeners = new CopyOnWriteArrayList<>();
    private static Uri DebugLogPath = null;
    private static DelayedExec AppIdleDelay = new DelayedExec(false);
    private static volatile long AppActiveStartedAtMsec = System.currentTimeMillis();
    private static FirebaseAnalytics FBAnalytics;
    private static final Object ShutdownLock = new Object();
    private static final Object DebugLogLock = new Object();
    private static boolean ShutdownInProgress = false;
    private static boolean AppExitRequested = false;
    private static long DebugLogGeneration = 0L;
    private static volatile File TemporaryArchiveRootForSession = null;

    private static void ResetDebugOutputStream() {
        synchronized (DebugLogLock) {
            ResetDebugOutputStreamLocked();
        }
    }

    private static void ResetDebugOutputStreamLocked() {
        DebugOutputStream = new DeferredLogOutputStream(STARTUP_LOG_BUFFER_BYTES);
        DebugLogPath = null;
        LogFilePath = null;
        BytesWrittenToDebugOutputStream = 0;
        DebugLogGeneration++;
    }
    private static volatile boolean ArchivePermissionMissingFlag = false;

    // CaltopoClient INSTANCE VARS:=
    private final String remoteId;
    private CtDroneSpec droneSpec;
    private CaltopoLiveTrack liveTrack;

    private final DelayedExec idleTimeoutPoll;
    private static DelayedExec UiUpdatePoll = new DelayedExec();
    ;
    private static long PreviousEarliestAgeOutInMsec = 0;
    private static final ArrayList<CtDroneSpec> DsArray = new ArrayList<>(16);
    private static long DroneSpecsArraySize = DsArray.size();
    private static boolean ProcessingDroneSpecUpdate = false;
    private static boolean PendingDroneSpecUpdate = false;
    private static boolean NotifySettingsChangedFlag;
    private static final Set<String> SessionUnknownDroneRemoteIds = new HashSet<>();
    private static final Set<String> SessionConfirmedDroneRemoteIds = new HashSet<>();
    private static final Set<String> CurrentPeerConfirmedDroneRemoteIds = new HashSet<>();

    @NonNull
    private static String CanonicalRemoteId(@NonNull String remoteId) {
        return remoteId.replaceAll("[^A-Z0-9]", "");
    }

    private static boolean IsCanonicalRemoteId(@NonNull String remoteId) {
        return !remoteId.isEmpty() && remoteId.equals(CanonicalRemoteId(remoteId));
    }

    private static boolean RejectNonCanonicalRemoteId(@NonNull String source, @NonNull String remoteId) {
        if (IsCanonicalRemoteId(remoteId)) return false;
        CTError(TAG, String.format(Locale.US,
                "%s received non-canonical remoteId='%s' canonicalWouldBe='%s'",
                source, remoteId, CanonicalRemoteId(remoteId)));
        return true;
    }

    private static void RequireCanonicalRemoteId(@NonNull String source, @NonNull String remoteId) {
        if (RejectNonCanonicalRemoteId(source, remoteId)) {
            throw new RuntimeException(String.format(Locale.US,
                    "%s requires canonical Remote ID but received '%s'", source, remoteId));
        }
    }

    private static final class DeferredLogOutputStream extends OutputStream {
        private final Object lock = new Object();
        private final int maxBufferedBytes;
        private ByteArrayOutputStream startupBuffer;
        private OutputStream delegate;

        DeferredLogOutputStream(int maxBufferedBytes) {
            this.maxBufferedBytes = maxBufferedBytes;
            this.startupBuffer = new ByteArrayOutputStream(Math.min(maxBufferedBytes, 16 * 1024));
        }

        void attach(@NonNull OutputStream target) throws IOException {
            synchronized (lock) {
                if (delegate == target) return;
                if (startupBuffer != null && startupBuffer.size() > 0) {
                    startupBuffer.writeTo(target);
                    target.flush();
                    startupBuffer.reset();
                    startupBuffer = null;
                }
                delegate = target;
            }
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (lock) {
                if (delegate != null) {
                    delegate.write(b);
                    return;
                }
                if (startupBuffer != null && startupBuffer.size() < maxBufferedBytes) {
                    startupBuffer.write(b);
                }
            }
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            synchronized (lock) {
                if (delegate != null) {
                    delegate.write(b, off, len);
                    return;
                }
                if (startupBuffer == null || len <= 0) return;
                int remaining = maxBufferedBytes - startupBuffer.size();
                if (remaining <= 0) return;
                startupBuffer.write(b, off, Math.min(len, remaining));
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (lock) {
                if (delegate != null) {
                    delegate.flush();
                }
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                if (delegate != null) {
                    delegate.flush();
                    delegate.close();
                    delegate = null;
                }
                if (startupBuffer != null) {
                    startupBuffer.reset();
                    startupBuffer = null;
                }
            }
        }
    }

    public CaltopoClient(String rid) throws RuntimeException {
        ClientClassState ccs = GetState();

        if (null == rid || rid.isEmpty()) {
            throw new RuntimeException("CaltopoClient() constructor missing/invalid remoteId");
        }
        RequireCanonicalRemoteId("CaltopoClient()", rid);
        remoteId = rid;
        CtDroneSpec lDroneSpec = ccs.droneSpecTable.get(rid); // Is this one already active?
        if (null != lDroneSpec) { // already in the active table — re-use it directly
            droneSpec = lDroneSpec;
        } else {
            lDroneSpec = ccs.cachedDroneSpecTable.get(rid); // check persistent cache
            if (null != lDroneSpec) { // found it.  Make a working copy:
                lDroneSpec = lDroneSpec.copy();
                ccs.droneSpecTable.put(rid, lDroneSpec);
                droneSpec = lDroneSpec;
            } else { // new - never seen before - make a working version.
                lDroneSpec = new CtDroneSpec(rid);
                lDroneSpec.setLocalArchiveOnly(true);
                ccs.droneSpecTable.put(rid, lDroneSpec);
                droneSpec = lDroneSpec;
            }
        }
        if (SessionUnknownDroneRemoteIds.contains(rid)) {
            droneSpec.setLocalArchiveOnly(true);
        }
        droneSpec.setDroneSpecListener(this);
        idleTimeoutPoll = new DelayedExec();
    }

    public static void AddDroneSpecsChangedListener(CtDroneSpec.DroneSpecsChangedListener newListener) {
        DroneSpecsChangedListeners.add(newListener);
        if (false) CTDebug(TAG, String.format(Locale.US,
                "AddDroneSpecsChangedListener() Adding 0x%x:%s, count:%d",
                newListener.hashCode(), newListener.getClass().getName(), DroneSpecsChangedListeners.size()));
        UpdateDroneSpecs();
    }

    public static void AddDroneConfirmationCandidateListener(CtDroneSpec.DroneConfirmationCandidateListener newListener) {
        DroneConfirmationCandidateListeners.add(newListener);
        if (false) CTDebug(TAG, String.format(Locale.US,
                "AddDroneConfirmationCandidateListener() Adding 0x%x:%s, count:%d",
                newListener.hashCode(), newListener.getClass().getName(), DroneConfirmationCandidateListeners.size()));
    }

    private static void NotifyDroneConfirmationCandidate(@NonNull CtDroneSpec droneSpec) {
        for (CtDroneSpec.DroneConfirmationCandidateListener listener : DroneConfirmationCandidateListeners) {
            listener.onDroneConfirmationCandidate(droneSpec);
        }
    }

    @Nullable
    public static CtDroneSpec GetDroneSpec(@NonNull String remoteId) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("GetDroneSpec()", remoteId)) return null;
        CtDroneSpec ds = ccs.droneSpecTable.get(remoteId);
        if (null == ds) {
            ds = ccs.cachedDroneSpecTable.get(remoteId);
            if (null != ds) {
                ds = ds.copy();
                ccs.droneSpecTable.put(remoteId, ds);
            }
        }
        return ds;
    }

    /** Reset active-flight aging when any RID message is associated with this aircraft.
     * This deliberately does not update position freshness or create a new aircraft track. */
    public static void NoteActiveAircraftMessageReceived(@NonNull String remoteId, long nowWallMsec) {
        if (RejectNonCanonicalRemoteId("NoteActiveAircraftMessageReceived()", remoteId)) return;
        CtDroneSpec ds = GetState().droneSpecTable.get(remoteId);
        if (ds == null || !ds.isActive()) return;
        ds.noteAircraftMessageReceived(nowWallMsec);
    }

    @NonNull
    public static List<CtDroneSpec> GetPersistedDroneSpecs() {
        ClientClassState ccs = GetState();
        ArrayList<CtDroneSpec> specs = new ArrayList<>(ccs.cachedDroneSpecTable.size());
        for (CtDroneSpec ds : ccs.cachedDroneSpecTable.values()) {
            if (ds != null) specs.add(ds.copy());
        }
        specs.sort((left, right) -> {
            int mappedCompare = left.getMappedId().compareToIgnoreCase(right.getMappedId());
            if (mappedCompare != 0) return mappedCompare;
            return left.getRemoteId().compareToIgnoreCase(right.getRemoteId());
        });
        return specs;
    }

    public static void ReplacePersistedDroneSpecs(
            @NonNull String organization,
            @NonNull List<EditableRidMapping> mappings
    ) {
        List<String> errors = RidMappingRules.INSTANCE.validate(organization, mappings);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.get(0));
        }
        ClientClassState ccs = GetState();
        Hashtable<String, CtDroneSpec> replacements = new Hashtable<>(Math.max(16, mappings.size()));
        String normalizedOrg = RidMappingRules.INSTANCE.normalizeOrganization(organization);
        for (EditableRidMapping mapping : mappings) {
            String remoteId = RidMappingRules.INSTANCE.normalizeRemoteId(mapping.getRemoteId());
            String callsign = mapping.getOwnerCallsign().trim();
            String model = mapping.getModel().trim();
            CtDroneSpec spec = new CtDroneSpec(
                    remoteId,
                    CtDroneSpec.BuildMappedId(callsign, model, remoteId),
                    normalizedOrg,
                    model,
                    mapping.getOwnerName().trim(),
                    callsign
            );
            replacements.put(remoteId, spec);
        }
        ccs.cachedDroneSpecTable = replacements;
        SetHomeOrgName(normalizedOrg);
        UpdateDroneSpecs();
        ArchiveState("RID mappings edited in Admin settings");
    }

    @NonNull
    private static String NormalizeOrgName(@Nullable String orgName) {
        if (orgName == null) return "";
        return orgName.trim().toUpperCase(Locale.US);
    }

    public static boolean IsKnownTeamDroneForTrackerUpload(@Nullable String remoteId, @Nullable String droneOrg) {
        if (remoteId == null) return false;
        String trimmedRemoteId = remoteId.trim();
        if (RejectNonCanonicalRemoteId("IsKnownTeamDroneForTrackerUpload()", trimmedRemoteId)) return false;
        String normalizedDroneOrg = NormalizeOrgName(droneOrg);
        String normalizedTrackerOrg = NormalizeOrgName(GetTrackerUploadOrgName());
        if (normalizedDroneOrg.isEmpty() ||
                normalizedTrackerOrg.isEmpty() ||
                !normalizedDroneOrg.equals(normalizedTrackerOrg)) {
            return false;
        }
        CtDroneSpec registeredDrone = GetDroneSpec(trimmedRemoteId);
        if (registeredDrone == null || registeredDrone.isLocalArchiveOnly()) return false;
        if (!CurrentPeerConfirmedDroneRemoteIds.contains(trimmedRemoteId)) return false;
        return normalizedDroneOrg.equals(NormalizeOrgName(registeredDrone.getOrg()));
    }

    public static boolean IsTrackerUploadIdentityForConfiguredOrg(
            @Nullable String remoteId,
            @Nullable String droneOrg
    ) {
        if (remoteId == null) return false;
        String trimmedRemoteId = remoteId.trim();
        if (RejectNonCanonicalRemoteId(
                "IsTrackerUploadIdentityForConfiguredOrg()", trimmedRemoteId)) return false;
        String normalizedDroneOrg = NormalizeOrgName(droneOrg);
        String normalizedTrackerOrg = NormalizeOrgName(GetTrackerUploadOrgName());
        return !normalizedDroneOrg.isEmpty() &&
                normalizedDroneOrg.equals(normalizedTrackerOrg);
    }

    public static boolean IsPersistedTeamDroneForTrackerUpload(
            @Nullable String remoteId,
            @Nullable String droneOrg
    ) {
        if (!IsTrackerUploadIdentityForConfiguredOrg(remoteId, droneOrg) || remoteId == null) {
            return false;
        }
        ClientClassState ccs = GetState();
        if (ccs.cachedDroneSpecTable == null) return false;
        CtDroneSpec registeredDrone = ccs.cachedDroneSpecTable.get(remoteId.trim());
        if (registeredDrone == null || registeredDrone.isLocalArchiveOnly()) return false;
        return NormalizeOrgName(droneOrg).equals(NormalizeOrgName(registeredDrone.getOrg()));
    }

    public static int GetActiveFlightCount() {
        ClientClassState ccs = GetState();
        if (ccs.droneSpecTable == null || ccs.droneSpecTable.isEmpty()) return 0;
        int activeCount = 0;
        for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
            if (ds != null && ds.isActive()) activeCount++;
        }
        return activeCount;
    }

    @NonNull
    public static List<String> GetActiveRemoteIdsSnapshot() {
        ClientClassState ccs = GetState();
        ArrayList<String> remoteIds = new ArrayList<>();
        if (ccs.droneSpecTable == null || ccs.droneSpecTable.isEmpty()) return remoteIds;
        for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
            if (ds != null && ds.isActive()) remoteIds.add(ds.getRemoteId());
        }
        return remoteIds;
    }

    public static int GetActiveFlightCountSnapshot() {
        ClientClassState ccs = Ccstate;
        if (ccs == null || ccs.droneSpecTable == null || ccs.droneSpecTable.isEmpty()) return -1;
        int activeCount = 0;
        for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
            if (ds != null && ds.isActive()) activeCount++;
        }
        return activeCount;
    }

    /**
     * Applies a drone spec received from a peer via MQTT.
     * If the drone is already active (waypoints being tracked), updates it in place so the
     * track label changes immediately.  If the drone has not yet been seen locally, pre-populates
     * the persistent cache so the correct mappedId is used as soon as the first waypoint arrives.
     *
     * Must be safe to call from any thread (MQTT callback thread or main thread).
     */
    public static void ApplyRemoteDroneSpec(@NonNull String remoteId,
                                            @NonNull String mappedId,
                                            @NonNull String org,
                                            @NonNull String model,
                                            @NonNull String owner) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("ApplyRemoteDroneSpec()", remoteId)) return;

        // Case 1: drone is already in the active table — update it directly.
        CtDroneSpec active = ccs.droneSpecTable.get(remoteId);
        if (active != null) {
            active.setMappedId(mappedId); // fires mappedIdChanged → UpdateDroneSpecs
            if (!org.isEmpty())   active.setOrg(org);
            if (!model.isEmpty()) active.setModel(model);
            if (!owner.isEmpty()) active.setOwner(owner);
            return;
        }

        // Case 2: drone not yet active — update or insert in the persistent cache so the
        // spec is ready when the first waypoint arrives and ClientForRemoteId() is called.
        CtDroneSpec cached = ccs.cachedDroneSpecTable.get(remoteId);
        if (cached != null) {
            cached.setMappedId(mappedId);
            if (!org.isEmpty())   cached.setOrg(org);
            if (!model.isEmpty()) cached.setModel(model);
            if (!owner.isEmpty()) cached.setOwner(owner);
        } else {
            CtDroneSpec newSpec = new CtDroneSpec(remoteId, mappedId, org, model, owner);
            ccs.cachedDroneSpecTable.put(remoteId, newSpec);
        }
    }

    public static void SetSettingsListener(@Nullable ClientSettingsListener listener) {
        SettingsListener = listener;
    }

    @NonNull public CtDroneSpec getDroneSpec() {return droneSpec;}

    private static final Handler MainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable CaltopoProfileExpiryRunnable = () -> {
        RemoveExpiredCaltopoProfiles(System.currentTimeMillis(), true);
        ScheduleCaltopoProfileExpiry();
    };
    private static final long WAYPOINT_SIDE_EFFECT_SLOW_MS = 250L;

    private static void logWaypointSideEffectIfSlow(@NonNull String step,
                                                    @NonNull CtDroneSpec droneSpec,
                                                    long elapsedMs) {
        if (elapsedMs < WAYPOINT_SIDE_EFFECT_SLOW_MS) return;
        CTWarn(TAG, String.format(Locale.US,
                "newWaypoint slow step=%s elapsedMs=%d remoteId=%s mappedId=%s thread=%s",
                step, elapsedMs, droneSpec.getRemoteId(), droneSpec.getMappedId(),
                (Looper.myLooper() == Looper.getMainLooper()) ? "main" : "background"));
    }

    private static void UpdateDroneSpecs() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MainHandler.post(() -> ProcessSortedCurrentDroneSpecArray(true));
            return;
        }
        ProcessSortedCurrentDroneSpecArray(true);
    }

    public void mappedIdChanged(@NonNull CtDroneSpec ds, @NonNull String oldval, @NonNull String newval) {
        CTDebug(TAG, String.format(Locale.US,
                "mappedIdChanged(%s): change from '%s' to '%s'", ds.trackLabel(), oldval, newval));
        UpdateDroneSpecs();
        // Propagate the updated spec to all peers on this map so every R2C instance
        // uses the same track label without requiring manual re-entry.
        R2CMqttManager.onDroneSpecChanged(ds.getRemoteId());
    }

    public void droneTakingOff(@NonNull CtDroneSpec ds){
        DroneSpecStatusChanged(ds, true);
    }

    public void droneLanded(@NonNull CtDroneSpec ds){
        terminateTrack("Drone Landed", true);
    }

    public static String LoggingLevelName(int loggingLevel) {
        return switch (loggingLevel) {
            case DebugLevelError -> "Errors only";
            case DebugLevelWarn -> "Warnings";
            case DebugLevelDebug -> "Debugs";
            case DebugLevelInfo -> "Ludicrous";
            default -> "";
        };
    }

    public static String BumpLoggingLevel() {
        DebugLevel++;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String retval = LoggingLevelName(DebugLevel);
        ArchiveState("Logging level changed to: " + retval);
        return retval;
    }

    public static String SetLoggingLevel(int loggingLevel) {
        DebugLevel = loggingLevel;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String retval = LoggingLevelName(DebugLevel);
        ArchiveState("Logging level changed to: " + retval);
        return retval;
    }

    public static boolean CTDebugEnabled(@Nullable String tag) {
        RegisterDebugTag(tag);
        if (DebugLevel < DebugLevelDebug) return false;
        if (!DebugTagFilterEnabled) return true;
        if (tag == null || tag.isEmpty()) return false;
        synchronized (DebugTagFilterLock) {
            return DebugTagFilter.contains(tag);
        }
    }

    public static void ClearDebugTagFilter() {
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.clear();
            DebugTagFilterEnabled = false;
        }
    }

    public static void SetDebugTagFilter(@Nullable String csvTags) {
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.clear();
            if (csvTags != null) {
                String[] tags = csvTags.split(",");
                for (String raw : tags) {
                    String tag = raw.trim();
                    if (!tag.isEmpty()) {
                        RegisterDebugTag(tag);
                        DebugTagFilter.add(tag);
                    }
                }
            }
            DebugTagFilterEnabled = !DebugTagFilter.isEmpty();
        }
    }

    public static void AddDebugTagFilter(@NonNull String tag) {
        String t = tag.trim();
        if (t.isEmpty()) return;
        RegisterDebugTag(t);
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.add(t);
            DebugTagFilterEnabled = true;
        }
    }

    public static void RemoveDebugTagFilter(@NonNull String tag) {
        String t = tag.trim();
        if (t.isEmpty()) return;
        synchronized (DebugTagFilterLock) {
            DebugTagFilter.remove(t);
            DebugTagFilterEnabled = !DebugTagFilter.isEmpty();
        }
    }

    public static boolean IsDebugTagFilterEnabled() {
        synchronized (DebugTagFilterLock) {
            return DebugTagFilterEnabled;
        }
    }

    @NonNull
    public static String GetDebugTagFilterCsv() {
        synchronized (DebugTagFilterLock) {
            if (DebugTagFilter.isEmpty()) return "";
            List<String> sorted = new ArrayList<>(DebugTagFilter);
            sorted.sort(String::compareTo);
            return String.join(",", sorted);
        }
    }

    public static void RegisterDebugTag(@Nullable String tag) {
        InitializeDebugTagRegistry();
        DebugTagRegistry.registerTag(tag);
    }

    public static void RegisterDebugTags(@NonNull List<String> tags) {
        InitializeDebugTagRegistry();
        DebugTagRegistry.registerTags(tags);
    }

    @NonNull
    public static List<String> GetRegisteredDebugTags() {
        InitializeDebugTagRegistry();
        return DebugTagRegistry.getTags();
    }

    private static synchronized void InitializeDebugTagRegistry() {
        if (DebugTagRegistryInitialized) return;
        List<String> builtInTags = Arrays.asList(
                "CaltopoClient",
                "CaltopoHybridBrowser",
                "CaltopoLiveTrack",
                "CaltopoMap",
                "CaltopoMapHierarchy",
                "CaltopoOp",
                "CaltopoSession",
                "CtDroneSpec",
                "DelayedExec",
                "DesignatorIndicator",
                "FfmpegBridge",
                "FfmpegProbeService",
                "ffmpeg_bridge",
                "MainScreen",
                "MediaMTXService",
                "R2CMqttManager",
                "R2CView",
                "R2CViewModel",
                "RidIconLatency",
                "ScanningService",
                "ServerTemplate",
                "StreamPlayer",
                "StreamTile",
                "StreamSessionService",
                "StreamsGrid",
                "StreamsViewModel",
                "MapCacheDebug",
                "MapCacheTile",
                "MapCacheDEM",
                "MapCacheIcon",
                "MapCacheStore",
                "WaypointTrack"
        );
        DebugTagRegistry.registerTags(builtInTags);
        DebugTagRegistryInitialized = true;
    }

    @Nullable
    public static Uri GetDebugLogUri() {
        return DebugLogPath;
    }



    public static void SubmitClue(
            CtDroneSpec droneSpec,
            Bitmap clueImage,
            double clueLat,
            double clueLng,
            double clueAlt,
            String clueTitle,
            String clueDescription,
            long clueTimestamp
    ) {
        CTDebug(TAG, String.format(Locale.US,
                "SubmitClue() received for %s(%s):%s lat=%.6f lng=%.6f alt=%.1f",
                droneSpec.getMappedId(), droneSpec.getRemoteId(), clueTitle,
                clueLat, clueLng, clueAlt));
        WaypointTrack.AddClueForTrack(droneSpec, clueLat, clueLng, clueAlt,
                clueTimestamp, clueTitle, clueDescription, clueImage);
        CaltopoMap.SubmitClueWithPhoto(droneSpec, clueLat, clueLng, clueAlt, clueTitle, clueDescription, clueTimestamp, clueImage);
    }

    public static void CTLog(String type, String tag, String msg) {
        synchronized (DebugLogLock) {
            OutputStream os = DebugOutputStream;
            if (null == os) return;
            if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) return;

            try {
                if (null != type && null != tag) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                            "ddLLLHHmmss.SSS z XXX", Locale.US);
                    msg = String.format(Locale.US, "%s %s: %s %s\n  ", type,
                            ZonedDateTime.now().format(formatter), tag,
                            RedactLocationFromDiagnosticMessage(msg));
                }
                byte[] bytes = msg.getBytes();
                BytesWrittenToDebugOutputStream += bytes.length;
                os.write(bytes);
                os.flush();
            } catch (IOException e) {
                Log.e(TAG, String.format(Locale.US, "CTError: CTLog(): Not able to write '%s' - %s", LogFilePath, e));
            }
            if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) {
                Log.e(TAG, "CTError: CTLog(): Sorry.  Maximum debugging output file size reached.  Future bits will be tossed on the floor.");
            }
        }
    }

    private static final Pattern DIAGNOSTIC_COORDINATE_KEY_VALUE = Pattern.compile(
            "(?i)(?:\\b(?:lat(?:itude)?|lon(?:gitude)?|lng)\\b)\\s*(?:=|:|%3d)\\s*[-+]?\\d{1,3}(?:\\.\\d+)?");
    private static final Pattern DIAGNOSTIC_COORDINATE_CONTAINER = Pattern.compile(
            "(?i)(?:[\\\"]coordinates[\\\"]\\s*:|<coordinates>|\\b(?:center|bbox|bounds)\\s*=\\s*[-+]?\\d"
                    + "|[-+]?\\d{1,8}\\.\\d{4,}\\s*,\\s*[-+]?\\d{1,8}\\.\\d{4,}"
                    + "|\\bz=\\d+\\s+x=\\d+\\s+y=\\d+|/tile/\\d+/\\d+/\\d+"
                    + "|\\b[ns]\\d{1,2}[ew]\\d{1,3}\\b)");
    private static final Pattern DIAGNOSTIC_LOCATION_PAYLOAD = Pattern.compile(
            "(?i)(?:DJI_SEI_(?:HEX|PAYLOAD)[^\\n]*\\bpayload=)");

    @NonNull
    public static String RedactLocationFromDiagnosticMessage(@Nullable String message) {
        if (message == null) return "";
        if (DIAGNOSTIC_COORDINATE_KEY_VALUE.matcher(message).find()
                || DIAGNOSTIC_COORDINATE_CONTAINER.matcher(message).find()
                || DIAGNOSTIC_LOCATION_PAYLOAD.matcher(message).find()) {
            return "[location details redacted]";
        }
        return message;
    }

    public static void CTEvent(@NonNull String tag, @NonNull String eventName, @Nullable Bundle parameters) {
        FirebaseAnalytics fbAnalytics = GetFBAnalytics();
        if (fbAnalytics == null) return;
        String cleanEventName = eventName.replaceAll("[^a-zA-Z0-9]", "_");
        fbAnalytics.logEvent("r2c_" + cleanEventName, parameters);
        CTDebug(tag, String.format(Locale.US, "CTEvent(r2c_%s): %s", cleanEventName, parameters));
    }

    public static void CTEventAsync(
            @NonNull String tag,
            @NonNull String eventName,
            @Nullable Bundle parameters) {
        Thread eventThread = new Thread(
                () -> CTEvent(tag, eventName, parameters),
                "R2C-ExitEvent");
        eventThread.start();
    }

    public static void CTInfo(String tag, String msg) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelInfo) {
            if (!CTDebugEnabled(tag)) return;
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            CTLog("INFO" + tidString, tag, msg);
            msg = "CTInfo" + tidString + ": " + msg;
            Log.i(tag, msg);
        }
    }

    public static void CTDebug(String tag, String msg) {
        RegisterDebugTag(tag);
        if (!CTDebugEnabled(tag)) return;
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        CTLog("DEBUG" + tidString, tag, msg);
        msg = "CTDebug" + tidString + ": " + msg;
        Log.d(tag, msg);
    }

    public static void CTError(String tag, String msg) {
        RegisterDebugTag(tag);
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        CTLog("ERROR" + tidString, tag, msg);
        msg = "CTError" + tidString + ": " + msg;
        Log.e(tag, msg);
    }

    public static String ExceptionToString(Exception e) {
        StringBuilder str = new StringBuilder();
        str.append(e);
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            str.append("\n    ");
            str.append(element);
        }
        return str.toString();
    }

    public static void CTError(String tag, String msg, Exception e) {
        RegisterDebugTag(tag);
        long myTid = Process.myTid();
        String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
        StringBuilder str = new StringBuilder();

        str.append(msg);
        str.append("\n  ");
        str.append(ExceptionToString(e));
        CTLog("ERROR" + tidString, tag, str.toString());
        str.insert(0, "CTError: ");
        Log.e(tag, str.toString());
    }

    public static void CTWarn(String tag, String msg) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelWarn) {
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            CTLog("WARN" + tidString, tag, msg);
            msg = "CTWarn" + tidString + ": " + msg;
            Log.w(tag, msg);
        }
    }

    public static void CTWarn(String tag, String msg, Exception e) {
        RegisterDebugTag(tag);
        if (DebugLevel >= DebugLevelWarn) {
            long myTid = Process.myTid();
            String tidString = "[" + ProcessId + "-" + ((MainThreadId == myTid) ? "main]" : myTid + "]");
            StringBuilder str = new StringBuilder();
            str.append(msg);
            str.append("\n  ");
            str.append(ExceptionToString(e));
            CTLog("WARN" + tidString, tag, str.toString());
            str.insert(0, "CTWarn" + tidString + ": ");
            Log.w(tag, msg);
        }
    }

    public static void OpenBufferContentsInBrowser(@NonNull String buffer) {
        DocumentFile trackDir = GetTodaysTrackDir();
        Context ctxt = R2CApplication.getAppCtxt();
        R2CActivity r2CActivity = R2CActivity.getR2CActivity();

        try {
            String filename = "open_map_failed_" + TimeDatestampString(System.currentTimeMillis());
            DocumentFile dataFilepath = trackDir.createFile("text/html", filename);
            ContentResolver resolver = ctxt.getContentResolver();
            OutputStream outputStream = resolver.openOutputStream(dataFilepath.getUri());
            outputStream.write(buffer.getBytes());
            outputStream.flush();
            outputStream.close();
            r2CActivity.openUri(dataFilepath.getUri().toString(), "text/html");
        } catch (Exception e) {
            CTError(TAG, "OpenBufferContentsInBrowser() raised. ", e);
        }
    }

    @Nullable
    public static DocumentFile GetArchiveDir() {
        Uri archiveUri = GetArchiveUri();
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return null;
        if (archiveUri == null) return GetTemporaryArchiveDirForSession();
        return DocumentFile.fromTreeUri(ctxt, archiveUri);
    }

    /**
     * Create/find a directory within the ArchiveDir with todays date
     * and return that as the directory to place trackfiles and logs in.
     *
     * @return DocumentFile path to existing directory on success and
     * null on failure.
     */
    @Nullable
    public static DocumentFile GetTodaysTrackDir() {
        DocumentFile todaysDir = null;
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) return null;
        DocumentFile archiveDir = GetArchiveDir();
        if (null != archiveDir) try {
            SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy", Locale.US);
            String dirpath = "tracks-" + sdf.format(new Date());
            todaysDir = archiveDir.findFile(dirpath);
            if (null == todaysDir) {
                todaysDir = archiveDir.createDirectory(dirpath);
                if (null == todaysDir) {
                    CTError(TAG, String.format(Locale.US, "GetTodaysTrackDir(): Not able to create '%s'", archiveDir));
                } else {
                    CTDebug(TAG, String.format(Locale.US, "GetTodaysTrackDir(): Created '%s'", archiveDir));
                }
            } else {
                CTDebug(TAG, String.format(Locale.US, "GetTodaysTrackDir(): found existing '%s'", archiveDir));
            }
        } catch (Exception e) {
            CTError(TAG, "Not able to create today's archive dir", e);
        }
        return todaysDir;
    }

    public static String GetTrackFolderName() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null && profile.trackFolder != null) {
            return profile.trackFolder;
        }
        return ccs.caltopoTrackFolder;
    }

    @NonNull
    public static String GetConnectKey() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null && profile.connectKey != null) {
            return profile.connectKey;
        }
        return ccs.caltopoConnectKey != null ? ccs.caltopoConnectKey : "";
    }

    public static void SetConnectKey(@NonNull String value) {
        String trimmed = value.trim();
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (trimmed.equals(ccs.caltopoConnectKey)) return;
        ccs.caltopoConnectKey = trimmed;
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null) profile.connectKey = trimmed;
        NotifySettingsChanged();
        ArchiveState("CalTopo Connect Key changed");
    }

    /**
     * Setting a null or empty track folder name is OK!.  That tells the client
     * to put the tracks in the default track directory.
     *
     * @param folderName Folder to put tracks into - may be null or empty.
     */
    public static void SetTrackFolderName(String folderName) {
        ClientClassState ccs = GetState();
        boolean stateChanged = false;
        if (null != folderName && null != ccs.caltopoTrackFolder) {
            if (!folderName.equals(ccs.caltopoTrackFolder)) {
                stateChanged = true;
            }
        } else if (null != ccs.caltopoTrackFolder || null != folderName) {
            stateChanged = true;
        }

        if (stateChanged) {
            ccs.caltopoTrackFolder = folderName;
            ArchiveState("Caltopo Track Folder changed.");
        }
    }

    @NonNull
    public static CaltopoCredentials GetCaltopoCredentials() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null && profile.credentials != null) {
            return profile.credentials;
        }
        return ccs.caltopoCredentials;
    }

    @Nullable
    public static CaltopoProfileRecord GetActiveCaltopoProfile() {
        ClientClassState ccs = GetState();
        if (ccs.caltopoProfiles == null || ccs.caltopoProfiles.isEmpty()) return null;
        if (ccs.activeCaltopoProfileId != null && !ccs.activeCaltopoProfileId.isEmpty()) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if (ccs.activeCaltopoProfileId.equals(profile.profileId)) return profile;
            }
        }
        return ccs.caltopoProfiles.get(0);
    }

    @NonNull
    public static List<CaltopoProfileRecord> GetCaltopoProfiles() {
        ClientClassState ccs = GetState();
        if (ccs.caltopoProfiles == null) return new ArrayList<>();
        return new ArrayList<>(ccs.caltopoProfiles);
    }

    @NonNull
    public static List<Pair<String, String>> GetMapBrowserProfileOptions() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        ArrayList<Pair<String, String>> options = new ArrayList<>();
        if (ccs.caltopoProfiles == null) return options;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            String label;
            if ("HOME".equals(profile.profileType)) {
                label = "Home";
            } else if ("MUTUAL_AID".equals(profile.profileType)) {
                label = (profile.displayName != null && !profile.displayName.isEmpty())
                        ? profile.displayName
                        : "Mutual Aid";
            } else {
                label = (profile.displayName != null && !profile.displayName.isEmpty())
                        ? profile.displayName
                        : profile.profileId;
            }
            options.add(Pair.create(profile.profileId, label));
        }
        return options;
    }

    /** Non-secret profile metadata for credential selectors. */
    @NonNull
    public static List<String[]> GetOperationalProfileOptions() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        ArrayList<String[]> options = new ArrayList<>();
        if (ccs.caltopoProfiles == null) return options;
        long nowMs = System.currentTimeMillis();
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (HasExpired(profile, nowMs)) continue;
            boolean mutualAid = "MUTUAL_AID".equals(profile.profileType);
            String baseLabel = profile.sourceLabel != null ? profile.sourceLabel.trim() : "";
            if (baseLabel.isEmpty() && profile.displayName != null) {
                baseLabel = profile.displayName.trim();
            }
            if (baseLabel.isEmpty()) baseLabel = mutualAid ? "Mutual Aid" : "Home";
            String credentialLabel = mutualAid && !baseLabel.toUpperCase(Locale.US).endsWith("-MA")
                    ? baseLabel + "-MA"
                    : baseLabel;
            options.add(new String[] {
                    profile.profileId != null ? profile.profileId : "",
                    credentialLabel,
                    mutualAid ? "Mutual Aid" : "Home organization",
                    Long.toString(profile.expiresAtEpochMs)
            });
        }
        return options;
    }

    @Nullable
    public static CaltopoProfileRecord GetCaltopoProfileById(@NonNull String profileId) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles == null) return null;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (profileId.equals(profile.profileId)) return profile;
        }
        return null;
    }

    @Nullable
    public static String GetActiveCaltopoProfileId() {
        ClientClassState ccs = GetState();
        return ccs.activeCaltopoProfileId;
    }

    public static boolean SetActiveCaltopoProfileId(@NonNull String profileId, boolean reconnect) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles == null) return false;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (!profileId.equals(profile.profileId)) continue;
            ccs.activeCaltopoProfileId = profileId;
            mirrorProfileIntoLegacyFields(ccs, profile);
            ArchiveState("active caltopo profile changed");
            if (reconnect) {
                CaltopoMap.ResetMapConnection(0);
            }
            NotifySettingsChanged();
            return true;
        }
        return false;
    }

    public static void UpsertCaltopoProfile(
            @NonNull CaltopoProfileRecord profile,
            boolean makeActive,
            boolean reconnect
    ) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles == null) {
            ccs.caltopoProfiles = new ArrayList<>();
        }
        boolean replaced = false;
        for (int i = 0; i < ccs.caltopoProfiles.size(); i++) {
            CaltopoProfileRecord existing = ccs.caltopoProfiles.get(i);
            boolean idMatch = profile.profileId != null && profile.profileId.equals(existing.profileId);
            boolean dedupeMatch = profile.importDedupeKey != null &&
                    !profile.importDedupeKey.isEmpty() &&
                    profile.importDedupeKey.equals(existing.importDedupeKey);
            boolean profileTypeSlotMatch =
                    ("HOME".equals(profile.profileType) || "MUTUAL_AID".equals(profile.profileType)) &&
                    profile.profileType.equals(existing.profileType);
            if (!idMatch && !dedupeMatch && !profileTypeSlotMatch) continue;
            ccs.caltopoProfiles.set(i, profile);
            replaced = true;
            break;
        }
        if (!replaced) {
            ccs.caltopoProfiles.add(profile);
        }
        if (makeActive || ccs.activeCaltopoProfileId == null || ccs.activeCaltopoProfileId.isEmpty()) {
            ccs.activeCaltopoProfileId = profile.profileId;
            mirrorProfileIntoLegacyFields(ccs, profile);
        }
        ArchiveState(replaced ? "caltopo profile updated" : "caltopo profile added");
        if (makeActive && reconnect) {
            CaltopoMap.ResetMapConnection(0);
        }
        ScheduleCaltopoProfileExpiry();
        NotifySettingsChanged();
    }

    public static boolean RemoveCaltopoProfile(
            @NonNull String profileId,
            boolean fallbackToHome,
            boolean reconnect
    ) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles == null || ccs.caltopoProfiles.isEmpty()) return false;
        boolean removedActive = false;
        boolean removed = false;
        ArrayList<CaltopoProfileRecord> survivors = new ArrayList<>(ccs.caltopoProfiles.size());
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (profileId.equals(profile.profileId)) {
                removed = true;
                if (profileId.equals(ccs.activeCaltopoProfileId)) removedActive = true;
                continue;
            }
            survivors.add(profile);
        }
        if (!removed) return false;

        ccs.caltopoProfiles = survivors;
        if (removedActive) {
            String fallbackId = "";
            if (fallbackToHome) {
                for (CaltopoProfileRecord profile : survivors) {
                    if ("HOME".equals(profile.profileType)) {
                        fallbackId = profile.profileId;
                        break;
                    }
                }
            }
            if (fallbackId.isEmpty() && !survivors.isEmpty()) {
                fallbackId = survivors.get(0).profileId;
            }
            ccs.activeCaltopoProfileId = fallbackId;
            CaltopoProfileRecord fallback = fallbackId.isEmpty() ? null : GetCaltopoProfileById(fallbackId);
            if (fallback != null) {
                mirrorProfileIntoLegacyFields(ccs, fallback);
            }
        }
        ArchiveState("caltopo profile removed");
        if (removedActive && reconnect) {
            CaltopoMap.ResetMapConnection(0);
        }
        ScheduleCaltopoProfileExpiry();
        NotifySettingsChanged();
        return true;
    }

    private static void mirrorProfileIntoLegacyFields(
            @NonNull ClientClassState ccs,
            @NonNull CaltopoProfileRecord profile
    ) {
        ccs.caltopoCredentials = profile.credentials;
        ccs.caltopoDomainAndPort = profile.domainAndPort;
        ccs.caltopoTrackFolder = profile.trackFolder;
        ccs.caltopoConnectKey = profile.connectKey != null ? profile.connectKey : "";
        ccs.incident = profile.incident;
        ccs.opPeriod = profile.opPeriod;
        ccs.trackerApiKey = profile.trackerApiKey;
        ccs.trackerUrlPfx = profile.trackerUrlPfx;
    }

    public static boolean HasExpired(@Nullable CaltopoProfileRecord profile, long nowMs) {
        return profile != null &&
                profile.expiresAtEpochMs > 0L &&
                nowMs >= profile.expiresAtEpochMs;
    }

    private static boolean HasUsableTrackerCredentials(@Nullable CaltopoProfileRecord profile) {
        return profile != null &&
                profile.trackerApiKey != null &&
                !profile.trackerApiKey.isEmpty() &&
                profile.trackerUrlPfx != null &&
                !profile.trackerUrlPfx.isEmpty();
    }

    @Nullable
    public static String FindFallbackHomeProfileId() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles == null) return null;
        long nowMs = System.currentTimeMillis();
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (!"HOME".equals(profile.profileType)) continue;
            if (HasExpired(profile, nowMs)) continue;
            return profile.profileId;
        }
        return null;
    }

    @Nullable
    private static CaltopoProfileRecord GetPreferredCoordinationTrackerProfile() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        long nowMs = System.currentTimeMillis();
        String currentMapId = CaltopoMap.GetMapId();
        if (ccs.caltopoProfiles != null && !currentMapId.isEmpty()) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if (!"MUTUAL_AID".equals(profile.profileType)) continue;
                if (HasExpired(profile, nowMs)) continue;
                if (!currentMapId.equals(profile.targetMapId)) continue;
                if (!HasUsableTrackerCredentials(profile)) continue;
                return profile;
            }
        }
        CaltopoProfileRecord active = GetActiveCaltopoProfile();
        if (HasUsableTrackerCredentials(active)) {
            return active;
        }
        if (ccs.caltopoProfiles == null) return null;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (!"HOME".equals(profile.profileType)) continue;
            if (HasExpired(profile, nowMs)) continue;
            if (!HasUsableTrackerCredentials(profile)) continue;
            return profile;
        }
        return null;
    }

    @Nullable
    private static CaltopoProfileRecord GetPreferredUploadTrackerProfile() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        long nowMs = System.currentTimeMillis();
        String currentMapId = CaltopoMap.GetMapId();
        if (ccs.caltopoProfiles != null && !currentMapId.isEmpty()) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if (!"MUTUAL_AID".equals(profile.profileType)) continue;
                if (HasExpired(profile, nowMs)) continue;
                if (!currentMapId.equals(profile.targetMapId)) continue;
                if (!HasUsableTrackerCredentials(profile)) continue;
                return profile;
            }
        }
        CaltopoProfileRecord active = GetActiveCaltopoProfile();
        if (active != null &&
                "HOME".equals(active.profileType) &&
                HasUsableTrackerCredentials(active)) {
            return active;
        }
        if (ccs.caltopoProfiles == null) return null;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (!"HOME".equals(profile.profileType)) continue;
            if (HasExpired(profile, nowMs)) continue;
            if (!HasUsableTrackerCredentials(profile)) continue;
            return profile;
        }
        return null;
    }

    @NonNull
    private static String describeToken(@Nullable String token) {
        if (token == null) return "<null>";
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return "<empty>";
        if (trimmed.length() <= 4) {
            return String.format(Locale.US, "len=%d suffix=%s", trimmed.length(), trimmed);
        }
        return String.format(Locale.US, "len=%d suffix=%s",
                trimmed.length(), trimmed.substring(trimmed.length() - 4));
    }

    @NonNull
    public static String DescribeTrackerCredentialSelection() {
        return DescribeTrackerCredentialSelection("shared");
    }

    @NonNull
    public static String DescribeTrackerCredentialSelection(@NonNull String usage) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord active = GetActiveCaltopoProfile();
        boolean upload = "upload".equalsIgnoreCase(usage);
        CaltopoProfileRecord preferred = upload
                ? GetPreferredUploadTrackerProfile()
                : GetPreferredCoordinationTrackerProfile();
        String source = "state";
        if (preferred != null) {
            if ("MUTUAL_AID".equals(preferred.profileType) &&
                    !CaltopoMap.GetMapId().isEmpty() &&
                    CaltopoMap.GetMapId().equals(preferred.targetMapId)) {
                source = "map-matched-mutual-aid";
            } else if (active != null && preferred.profileId.equals(active.profileId)) {
                source = "active-profile";
            } else {
                source = "fallback-profile";
            }
        }
        String profileId = preferred != null ? preferred.profileId : "";
        String profileType = preferred != null ? preferred.profileType : "";
        String activeProfileId = active != null ? active.profileId : "";
        String activeProfileType = active != null ? active.profileType : "";
        String url = preferred != null && preferred.trackerUrlPfx != null && !preferred.trackerUrlPfx.isEmpty()
                ? preferred.trackerUrlPfx
                : ccs.trackerUrlPfx;
        String token = preferred != null && preferred.trackerApiKey != null && !preferred.trackerApiKey.isEmpty()
                ? preferred.trackerApiKey
                : ccs.trackerApiKey;
        return String.format(Locale.US,
                "usage=%s source=%s activeProfileId='%s' activeProfileType='%s' selectedProfileId='%s' selectedProfileType='%s' trackerUrl='%s' trackerToken=%s",
                usage,
                source,
                activeProfileId,
                activeProfileType,
                profileId,
                profileType,
                url,
                describeToken(token));
    }

    @NonNull
    public static String GetTrackerUploadApiKey() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetPreferredUploadTrackerProfile();
        if (profile != null && profile.trackerApiKey != null && !profile.trackerApiKey.isEmpty()) {
            return profile.trackerApiKey;
        }
        return "";
    }

    @NonNull
    public static String GetTrackerUploadUrlPfx() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetPreferredUploadTrackerProfile();
        if (profile != null && profile.trackerUrlPfx != null && !profile.trackerUrlPfx.isEmpty()) {
            return profile.trackerUrlPfx;
        }
        return "";
    }

    @NonNull
    public static String GetTrackerCoordinationApiKey() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetPreferredCoordinationTrackerProfile();
        if (profile != null && profile.trackerApiKey != null && !profile.trackerApiKey.isEmpty()) {
            return profile.trackerApiKey;
        }
        return ccs.trackerApiKey;
    }

    @NonNull
    public static String GetTrackerCoordinationUrlPfx() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetPreferredCoordinationTrackerProfile();
        if (profile != null && profile.trackerUrlPfx != null && !profile.trackerUrlPfx.isEmpty()) {
            return profile.trackerUrlPfx;
        }
        return ccs.trackerUrlPfx;
    }

    @NonNull
    public static String GetTrackerCoordinationScopeId() {
        String currentMapName = CaltopoMap.GetMapName();
        return currentMapName != null ? currentMapName : "";
    }

    public static int RemoveExpiredCaltopoProfiles(long nowMs, boolean disconnectIfActive) {
        ClientClassState ccs = GetState();
        if (ccs.caltopoProfiles == null || ccs.caltopoProfiles.isEmpty()) return 0;
        ArrayList<CaltopoProfileRecord> survivors = new ArrayList<>(ccs.caltopoProfiles.size());
        boolean activeExpired = false;
        int removed = 0;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (HasExpired(profile, nowMs)) {
                removed++;
                if (profile.profileId != null && profile.profileId.equals(ccs.activeCaltopoProfileId)) {
                    activeExpired = true;
                }
                continue;
            }
            survivors.add(profile);
        }
        if (removed == 0) return 0;

        ccs.caltopoProfiles = survivors;
        if (activeExpired || ccs.activeCaltopoProfileId == null || ccs.activeCaltopoProfileId.isEmpty()
                || !containsProfileId(survivors, ccs.activeCaltopoProfileId)) {
            String fallbackId = null;
            for (CaltopoProfileRecord profile : survivors) {
                if ("HOME".equals(profile.profileType)) {
                    fallbackId = profile.profileId;
                    break;
                }
            }
            if (fallbackId == null && !survivors.isEmpty()) {
                fallbackId = survivors.get(0).profileId;
            }
            ccs.activeCaltopoProfileId = fallbackId != null ? fallbackId : "";
            CaltopoProfileRecord activeProfile = null;
            if (fallbackId != null) {
                for (CaltopoProfileRecord profile : survivors) {
                    if (fallbackId.equals(profile.profileId)) {
                        activeProfile = profile;
                        break;
                    }
                }
            }
            if (activeProfile != null) {
                mirrorProfileIntoLegacyFields(ccs, activeProfile);
            } else {
                ccs.caltopoCredentials = new CaltopoCredentials();
                ccs.caltopoDomainAndPort = "caltopo.com";
            }
        }

        if (disconnectIfActive && activeExpired) {
            try {
                CaltopoMap.ResetMapConnection(0);
            } catch (Exception e) {
                CTWarn(TAG, "RemoveExpiredCaltopoProfiles(): map reset raised", e);
            }
        }
        ArchiveState("expired caltopo profiles removed");
        ScheduleCaltopoProfileExpiry();
        NotifySettingsChanged();
        return removed;
    }

    private static boolean containsProfileId(@NonNull List<CaltopoProfileRecord> profiles, @Nullable String profileId) {
        if (profileId == null || profileId.isEmpty()) return false;
        for (CaltopoProfileRecord profile : profiles) {
            if (profileId.equals(profile.profileId)) return true;
        }
        return false;
    }

    private static void ensureProfileStateFresh(@NonNull ClientClassState ccs, boolean disconnectIfActive) {
        if (ccs.caltopoProfiles == null || ccs.caltopoProfiles.isEmpty()) return;
        RemoveExpiredCaltopoProfiles(System.currentTimeMillis(), disconnectIfActive);
        ScheduleCaltopoProfileExpiry();
    }

    /**
     * Schedule the next operational-profile cutoff against its absolute expiry.
     * Lifecycle polling remains a safety net, but an active MA profile no longer
     * retains CalTopo or tracker coordination until the next poll.
     */
    public static void ScheduleCaltopoProfileExpiry() {
        MainHandler.removeCallbacks(CaltopoProfileExpiryRunnable);
        ClientClassState ccs = Ccstate;
        if (ccs == null || ccs.caltopoProfiles == null) return;
        long nowMs = System.currentTimeMillis();
        long nextExpiryMs = Long.MAX_VALUE;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if (profile == null || profile.expiresAtEpochMs <= 0L) continue;
            nextExpiryMs = Math.min(nextExpiryMs, profile.expiresAtEpochMs);
        }
        if (nextExpiryMs == Long.MAX_VALUE) return;
        MainHandler.postDelayed(
                CaltopoProfileExpiryRunnable,
                Math.max(1L, nextExpiryMs - nowMs)
        );
    }

    public static void SetUsePeers(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.usePeersFlag != flag) {
            ccs.usePeersFlag = flag;
            NotifySettingsChanged();
            ArchiveState("usePeers changed to " + flag);
        }
    }

    public static boolean GetUsePeersFlag() {
        ClientClassState ccs = GetState();
        return ccs.usePeersFlag;
    }

    public static void SetStandaloneR2cCoordinationEnabled(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.standaloneR2cCoordinationEnabled != flag) {
            ccs.standaloneR2cCoordinationEnabled = flag;
            if (!flag) {
                CaltopoMap.StopStandaloneTrackerCoordinationIfActive();
            }
            NotifySettingsChanged();
            ArchiveState("standalone r2c coordination changed to " + flag);
        }
    }

    public static boolean GetStandaloneR2cCoordinationEnabled() {
        ClientClassState ccs = GetState();
        return ccs.standaloneR2cCoordinationEnabled;
    }

    public static void SetCaptureVideoStreamsFlag(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.captureVideoStreamsFlag != flag) {
            ccs.captureVideoStreamsFlag = flag;
            NotifySettingsChanged();
            ArchiveState("captureVideoStreams changed to " + flag);
        }
    }

    public static boolean GetCaptureVideoStreamsFlag() {
        ClientClassState ccs = GetState();
        return ccs.captureVideoStreamsFlag;
    }

    public static void SetCaltopoCredentials(@NonNull CaltopoCredentials cred)
            throws RuntimeException {
        SetCaltopoCredentialsWithOrigin(cred, CALTOPO_CREDENTIAL_ORIGIN_INDEPENDENT);
    }

    public static void SetTrackerManagedCaltopoCredentials(@NonNull CaltopoCredentials cred)
            throws RuntimeException {
        SetCaltopoCredentialsWithOrigin(cred, CALTOPO_CREDENTIAL_ORIGIN_TRACKER);
    }

    private static void SetCaltopoCredentialsWithOrigin(
            @NonNull CaltopoCredentials cred,
            @NonNull String origin) throws RuntimeException {
        if (!CaltopoCredentials.sniffTest(cred)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = GetState();
        boolean credentialsChanged = !CaltopoCredentials.credentialsAreEqual(
                cred, ccs.caltopoCredentials);
        boolean originChanged = !origin.equals(ccs.caltopoCredentialOrigin);
        if (credentialsChanged || originChanged) {
            ccs.caltopoCredentials = cred;
            ccs.caltopoCredentialOrigin = origin;
            NotifySettingsChanged();
            ArchiveState("caltopo credentials changed; origin=" + origin);
        }
    }

    @NonNull
    public static String GetCaltopoCredentialOrigin() {
        String origin = GetState().caltopoCredentialOrigin;
        return origin == null || origin.isEmpty()
                ? CALTOPO_CREDENTIAL_ORIGIN_UNKNOWN : origin;
    }

    /**
     * Disable only CalTopo credentials that came from Tracker. Legacy credentials with
     * unknown provenance are cleared conservatively; explicitly independent credentials
     * remain available for offline and degraded-network operation.
     */
    public static synchronized boolean QuarantineTrackerManagedCaltopoCredentials() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (CALTOPO_CREDENTIAL_ORIGIN_INDEPENDENT.equals(GetCaltopoCredentialOrigin())) {
            ArchiveState("tracker reauthentication preserved independent caltopo credentials");
            return false;
        }
        ccs.caltopoCredentials = new CaltopoCredentials();
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if ("HOME".equals(profile.profileType)) {
                    profile.credentials = new CaltopoCredentials();
                }
            }
        }
        ccs.mutualAidTemplate = new MutualAidTemplateRecord();
        ccs.caltopoCredentialOrigin = CALTOPO_CREDENTIAL_ORIGIN_TRACKER;
        try {
            CaltopoMap.ResetMapConnection(0);
        } catch (Exception error) {
            CTWarn(TAG, "QuarantineTrackerManagedCaltopoCredentials(): map reset raised", error);
        }
        NotifySettingsChanged();
        ArchiveState("tracker-managed caltopo credentials cleared pending reauthentication");
        return true;
    }

    public static void SetCaltopoDomainAndPort(@NonNull String dAndP) {
        ClientClassState ccs = GetState();
        if (null != ccs.caltopoDomainAndPort) {
            if (dAndP.equals(ccs.caltopoDomainAndPort)) {
                CTDebug(TAG, "SetCaltopoDomainAndPort(): No change.");
                return;
            }
            // FIXME: Should probably check valid format...
        }
        ccs.caltopoDomainAndPort = dAndP;
        NotifySettingsChanged();
        ArchiveState("caltopo domain changed");
    }

    @NonNull
    public static MutualAidTemplateRecord GetMutualAidTemplate() {
        ClientClassState ccs = GetState();
        if (ccs.mutualAidTemplate == null) {
            ccs.mutualAidTemplate = new MutualAidTemplateRecord();
        }
        return ccs.mutualAidTemplate;
    }

    public static boolean HasMutualAidTemplate() {
        MutualAidTemplateRecord template = GetMutualAidTemplate();
        return CaltopoCredentials.sniffTest(
                new CaltopoCredentials(template.teamId, template.credentialId, template.credentialSecret)
        );
    }

    @NonNull
    public static String GetMutualAidTemplateTeamId() {
        return GetMutualAidTemplate().teamId;
    }

    @NonNull
    public static String GetMutualAidTemplateCredentialId() {
        return GetMutualAidTemplate().credentialId;
    }

    @NonNull
    public static String GetMutualAidTemplateCredentialSecret() {
        return GetMutualAidTemplate().credentialSecret;
    }

    @NonNull
    public static String GetMutualAidTemplateDomainAndPort() {
        return GetMutualAidTemplate().domainAndPort;
    }

    @NonNull
    public static String GetMutualAidTemplateSourceLabel() {
        return GetMutualAidTemplate().sourceLabel;
    }

    @NonNull
    public static String GetMutualAidTemplateTargetFolderHint() {
        return GetMutualAidTemplate().targetFolderHint;
    }

    @NonNull
    public static String GetMutualAidTemplateConnectKey() {
        String value = GetMutualAidTemplate().connectKey;
        return value != null ? value : "";
    }

    @NonNull
    public static String GetHomeOrgName() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if ("HOME".equals(profile.profileType) && profile.sourceLabel != null && !profile.sourceLabel.isEmpty()) {
                    return profile.sourceLabel;
                }
            }
        }
        CaltopoProfileRecord active = GetActiveCaltopoProfile();
        if (active != null && "HOME".equals(active.profileType) && active.sourceLabel != null) {
            return active.sourceLabel;
        }
        return "";
    }

    @NonNull
    public static String GetTrackerUploadOrgName() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetPreferredUploadTrackerProfile();
        if (profile != null && profile.sourceLabel != null && !profile.sourceLabel.isEmpty()) {
            return profile.sourceLabel;
        }
        String mutualAidSourceLabel = GetMutualAidSourceLabel();
        if (!mutualAidSourceLabel.isEmpty()) {
            return mutualAidSourceLabel;
        }
        return GetHomeOrgName();
    }

    public static void SetHomeOrgName(@NonNull String orgName) {
        ClientClassState ccs = GetState();
        String trimmed = orgName.trim();
        if (ccs.caltopoProfiles == null) {
            ccs.caltopoProfiles = new ArrayList<>();
        }
        CaltopoProfileRecord target = null;
        for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
            if ("HOME".equals(profile.profileType)) {
                target = profile;
                break;
            }
        }
        if (target == null) {
            target = new CaltopoProfileRecord(
                    "home-default",
                    "Default",
                    "HOME",
                    ccs.caltopoCredentials != null ? ccs.caltopoCredentials : new CaltopoCredentials(),
                    ccs.caltopoDomainAndPort != null ? ccs.caltopoDomainAndPort : "caltopo.com",
                    ccs.caltopoTrackFolder != null ? ccs.caltopoTrackFolder : "Drone Tracks",
                    ccs.incident != null ? ccs.incident : "Training",
                    ccs.opPeriod != null ? ccs.opPeriod : "1",
                    ccs.trackerApiKey != null ? ccs.trackerApiKey : "",
                    ccs.trackerUrlPfx != null ? ccs.trackerUrlPfx : "",
                    false,
                    0L,
                    false,
                    trimmed,
                    "",
                    "",
                    "",
                    0L,
                    ""
            );
            ccs.caltopoProfiles.add(target);
        }
        if (trimmed.equals(target.sourceLabel)) return;
        target.sourceLabel = trimmed;
        CaltopoProfileRecord active = GetActiveCaltopoProfile();
        if (active != null && "HOME".equals(active.profileType) && active.profileId.equals(target.profileId)) {
            mirrorProfileIntoLegacyFields(ccs, target);
        }
        NotifySettingsChanged();
        ArchiveState("home org name changed");
    }

    public static void SetHomeTrackerCredentials(
            @NonNull String trackerUrl,
            @NonNull String trackerApiKey
    ) {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord home = null;
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if ("HOME".equals(profile.profileType)) {
                    home = profile;
                    break;
                }
            }
        }
        if (home == null) {
            SetHomeOrgName("HOME");
            if (ccs.caltopoProfiles != null) {
                for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                    if ("HOME".equals(profile.profileType)) {
                        home = profile;
                        break;
                    }
                }
            }
        }
        if (home != null) {
            home.trackerUrlPfx = trackerUrl.trim();
            home.trackerApiKey = trackerApiKey.trim();
            if (home.profileId.equals(ccs.activeCaltopoProfileId)) {
                mirrorProfileIntoLegacyFields(ccs, home);
            }
        }
        ccs.trackerUrlPfx = trackerUrl.trim();
        ccs.trackerApiKey = trackerApiKey.trim();
        NotifySettingsChanged();
        ArchiveState("home tracker enrollment changed");
    }

    /** Clear organization-managed secrets when Tracker requires user reauthentication. */
    public static synchronized void RequireManagedReauthentication() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        ccs.trackerApiKey = "";
        ccs.trackerUrlPfx = "";
        ccs.trackerFaaProxyUrl = "";
        ccs.caltopoCredentials = new CaltopoCredentials();
        ccs.cachedDroneSpecTable = new Hashtable<>();
        CurrentPeerConfirmedDroneRemoteIds.clear();
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                profile.credentials = new CaltopoCredentials();
                profile.trackerApiKey = "";
                profile.trackerUrlPfx = "";
            }
        }
        UpdateDroneSpecs();
        SetUsePeers(false);
        SetStandaloneR2cCoordinationEnabled(false);
        try {
            CaltopoMap.ResetMapConnection(0);
        } catch (Exception error) {
            CTWarn(TAG, "RequireManagedReauthentication(): map reset raised", error);
        }
        NotifySettingsChanged();
        ArchiveState("managed credentials cleared pending reauthentication");
    }

    @NonNull
    public static String GetHomeTrackerApiKey() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if ("HOME".equals(profile.profileType) && profile.trackerApiKey != null) {
                    return profile.trackerApiKey;
                }
            }
        }
        return "";
    }

    @NonNull
    public static String GetHomeTrackerUrlPfx() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        if (ccs.caltopoProfiles != null) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if ("HOME".equals(profile.profileType) && profile.trackerUrlPfx != null) {
                    return profile.trackerUrlPfx;
                }
            }
        }
        return "";
    }

    @NonNull
    public static String GetTrackerFaaProxyUrl() {
        return GetState().trackerFaaProxyUrl;
    }

    public static void SetTrackerFaaProxyUrl(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.trackerFaaProxyUrl.equals(value)) {
            ccs.trackerFaaProxyUrl = value;
            NotifySettingsChanged();
            ArchiveState("tracker FAA proxy URL changed");
        }
    }

    @NonNull
    public static String GetTrackerEnrollmentUrl() {
        return GetState().trackerEnrollmentUrl;
    }

    public static void SetTrackerEnrollmentUrl(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.trackerEnrollmentUrl.equals(value)) {
            ccs.trackerEnrollmentUrl = value;
            NotifySettingsChanged();
            ArchiveState("tracker enrollment locator changed");
        }
    }

    @NonNull
    public static String GetMutualAidSourceLabel() {
        MutualAidTemplateRecord template = GetMutualAidTemplate();
        return template.sourceLabel != null ? template.sourceLabel : "";
    }

    public static void SetMutualAidTemplate(@NonNull MutualAidTemplateRecord template) {
        ClientClassState ccs = GetState();
        ccs.mutualAidTemplate = template;
        NotifySettingsChanged();
        ArchiveState("mutual aid template changed");
    }

    public static void SetMutualAidTemplateFields(
            @NonNull String teamId,
            @NonNull String credentialId,
            @NonNull String credentialSecret,
            @NonNull String domainAndPort,
            @NonNull String sourceLabel,
            @NonNull String targetFolderHint
    ) {
        SetMutualAidTemplateFields(
                teamId, credentialId, credentialSecret, domainAndPort,
                sourceLabel, targetFolderHint, ""
        );
    }

    public static void SetMutualAidTemplateFields(
            @NonNull String teamId,
            @NonNull String credentialId,
            @NonNull String credentialSecret,
            @NonNull String domainAndPort,
            @NonNull String sourceLabel,
            @NonNull String targetFolderHint,
            @NonNull String connectKey
    ) {
        MutualAidTemplateRecord template = new MutualAidTemplateRecord(
                teamId,
                credentialId,
                credentialSecret,
                domainAndPort,
                sourceLabel,
                targetFolderHint
        );
        template.connectKey = connectKey.trim();
        SetMutualAidTemplate(template);
    }

    public static String GetCaltopoDomainAndPort() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null && profile.domainAndPort != null && !profile.domainAndPort.isEmpty()) {
            return profile.domainAndPort;
        }
        if (null == ccs.caltopoDomainAndPort)
            ccs.caltopoDomainAndPort = ""; // shouldn't happen, but play it safe.
        return ccs.caltopoDomainAndPort;
    }

    public static String ReadTextFile(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufferedReader;

        try {
            Context ctxt = R2CApplication.getAppCtxt();
            ContentResolver resolver  = ctxt.getContentResolver();
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                // Try reading file metadata to coerce Google Drive to fetch fresh version of
                // the file, rather than pulling from it's cache.
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    CTDebug(TAG, String.format(Locale.US, "ReadJsonFile(%s) size:%d bytes...",
                            cursor.getString(nameIndex), cursor.getLong(sizeIndex)));
                }
            }
            is = resolver.openInputStream(uri);
            isr = new InputStreamReader(is, StandardCharsets.UTF_8);
            bufferedReader = new BufferedReader(isr);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            bufferedReader.close();
            isr.close();
            if (null != is) is.close();
        } catch (IOException e) {
            CTError(TAG, String.format(Locale.US, "Not able to read '%s'", uri), e);
            return null;
        }
        return stringBuilder.toString();
    }

    public static JSONObject ReadJsonFile(Uri uri) {
        String content = ReadTextFile(uri);
        if (null == content) return null;
        try {
            return new JSONObject(content);
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US, "Not able to parse '%s'", uri), e);
            return null;
        }
    }

    public static void ShowToast(String msg) {
        CTWarn(TAG, "showToast():" + msg);
        R2CActivity activity = R2CActivity.getR2CActivity();
        if (null != activity) activity.showToast(msg);
    }

    public static void ShowToast(String msg, Exception e) {
        CTWarn(TAG, "showToast():" + msg, e);
        msg = msg + "\n" + ExceptionToString(e);
        R2CActivity activity = R2CActivity.getR2CActivity();
        if (null != activity) activity.showToast(msg);
    }

    public static void readCredentialsFileContent(JSONObject json)
            throws JSONException {
        String orgName = json.optString("org_name", json.optString("source_label"));
        String teamId = json.optString("team_id");
        String credentialId = json.optString("credential_id");
        String credentialSecret = json.optString("credential_secret");
        String domainAndPort = json.optString("domain_and_port");
        String connectKey = json.optString("connect_key");
        String trackFolder = json.optString("track_folder");
        String incident = json.optString("incident");
        String opPeriod = json.optString("op_period");
        String trackerApiKey = json.optString("tracker_api_key");
        String trackerUrlPfx = TrackerConfigCompat.readTrackerUrlPrefix(json);
        String trackerFaaProxyUrl = json.optString("tracker_faa_proxy_url");
        boolean hasUsePeers = json.has("use_peers");
        boolean usePeers = json.optBoolean("use_peers", true);
        boolean predictiveHeadEnabled = json.optBoolean("predictive_head_enabled", true);
        long proximityAlertSpacingFeet = json.optLong("proximity_alert_spacing_feet", 40L);
        boolean notamEnabled = json.optBoolean("notam_enabled", true);
        int notamRadiusNm = json.optInt(
                "notam_radius_statute_miles",
                json.optInt("notam_radius_nm", 1)
        );
        boolean notamAutoRefresh = json.optBoolean("notam_auto_refresh", true);
        int notamRefreshIntervalSeconds = json.optInt("notam_refresh_interval_seconds", 1800);
        boolean notamWarnInsideOneNm = json.optBoolean("notam_warn_inside_one_nm", true);
        String notamApiBaseUrl = json.optString("notam_api_base_url");
        String notamTokenUrl = json.optString("notam_token_url");
        String notamClientId = json.optString("notam_client_id");
        String notamClientSecret = json.optString("notam_client_secret");
        String notamScope = json.optString("notam_scope");
        if (!trackFolder.isEmpty()) SetTrackFolderName(trackFolder);
        if (!incident.isEmpty()) SetIncident(incident);
        if (!opPeriod.isEmpty()) SetOpPeriod(opPeriod);
        if (!trackerApiKey.isEmpty()) SetTrackerApiKey(trackerApiKey);
        if (!trackerUrlPfx.isEmpty()) SetTrackerUrlPfx(trackerUrlPfx);
        if (!trackerFaaProxyUrl.isEmpty()) SetTrackerFaaProxyUrl(trackerFaaProxyUrl);
        if (!domainAndPort.isEmpty()) SetCaltopoDomainAndPort(domainAndPort);
        if (!connectKey.isEmpty()) SetConnectKey(connectKey);
        if (hasUsePeers) SetUsePeers(usePeers);
        SetPredictiveHeadEnabled(predictiveHeadEnabled);
        SetProximityAlertSpacingFeet(proximityAlertSpacingFeet);
        SetNotamEnabled(notamEnabled);
        SetNotamRadiusNm(notamRadiusNm);
        SetNotamAutoRefresh(notamAutoRefresh);
        SetNotamRefreshIntervalSeconds(notamRefreshIntervalSeconds);
        SetNotamWarnInsideOneNm(notamWarnInsideOneNm);
        if (!notamApiBaseUrl.isEmpty()) SetNotamApiBaseUrl(notamApiBaseUrl);
        if (!notamTokenUrl.isEmpty()) SetNotamTokenUrl(notamTokenUrl);
        if (!notamClientId.isEmpty() && !notamClientSecret.isEmpty()) {
            FaaConfigManager.importLegacyCredentialsFromJson(json);
        } else {
            if (!notamClientId.isEmpty()) SetNotamClientId(notamClientId);
            if (!notamClientSecret.isEmpty()) SetNotamClientSecret(notamClientSecret);
            if (!notamScope.isEmpty()) SetNotamScope(notamScope);
        }
        if (!orgName.isEmpty()) SetHomeOrgName(orgName);
        if (!teamId.isEmpty() || !credentialId.isEmpty() || !credentialSecret.isEmpty()) {
            SetCaltopoCredentials(new CaltopoCredentials(teamId, credentialId, credentialSecret));
        }
        NotifySettingsChanged();
        ArchiveState("Credentials loaded");
    }

    public static void readMutualAidCredentialsFileContent(JSONObject json)
            throws JSONException {
        String teamId = json.optString("team_id");
        String credentialId = json.optString("credential_id");
        String credentialSecret = json.optString("credential_secret");
        String domainAndPort = json.optString("domain_and_port", GetCaltopoDomainAndPort());
        String sourceLabel = json.optString("source_label", json.optString("org_name"));
        String targetFolderHint = json.optString("target_folder_hint", "MAI");
        String connectKey = json.optString("connect_key");
        MutualAidTemplateRecord template = new MutualAidTemplateRecord(
                teamId,
                credentialId,
                credentialSecret,
                domainAndPort,
                sourceLabel,
                targetFolderHint
        );
        template.connectKey = connectKey;
        if (!CaltopoCredentials.sniffTest(
                new CaltopoCredentials(template.teamId, template.credentialId, template.credentialSecret)
        )) {
            throw new RuntimeException("Mutual aid credentials file is missing required fields.");
        }
        SetMutualAidTemplate(template);
        NotifySettingsChanged();
        ArchiveState("Mutual aid credentials loaded");
    }

    // readRidmapFileContent():
    // New for v1.0.7rc1: This is now the only way to _add_ dronespecs into the
    // persistent ClientClassState.
    public static void readRidmapFileContent(JSONObject json) throws JSONException {
        JSONArray mapJson;
        int changeCount = 0;
        boolean replaceFlag = false;
        try {
            mapJson = json.optJSONArray("map");
            if (!json.optString("load_type").equals("merge")) {
                replaceFlag = true;
            }
            CTDebug(TAG, "readRidmapFileContent(): setting load_type to " +
                    (replaceFlag ? "replace" : "merge"));
        } catch (NullPointerException e) {
            mapJson = null;
        }
        if (null == mapJson) {
            ShowToast("No map specified in file.");
            return;
        }
        ClientClassState ccs = GetState();
        Hashtable<String, CtDroneSpec> mergedTable = new Hashtable<>(16);
        CtDroneSpec newDs;
        for (int i = 0; i < mapJson.length(); i++) {
            JSONObject entry = mapJson.getJSONObject(i);
            String rid = entry.optString("remoteId");
            if (RejectNonCanonicalRemoteId("readRidmapFileContent()", rid)) {
                throw new JSONException(String.format(Locale.US,
                        "Illegal non-canonical remoteId '%s' at table offset %d - file contents ignored.", rid, i));
            }
            String mid = entry.optString("mappedId");
            String org = entry.optString("org");
            String model = entry.optString("model");
            String owner = entry.optString("owner");
            RidMappingOwnerFields ownerFields = RidMappingRules.INSTANCE.resolveOwnerFields(
                    entry.optString("ownerName"),
                    entry.optString("ownerCallsign"),
                    owner,
                    mid,
                    model,
                    rid);
            newDs = new CtDroneSpec(
                    rid,
                    mid,
                    org,
                    model,
                    ownerFields.getOwnerName(),
                    ownerFields.getOwnerCallsign());
            CtDroneSpec existingDs = ccs.cachedDroneSpecTable.get(rid);
            boolean changed = (null == existingDs);
            if (!changed) {
                CTDebug(TAG, "readRidmapFileContent(): Found existing droneSpec for spec: " + existingDs);
                if (existingDs.isDifferentFrom(newDs)) {
                    existingDs.setModel(newDs.getModel());
                    existingDs.setMappedId(newDs.getMappedId());
                    existingDs.setOrg(newDs.getOrg());
                    existingDs.setOwner(newDs.getOwner());
                    existingDs.setOwnerName(newDs.getOwnerName());
                    CTDebug(TAG, String.format(Locale.US,
                            "readRidmapFileContent(): changed persistent dronespec from:\n    %s\n  to:\n    %s",
                            existingDs, newDs));
                    newDs = existingDs;
                    changed = true;
                } else {
                    CTInfo(TAG, "readRidmapFileContent(): no changes detected for spec: " + existingDs);
                }
            }
            existingDs = mergedTable.get(rid);
            if (null != existingDs) {
                throw new JSONException(String.format(Locale.US,
                        "Illegal duplicate remoteId '%s' at table offset %d - file contents ignored.", rid, i));
            }
            if (changed) changeCount++;
            mergedTable.put(newDs.getRemoteId(), newDs);
        }

        if (0 != changeCount) {
            // Be sure to include any existing mappings that weren't mentioned in the file:
            for (Map.Entry<String, CtDroneSpec> map : ccs.cachedDroneSpecTable.entrySet()) {
                String key = map.getKey();
                if (null == mergedTable.get(key)) {
                    mergedTable.put(key, map.getValue());
                }
            }
            ccs.cachedDroneSpecTable = mergedTable;
            ArchiveState("readRidmapFileContent() merged ridmap with updates/changes.");
        } else {
            CTDebug(TAG, "readRidmapFileContent(): No changes detected.");
        }
    }

    public static boolean LoadConfigFile(Uri uri) {
        if (null == uri) return false;
        try {
            ClientClassState ccs = GetState();

            String content = ReadTextFile(uri);
            if (null == content) return false;
            Context ctxt = R2CApplication.getAppCtxt();
            if (FaaConfigManager.tryHandleRawConfigText(ctxt, content)) {
                if (ctxt != null) {
                    ccs.configFilesLoaded = AppConfigStore.recordLoadedConfigFile(ctxt, "ct_faa_remote_config", "config_file", "");
                }
                NotifySettingsChanged();
                ShowToast("FAA config token accepted.");
                return true;
            }
            JSONObject json = new JSONObject(content);
            String type = json.optString("type").trim().toLowerCase();
            String fileVersion = json.optString("file_version");
            String updated = json.optString("updated");
            String editor = json.optString("editor");
            CTDebug(TAG, String.format(Locale.US, "Reading v%s %s config file last updated by %s on %s",
                    fileVersion, type, editor, updated));

            if (ctxt != null) {
                ccs.configFilesLoaded = AppConfigStore.recordLoadedConfigFile(ctxt, type, editor, updated);
            }

            if (type.equals("ct_ridmap")) {
                readRidmapFileContent(json);
            } else if (type.equals("ct_credentials")) {
                readCredentialsFileContent(json);
            } else if (type.equals("ct_mutual_aid_credentials")) {
                readMutualAidCredentialsFileContent(json);
            } else if (FaaConfigManager.readConfigObject(json)) {
                // handled by FAA config manager
            }
            NotifySettingsChanged();
            ShowToast(String.format(Locale.US, "%s:%s successfully loaded.", type, fileVersion));
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US, "Error processing '%s':", uri), e);
            return false;
        }
        return true;
    }

    /**
     * Serialize the current app state (ridmap + credentials) into an org-config
     * bundle JSON string suitable for uploading to Drive and sharing via QR token.
     *
     * The bundle contains two child config objects in the same format as the
     * existing ct_ridmap and ct_credentials files so they can be applied using
     * the same parsing paths.  The caller (OrgConfigManager) is responsible for
     * encrypting the credentials block before upload.
     *
     * @param orgName display name for the SAR organisation (included in the bundle).
     * @return pretty-printed JSON string, or null on error.
     */
    public static String BuildOrgConfigBundle(String orgName) {
        try {
            ClientClassState ccs = GetState();
            JSONObject bundle = new JSONObject();
            bundle.put("format", "rid2caltopo_org_config");
            bundle.put("version", 2);
            bundle.put("org_name", orgName != null ? orgName : "");
            bundle.put("generated", TimeDatestampString(System.currentTimeMillis()));

            JSONArray configs = new JSONArray();

            // ── ct_ridmap ──────────────────────────────────────────────────────
            JSONObject ridmap = new JSONObject();
            ridmap.put("type", "ct_ridmap");
            ridmap.put("file_version", "1.0");
            ridmap.put("load_type", "replace");
            JSONArray mapArray = new JSONArray();
            for (Map.Entry<String, CtDroneSpec> entry : ccs.cachedDroneSpecTable.entrySet()) {
                CtDroneSpec spec = entry.getValue();
                JSONObject mapEntry = new JSONObject();
                mapEntry.put("remoteId", spec.getRemoteId());
                mapEntry.put("mappedId", spec.getMappedId());
                mapEntry.put("org",      spec.getOrg());
                mapEntry.put("model",    spec.getModel());
                mapEntry.put("owner",    spec.getOwner());
                mapEntry.put("ownerName", spec.getOwnerName());
                mapEntry.put("ownerCallsign", spec.getOwner());
                mapArray.put(mapEntry);
            }
            ridmap.put("map", mapArray);
            configs.put(ridmap);

            // ── ct_credentials ────────────────────────────────────────────────
            JSONObject credentials = new JSONObject();
            credentials.put("type", "ct_credentials");
            credentials.put("file_version", "1.0");
            if (orgName != null && !orgName.isEmpty())
                credentials.put("org_name", orgName);
            CaltopoCredentials cred = ccs.caltopoCredentials;
            if (cred != null) {
                if (cred.teamId != null && !cred.teamId.isEmpty())
                    credentials.put("team_id", cred.teamId);
                if (cred.credentialId != null && !cred.credentialId.isEmpty())
                    credentials.put("credential_id", cred.credentialId);
                if (cred.credentialSecret != null && !cred.credentialSecret.isEmpty())
                    credentials.put("credential_secret", cred.credentialSecret);
            }
            if (ccs.caltopoDomainAndPort != null && !ccs.caltopoDomainAndPort.isEmpty())
                credentials.put("domain_and_port", ccs.caltopoDomainAndPort);
            if (ccs.caltopoConnectKey != null && !ccs.caltopoConnectKey.isEmpty())
                credentials.put("connect_key", ccs.caltopoConnectKey);
            if (ccs.caltopoTrackFolder != null && !ccs.caltopoTrackFolder.isEmpty())
                credentials.put("track_folder", ccs.caltopoTrackFolder);
            if (ccs.incident != null && !ccs.incident.isEmpty())
                credentials.put("incident", ccs.incident);
            if (ccs.opPeriod != null && !ccs.opPeriod.isEmpty())
                credentials.put("op_period", ccs.opPeriod);
            // R2C2 distributes the signed campaign locator, never the credential
            // issued to the tablet creating this bundle. Each importing device
            // redeems the locator for its own revocable r2c_dev_ credential.
            if (ccs.trackerEnrollmentUrl != null && !ccs.trackerEnrollmentUrl.isEmpty())
                credentials.put("tracker_enrollment_url", ccs.trackerEnrollmentUrl);
            credentials.put("use_peers", ccs.usePeersFlag);
            credentials.put("predictive_head_enabled",           ccs.predictiveHeadEnabled);
            credentials.put("proximity_alert_spacing_feet",      ccs.proximityAlertSpacingFeet);
            // ── NOTAM settings ───────────────────────────────────────────────
            credentials.put("notam_enabled",                   ccs.notamEnabled);
            credentials.put("notam_radius_statute_miles",      ccs.notamRadiusNm);
            credentials.put("notam_auto_refresh",              ccs.notamAutoRefresh);
            credentials.put("notam_refresh_interval_seconds",  ccs.notamRefreshIntervalSeconds);
            credentials.put("notam_warn_inside_one_nm",        ccs.notamWarnInsideOneNm);
            configs.put(credentials);

            // ── ct_mutual_aid_credentials ───────────────────────────────────
            MutualAidTemplateRecord template = ccs.mutualAidTemplate;
            if (template != null &&
                    ((template.teamId != null && !template.teamId.isEmpty()) ||
                     (template.credentialId != null && !template.credentialId.isEmpty()) ||
                     (template.credentialSecret != null && !template.credentialSecret.isEmpty()) ||
                     (template.connectKey != null && !template.connectKey.isEmpty()) ||
                     (template.sourceLabel != null && !template.sourceLabel.isEmpty()) ||
                     (template.targetFolderHint != null && !template.targetFolderHint.isEmpty()))) {
                JSONObject mutualAidCredentials = new JSONObject();
                mutualAidCredentials.put("type", "ct_mutual_aid_credentials");
                mutualAidCredentials.put("file_version", "1.0");
                if (template.teamId != null && !template.teamId.isEmpty())
                    mutualAidCredentials.put("team_id", template.teamId);
                if (template.credentialId != null && !template.credentialId.isEmpty())
                    mutualAidCredentials.put("credential_id", template.credentialId);
                if (template.credentialSecret != null && !template.credentialSecret.isEmpty())
                    mutualAidCredentials.put("credential_secret", template.credentialSecret);
                if (template.domainAndPort != null && !template.domainAndPort.isEmpty())
                    mutualAidCredentials.put("domain_and_port", template.domainAndPort);
                if (template.connectKey != null && !template.connectKey.isEmpty())
                    mutualAidCredentials.put("connect_key", template.connectKey);
                if (template.sourceLabel != null && !template.sourceLabel.isEmpty())
                    mutualAidCredentials.put("source_label", template.sourceLabel);
                if (template.targetFolderHint != null && !template.targetFolderHint.isEmpty())
                    mutualAidCredentials.put("target_folder_hint", template.targetFolderHint);
                configs.put(mutualAidCredentials);
            }

            bundle.put("configs", configs);
            return bundle.toString(2);

        } catch (JSONException e) {
            CTError(TAG, "BuildOrgConfigBundle() failed.", e);
            return null;
        }
    }

    /**
     * Parse an org-config bundle JSON string (produced by {@link #BuildOrgConfigBundle},
     * with credentials already decrypted by OrgConfigManager) and apply its child
     * configs using the existing ct_ridmap and ct_credentials parsing paths.
     *
     * @param json the bundle JSON string (credentials already in plaintext).
     * @return true if at least one child config was applied without error.
     */
    public static boolean ApplyOrgConfigBundle(String json) {
        if (null == json || json.isEmpty()) return false;
        try {
            JSONObject bundle = new JSONObject(json);
            String format = bundle.optString("format");
            if (!format.equals("rid2caltopo_org_config") || bundle.optInt("version", 0) != 2) {
                CTWarn(TAG, "ApplyOrgConfigBundle(): unexpected format: " + format);
                return false;
            }
            JSONArray configs = bundle.optJSONArray("configs");
            if (null == configs || configs.length() == 0) {
                CTWarn(TAG, "ApplyOrgConfigBundle(): configs array is empty.");
                return false;
            }
            Context ctxt = R2CApplication.getAppCtxt();
            int applied = 0;
            for (int i = 0; i < configs.length(); i++) {
                JSONObject config = configs.getJSONObject(i);
                String type    = config.optString("type").trim().toLowerCase();
                String editor  = config.optString("editor", "org_config");
                String updated = config.optString("updated", "");
                if (ctxt != null) {
                    GetState().configFilesLoaded =
                        AppConfigStore.recordLoadedConfigFile(ctxt, type, editor, updated);
                }
                if (type.equals("ct_ridmap")) {
                    readRidmapFileContent(config);
                    applied++;
                } else if (type.equals("ct_credentials")) {
                    readCredentialsFileContent(config);
                    applied++;
                } else if (type.equals("ct_mutual_aid_credentials")) {
                    readMutualAidCredentialsFileContent(config);
                    applied++;
                } else if (FaaConfigManager.readConfigObject(config)) {
                    applied++;
                } else {
                    CTWarn(TAG, "ApplyOrgConfigBundle(): unknown config type ignored: " + type);
                }
            }
            if (applied > 0) NotifySettingsChanged();
            return applied > 0;
        } catch (JSONException e) {
            CTError(TAG, "ApplyOrgConfigBundle() failed.", e);
            return false;
        }
    }

    public static int GetRidmapCount() {
        ClientClassState ccs = GetState();
        return ccs.cachedDroneSpecTable.size();
    }

    @NonNull
    public static List<String> GetRidmapEntriesSnapshot() {
        ClientClassState ccs = GetState();
        ArrayList<String> entries = new ArrayList<>();
        for (Map.Entry<String, CtDroneSpec> map : ccs.cachedDroneSpecTable.entrySet()) {
            CtDroneSpec ds = map.getValue();
            if (ds == null) continue;
            entries.add(String.format(Locale.US,
                    "RID: %s\nMapped ID: %s\nOrg: %s\nModel: %s\nOwner: %s",
                    ds.getRemoteId(),
                    ds.getMappedId(),
                    ds.getOrg(),
                    ds.getModel(),
                    ds.getOwner()));
        }
        entries.sort(String::compareToIgnoreCase);
        return entries;
    }

    public static void ResetPersistedClientState() {
        Ccstate = new ClientClassState();
        if (R2CApplication.getAppCtxt() != null) {
            R2CActivity.MyDeviceName = AndroidDeviceIdentity.displayName(
                    R2CApplication.getAppCtxt());
        }
        SessionUnknownDroneRemoteIds.clear();
        SessionConfirmedDroneRemoteIds.clear();
        CurrentPeerConfirmedDroneRemoteIds.clear();
        DebugLevel = DebugLevelDebug;
        ClearDebugTagFilter();
        ArchivePermissionMissingFlag = false;
        ArchiveState("persistent client state reset");
        NotifySettingsChanged();
        UpdateDroneSpecs();
    }

    public static void SaveDroneSpecConfirmation(
            @NonNull String remoteId,
            @NonNull String org,
            @NonNull String model,
            @NonNull String owner,
            @NonNull String mappedId
    ) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("SaveDroneSpecConfirmation()", remoteId)) return;
        SessionConfirmedDroneRemoteIds.add(remoteId);
        CurrentPeerConfirmedDroneRemoteIds.add(remoteId);
        String trimmedOrg = org.trim();
        String trimmedModel = model.trim();
        String trimmedOwner = owner.trim();
        String trimmedMappedId = mappedId.trim();

        CtDroneSpec activeDs = ccs.droneSpecTable.get(remoteId);
        String previousMappedId = activeDs != null ? activeDs.getMappedId().trim() : "";
        WaypointTrack.TrackPoint latestPointBeforeMappedIdChange = activeDs != null
                ? LatestLocalTrackPoint(activeDs)
                : null;
        if (activeDs == null) {
            // Confirmation panel edits are session-scoped operator input.
            // Keep them only in the active table for this app invocation; they
            // can seed later confirmations in this run but must not become part
            // of the persisted ridmap cache.
            activeDs = new CtDroneSpec(remoteId, trimmedMappedId, trimmedOrg, trimmedModel, trimmedOwner);
            ccs.droneSpecTable.put(remoteId, activeDs);
        } else {
            activeDs.setOrg(trimmedOrg);
            activeDs.setModel(trimmedModel);
            activeDs.setOwner(trimmedOwner);
            activeDs.setMappedId(trimmedMappedId);
        }
        boolean mappedIdChanged = !previousMappedId.equals(activeDs.getMappedId().trim());
        if (activeDs.isLocalArchiveOnly() && isTeamDroneOrg(trimmedOrg)) {
            PromoteLocalArchiveOnlyDrone(remoteId);
        } else {
            SessionUnknownDroneRemoteIds.remove(remoteId);
            activeDs.setLocalArchiveOnly(shouldKeepConfirmedDroneLocalOnly(trimmedOrg));
            UpdateDroneSpecs();
        }
        if (mappedIdChanged) {
            NotifyLatestLocalTrackPoint(activeDs, latestPointBeforeMappedIdChange);
        }
    }

    public static void ApplyPeerDroneSpecConfirmation(
            @NonNull String remoteId,
            @NonNull String org,
            @NonNull String model,
            @NonNull String owner,
            @NonNull String mappedId
    ) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("ApplyPeerDroneSpecConfirmation()", remoteId)) return;
        SessionConfirmedDroneRemoteIds.add(remoteId);
        CurrentPeerConfirmedDroneRemoteIds.add(remoteId);

        String trimmedOrg = org.trim();
        String trimmedModel = model.trim();
        String trimmedOwner = owner.trim();
        String trimmedMappedId = mappedId.trim();

        CtDroneSpec activeDs = ccs.droneSpecTable.get(remoteId);
        if (activeDs == null) {
            activeDs = new CtDroneSpec(
                    remoteId,
                    trimmedMappedId,
                    trimmedOrg,
                    trimmedModel,
                    trimmedOwner);
            ccs.droneSpecTable.put(remoteId, activeDs);
        } else {
            if (!trimmedOrg.isEmpty()) activeDs.setOrg(trimmedOrg);
            if (!trimmedModel.isEmpty()) activeDs.setModel(trimmedModel);
            if (!trimmedOwner.isEmpty()) activeDs.setOwner(trimmedOwner);
            if (!trimmedMappedId.isEmpty()) activeDs.setMappedId(trimmedMappedId);
        }
        activeDs.setLocalArchiveOnly(false);

        UpdateDroneSpecs();
    }

    public static void SaveDroneSpecUnknownConfirmation(@NonNull String remoteId) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("SaveDroneSpecUnknownConfirmation()", remoteId)) return;
        SessionUnknownDroneRemoteIds.add(remoteId);
        CtDroneSpec activeDs = ccs.droneSpecTable.get(remoteId);
        if (activeDs == null) {
            activeDs = new CtDroneSpec(remoteId);
            ccs.droneSpecTable.put(remoteId, activeDs);
        }
        activeDs.setLocalArchiveOnly(true);

        UpdateDroneSpecs();
    }

    private static boolean shouldSuppressMapTracking(@NonNull CtDroneSpec droneSpec) {
        String remoteId = droneSpec.getRemoteId().trim();
        String mappedId = droneSpec.getMappedId().trim();
        return droneSpec.isLocalArchiveOnly()
                || SessionUnknownDroneRemoteIds.contains(remoteId)
                || mappedId.isEmpty()
                || mappedId.equals(remoteId);
    }

    private static boolean isTeamDroneOrg(@Nullable String orgName) {
        String normalizedDroneOrg = NormalizeOrgName(orgName);
        String normalizedTrackerOrg = NormalizeOrgName(GetTrackerUploadOrgName());
        return !normalizedDroneOrg.isEmpty()
                && !normalizedTrackerOrg.isEmpty()
                && normalizedDroneOrg.equals(normalizedTrackerOrg);
    }

    private static boolean shouldKeepConfirmedDroneLocalOnly(@Nullable String orgName) {
        String normalizedDroneOrg = NormalizeOrgName(orgName);
        String normalizedTrackerOrg = NormalizeOrgName(GetTrackerUploadOrgName());
        return !normalizedDroneOrg.isEmpty()
                && !normalizedTrackerOrg.isEmpty()
                && !normalizedDroneOrg.equals(normalizedTrackerOrg);
    }

    @Nullable
    private static WaypointTrack.TrackPoint LatestLocalTrackPoint(@NonNull CtDroneSpec droneSpec) {
        List<WaypointTrack.TrackPoint> points = WaypointTrack.GetTrackPointsSnapshot(droneSpec);
        if (points.isEmpty()) return null;
        return points.get(points.size() - 1);
    }

    private static void NotifyLatestLocalTrackPoint(@NonNull CtDroneSpec droneSpec,
                                                    @Nullable WaypointTrack.TrackPoint latestPointBeforeMappedIdChange) {
        WaypointTrack.TrackPoint point = latestPointBeforeMappedIdChange != null
                ? latestPointBeforeMappedIdChange
                : LatestLocalTrackPoint(droneSpec);
        if (point == null) {
            if (!Double.isFinite(droneSpec.lastLat) || !Double.isFinite(droneSpec.lastLng)) return;
            if (droneSpec.lastLat == 0.0 && droneSpec.lastLng == 0.0) return;
            if (droneSpec.mostRecentMsecTimestamp <= 0) return;
            CaltopoLiveTrack.NotifyLocalTrackPoint(
                    droneSpec,
                    droneSpec.lastLat,
                    droneSpec.lastLng,
                    droneSpec.lastAlt,
                    droneSpec.mostRecentMsecTimestamp);
            return;
        }
        CaltopoLiveTrack.NotifyLocalTrackPoint(
                droneSpec,
                point.lat,
                point.lng,
                point.ele,
                point.timestampMsec);
    }

    public static boolean IsSessionUnknownDrone(@NonNull String remoteId) {
        if (RejectNonCanonicalRemoteId("IsSessionUnknownDrone()", remoteId)) return false;
        return SessionUnknownDroneRemoteIds.contains(remoteId);
    }

    public static boolean IsSessionDroneConfirmed(@NonNull String remoteId) {
        if (RejectNonCanonicalRemoteId("IsSessionDroneConfirmed()", remoteId)) return false;
        return SessionConfirmedDroneRemoteIds.contains(remoteId);
    }

    public static boolean IsCurrentPeerDroneConfirmed(@NonNull String remoteId) {
        if (RejectNonCanonicalRemoteId("IsCurrentPeerDroneConfirmed()", remoteId)) return false;
        return CurrentPeerConfirmedDroneRemoteIds.contains(remoteId);
    }

    public static void ClearCurrentPeerDroneConfirmation(@NonNull String remoteId) {
        if (RejectNonCanonicalRemoteId("ClearCurrentPeerDroneConfirmation()", remoteId)) return;
        CurrentPeerConfirmedDroneRemoteIds.remove(remoteId);
    }

    public static void PromoteLocalArchiveOnlyDrone(@NonNull String remoteId) {
        promoteLocalArchiveOnlyDrone(remoteId, null);
    }

    static void promoteLocalArchiveOnlyDrone(@NonNull String remoteId,
                                             @Nullable LiveTrackOwnerDelegate liveTrackOverride) {
        ClientClassState ccs = GetState();
        if (RejectNonCanonicalRemoteId("promoteLocalArchiveOnlyDrone()", remoteId)) return;
        CtDroneSpec activeDs = ccs.droneSpecTable.get(remoteId);
        if (activeDs == null || !activeDs.isLocalArchiveOnly()) return;

        SessionUnknownDroneRemoteIds.remove(remoteId);
        activeDs.setLocalArchiveOnly(false);
        UpdateDroneSpecs();
        R2CMqttManager.onDroneSpecChanged(remoteId);

        if (!activeDs.isActive()) return;

        LiveTrackOwnerDelegate liveTrack = liveTrackOverride != null
                ? liveTrackOverride
                : CaltopoLiveTrack.GetLiveTrackForRemoteId(remoteId);
        if (liveTrack == null) return;

        double distMeters = CaltopoMap.DistanceFromMeInMeters(activeDs.lastLat, activeDs.lastLng);
        long firstSeenTs = -1L;
        if (liveTrack instanceof CaltopoLiveTrack caltopoLiveTrack) {
            firstSeenTs = caltopoLiveTrack.getFirstTimestamp();
        }
        R2cRuntimeRegistry.getDefaultRuntime()
                .getPeerCoordinator()
                .onLiveTrackCreated(liveTrack, activeDs, distMeters, firstSeenTs);
    }

    public static String GetConfigFilesLoadedRecord() {
        ClientClassState ccs = GetState();
        return String.format(Locale.US, " * %s",
                ccs.configFilesLoaded.trim().replaceAll("\\n", "\n * "));
    }

    public static void CheckUnreportedFiles() {
        if (IsExitRequested()) {
            CTDebug(TAG, "CheckUnreportedFiles(): skipping while app exit is in progress.");
            return;
        }
        try {
            GetArchiveScanExecutorPool().submit(WaypointTrack::BgPollUnreportedTracks);
        } catch (RejectedExecutionException e) {
            CTWarn(TAG, "CheckUnreportedFiles(): archive scan executor rejected task", e);
        }
    }

    public static void DroneSpecStatusChanged(@NonNull CtDroneSpec ds, boolean isActiveFlag) {
        CaltopoMap.OnDroneSpecStatusChanged(isActiveFlag);
        UpdateDroneSpecs();
    }

    @NonNull
    public static CaltopoClient ClientForRemoteId(@NonNull String remoteId)
            throws RuntimeException {
        if (null == ClientMap) {
            ClientMap = new Hashtable<>(16);
        }
        if (remoteId.isEmpty()) {
            throw new RuntimeException("CaltopoClient.ClientForRemoteId(): Invalid remoteId");
        }
        RequireCanonicalRemoteId("ClientForRemoteId()", remoteId);
        CaltopoClient client = ClientMap.get(remoteId);
        if (null == client) {
            client = new CaltopoClient(remoteId);
            ClientMap.put(remoteId, client);
            CTDebug(TAG, String.format(Locale.US,
                    "ClientForRemoteId(): Instantiating client for '%s'", remoteId));
            NotifyDroneConfirmationCandidate(client.droneSpec);
        }

        if (null == client.droneSpec) {
            CTError(TAG, String.format(Locale.US,
                    "ClientForRemoteId(): droneSpec missing for '%s'", remoteId));
        }
        return client;
    }

    @NonNull
    public static String DroneSpecStringRep(@NonNull Hashtable<String, CtDroneSpec> ht) {
        int count = ht.size();
        StringBuilder retval = new StringBuilder(String.format(Locale.US, "%d k/v pairs:", count));

        for (Map.Entry<String, CtDroneSpec> map : ht.entrySet()) {
            CtDroneSpec ds = map.getValue();
            retval.append("\n  ");
            retval.append(ds);
        }
        return retval.toString();
    }

    private static void cleanupLegacyStateFiles(@NonNull Context ctxt) {
        try {
            ctxt.deleteFile(TAG + ".ser");
        } catch (Exception ignored) {
        }
        try {
            ctxt.deleteFile(TAG + ".state");
        } catch (Exception ignored) {
        }
    }

    private static FirebaseAnalytics GetFBAnalytics() {
        if (null == FBAnalytics) {
            Context context = R2CApplication.getAppCtxt();
            if (null != context) {
                FBAnalytics = FirebaseAnalytics.getInstance(context);
            }
        }
        return FBAnalytics;
    }

    private static void SetFBDefaults(@Nullable ClientClassState ccs) {
        if (ccs == null) ccs = GetState();
        FirebaseAnalytics fbAnalytics = GetFBAnalytics();
        if (null == fbAnalytics) return;
        try {
            Bundle parameters = new Bundle();
            parameters.putString("r2c_map", CaltopoMap.GetMapName());
            parameters.putLong("r2c_ctCred", CaltopoCredentials.sniffTest(ccs.caltopoCredentials) ? 1L : 0L);
            parameters.putLong("r2c_newTrackDelayInSeconds", ccs.newTrackDelayInSeconds);
            parameters.putLong("r2c_minDistanceInFeet", ccs.minDistanceInFeet);
            parameters.putLong("r2c_usePeers", ccs.usePeersFlag ? 1L : 0L);
            parameters.putLong("r2c_maxIdleTimeInMinutes", ccs.maxIdleTimeInMinutes);
            parameters.putLong("r2c_debugLevel", ccs.debugLevel);
            fbAnalytics.setDefaultEventParameters(parameters);

        } catch (Exception e) {
            CTError(TAG, "setFBDefaults() raised:", e);
        }
    }

    @NonNull
    private static ClientClassState GetState() {
        InitializeDebugTagRegistry();
        if (null == Ccstate) {
            reloadPersistedStateInternal(false);
            ClientClassState ccs = Ccstate;
            if (ccs != null && !ccs.cachedDroneSpecTable.isEmpty()) {
                Bundle parameters = new Bundle();
                ArrayList<String> mappedIds = new ArrayList<>();
                for (CtDroneSpec ds : ccs.cachedDroneSpecTable.values()) {
                    mappedIds.add(ds.getRemoteId() + ":" + ds.getMappedId());
                }
                parameters.putStringArrayList("r2c_mappedIds", mappedIds);
                CTEvent(TAG, "InitialState", parameters);
            }
        }
        return Ccstate;
    }

    public static synchronized boolean ReloadStateFromStore() {
        return reloadPersistedStateInternal(true);
    }

    private static boolean reloadPersistedStateInternal(boolean notifySettings) {
        Context ctxt = R2CApplication.getAppCtxt();
        ClientClassState ccs = null;
        if (ctxt != null) {
            AppConfigStore.initialize(ctxt);
            ccs = (ClientClassState) AppConfigStore.restoreClientState(ctxt);
            ArchivePermissionMissingFlag = AppConfigStore.getArchiveRequiresRegrant(ctxt);
            cleanupLegacyStateFiles(ctxt);
            CTDebug(TAG, "reloadPersistedStateInternal(): restored ClientClassState from proto store.");
        }
        if (null == ccs) ccs = new ClientClassState();
        // Publish a provisional state immediately so any re-entrant getters hit this
        // object instead of re-entering restoreClientState() while startup is still in flight.
        Ccstate = ccs;
        if (ctxt != null) {
            applyArchivePathBackupPrefs(ctxt, ccs);
        }
        if (null == ccs.droneSpecTable) ccs.droneSpecTable = new Hashtable<>(16);
        if (null == ccs.cachedDroneSpecTable) ccs.cachedDroneSpecTable = new Hashtable<>(16);
        if (ccs.caltopoProfiles == null) ccs.caltopoProfiles = new ArrayList<>();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord activeProfile = null;
        if (ccs.caltopoProfiles != null && !ccs.caltopoProfiles.isEmpty()) {
            for (CaltopoProfileRecord profile : ccs.caltopoProfiles) {
                if (profile.profileId != null && profile.profileId.equals(ccs.activeCaltopoProfileId)) {
                    activeProfile = profile;
                    break;
                }
            }
            if (activeProfile == null) {
                activeProfile = ccs.caltopoProfiles.get(0);
                ccs.activeCaltopoProfileId = activeProfile.profileId;
            }
            mirrorProfileIntoLegacyFields(ccs, activeProfile);
        }
        CTDebug(TAG, "reloadPersistedStateInternal(): " + Ccstate);
        SetFBDefaults(ccs);
        if (notifySettings) {
            NotifySettingsChanged();
        }
        return true;
    }

    private static void ArchiveState(@NonNull String reason) {
        if (null != Ccstate) try {
            // so many contexts to choose from - hopefully one of them works...
            Context ctxt = R2CApplication.getAppCtxt();
            if (null == ctxt) {
                CTDebug(TAG, "ArchiveState(): Missing required app context." );
                return;
            }
            Ccstate.debugLevel = DebugLevel;
            AppConfigStore.persistState(ctxt, Ccstate, ArchivePermissionMissingFlag);
            CTDebug(TAG, String.format(Locale.US, "ArchiveState(%s):\n%s", reason, Ccstate));
            SetFBDefaults(Ccstate);
            Bundle parameters = new Bundle();
            parameters.putString("r2c_reason", reason);
            CTEvent(TAG, "ArchiveState", parameters);
        } catch (Exception e) {
            CTError(TAG, "ArchiveState() raised:", e);
        }
    }

    // This gets run by periodic timer.
    private static void ProcessSortedCurrentDroneSpecArray() {
        ProcessSortedCurrentDroneSpecArray(false);
    }

    public static void InvalidateTrackAgingSchedule() {
        PreviousEarliestAgeOutInMsec = 0;
    }

    /**
     * ProcessSortedCurrentDroneSpecArray()
     * Sorts active droneSpecs by flight start Time, so oldest will appear first in
     * list while also checking for timeout and removing aged-out dronespecs that
     * have stopped providing local or peer-relayed telemetry.
     *
     * @param changedFlag True if something has changed and we need to refresh the list.
     *                    If false, then check for inactive dronespecs.
     */

    private static synchronized void ProcessSortedCurrentDroneSpecArray(boolean changedFlag) {
        if (ProcessingDroneSpecUpdate) {
            PendingDroneSpecUpdate |= changedFlag;
            return;
        }
        ProcessingDroneSpecUpdate = true;
        try {
            boolean forceChanged = changedFlag;
            do {
                boolean runChanged = forceChanged || PendingDroneSpecUpdate;
                PendingDroneSpecUpdate = false;
                ProcessSortedCurrentDroneSpecArrayOnce(runChanged);
                forceChanged = false;
            } while (PendingDroneSpecUpdate);
        } finally {
            ProcessingDroneSpecUpdate = false;
        }
    }

    private static void ProcessSortedCurrentDroneSpecArrayOnce(boolean changedFlag) {
        long mostRecentUpdate = CtDroneSpec.LastWaypointUpdateTimestampMsec();

        ClientClassState ccs = GetState();
        long newTrackDelayInMsec = ccs.newTrackDelayInSeconds * 1000;
        long currentTimeInMsec = System.currentTimeMillis();
        long nextAgeOutInMsec = newTrackDelayInMsec;
        if (changedFlag || currentTimeInMsec >= PreviousEarliestAgeOutInMsec) {
            DsArray.clear();
            for (CtDroneSpec ds : ccs.droneSpecTable.values()) {
                // terminateTrack() resets the CtDroneSpec and synchronously requests another
                // list rebuild. Never evaluate or retain that inactive shell: its cleared 0,0
                // position otherwise appears to be millions of feet from the tablet and the
                // distant-aircraft RID-loss exception can resurrect it as an empty row.
                if (!ds.isActive()) continue;
                long trackDelayInMsec = TrackDelayInMsecForDroneSpec(ds, newTrackDelayInMsec);
                long telemetryIdleInMsec = ds.trackTelemetryIdleTimeInMsec(currentTimeInMsec);
                PairedVideoFlightActivity videoActivity =
                        StreamFlightActivityRegistry.activityForRemoteId(ds.getRemoteId(), currentTimeInMsec);
                long combinedIdleInMsec = CombinedFlightIdleTimeInMsec(
                        telemetryIdleInMsec,
                        currentTimeInMsec,
                        videoActivity);
                boolean trackAgedOut = HasTrackAgedOut(combinedIdleInMsec, trackDelayInMsec);
                if (trackAgedOut) {
                    CaltopoClient client = (ClientMap != null) ? ClientMap.get(ds.getRemoteId()) : null;
                    String msg = String.format(Locale.US,
                            "ProcessSortedCurrentDroneSpecArray(%s): %s RID/peer idle %.3f seconds, combined RID/video idle %.3f/%.3f seconds, pairedVideoActive=%s. Finishing track...",
                            changedFlag, ds.trackLabel(),
                            (double) telemetryIdleInMsec / 1000.0,
                            (double) combinedIdleInMsec / 1000.0,
                            (double) trackDelayInMsec / 1000.0,
                            videoActivity.getPublisherActive(),
                            TrackDelayReasonSuffix(ds));
                    CTInfo(TAG, msg);
                    if (client != null) {
                        client.terminateTrack(msg, false);
                    } else {
                        CTWarn(TAG, "ProcessSortedCurrentDroneSpecArray(): no CaltopoClient found for " + ds.getRemoteId());
                    }
                    changedFlag = true;
                    continue;
                }
                long currentAgeOutInMsec = trackAgedOut
                        ? 1_000L
                        : trackDelayInMsec - combinedIdleInMsec;
                if (currentAgeOutInMsec < nextAgeOutInMsec) nextAgeOutInMsec = currentAgeOutInMsec;
                if (CTDebugEnabled(TAG)) CTDebug(TAG, String.format(Locale.US,
                        "ProcessSortedCurrentDroneSpecArray(%s): RID/peer idle for %s is %.3f, combined RID/video idle %.3f, age check in %.3f seconds, next age check in %.3f seconds, pairedVideoActive=%s%s",
                        changedFlag, ds.getMappedId(), telemetryIdleInMsec / 1000.0, combinedIdleInMsec / 1000.0,
                        currentAgeOutInMsec / 1000.0, nextAgeOutInMsec / 1000.0,
                        videoActivity.getPublisherActive(),
                        TrackDelayReasonSuffix(ds)));
                DsArray.add(ds);
            }
            PreviousEarliestAgeOutInMsec = currentTimeInMsec + nextAgeOutInMsec;
            DsArray.sort(CtDroneSpec::compareToAge);
            long newSize = DsArray.size();
            if (!changedFlag && (DroneSpecsArraySize != newSize)) {
                if (CTDebugEnabled(TAG)) CTDebug(TAG, String.format(Locale.US,
                        "ProcessSortedCurrentDroneSpecArray(): arraySize changed from:%d to :%d", DroneSpecsArraySize, newSize));
                DroneSpecsArraySize = newSize;
            }
        }

        ArrayList<CtDroneSpec> dsArrayClone = (ArrayList<CtDroneSpec>) DsArray.clone();
        ProximityAlertCenter.INSTANCE.updateDrones(dsArrayClone);
        if (false) CTDebug(TAG, String.format(Locale.US,
                "ProcessSortedCurrentDroneSpecArray(%s) updating %d listeners",
                changedFlag, DroneSpecsChangedListeners.size()));
        for (CtDroneSpec.DroneSpecsChangedListener listener : DroneSpecsChangedListeners) {
            if (false) CTDebug(TAG, String.format(Locale.US,
                    "ProcessSortedCurrentDroneSpecArray(%s) + Updating 0x%x:%s", changedFlag,
                    listener.hashCode(), listener.getClass().getName()));
            listener.onDroneSpecsChanged(dsArrayClone);
        }
        if (DsArray.size() == 0) {
            if (UiUpdatePoll.stop()) {
                CTDebug(TAG, "ProcessSortedCurrentDroneSpecArray(): Stopping UiUpdatePoll.");
            }
        } else if (!UiUpdatePoll.isRunning()) {
            UiUpdatePoll.start(CaltopoClient::ProcessSortedCurrentDroneSpecArray, 1000, 1000);
            CTDebug(TAG, "UpdateDroneSpecs(): Starting UiUpdatePoll...");
        }
    }

    static long TrackDelayInMsecForDroneSpecForTests(@NonNull CtDroneSpec ds, long newTrackDelayInMsec) {
        return TrackDelayInMsecForDroneSpec(ds, newTrackDelayInMsec);
    }

    static boolean HasTrackAgedOutForTests(long telemetryIdleInMsec, long trackDelayInMsec) {
        return HasTrackAgedOut(telemetryIdleInMsec, trackDelayInMsec);
    }

    static long CombinedFlightIdleTimeInMsecForTests(long telemetryIdleInMsec,
                                                      long currentTimeInMsec,
                                                      boolean publisherActive,
                                                      long lastVideoActivityAtMsec) {
        return CombinedFlightIdleTimeInMsec(
                telemetryIdleInMsec,
                currentTimeInMsec,
                new PairedVideoFlightActivity(publisherActive, lastVideoActivityAtMsec));
    }

    private static boolean HasTrackAgedOut(long telemetryIdleInMsec, long trackDelayInMsec) {
        return telemetryIdleInMsec >= trackDelayInMsec;
    }

    private static long CombinedFlightIdleTimeInMsec(long telemetryIdleInMsec,
                                                      long currentTimeInMsec,
                                                      @NonNull PairedVideoFlightActivity videoActivity) {
        if (videoActivity.getPublisherActive()) return 0L;
        long lastVideoActivityAtMsec = videoActivity.getLastActivityAtMs();
        if (lastVideoActivityAtMsec <= 0L) return telemetryIdleInMsec;
        long videoIdleInMsec = Math.max(0L, currentTimeInMsec - lastVideoActivityAtMsec);
        return Math.min(telemetryIdleInMsec, videoIdleInMsec);
    }

    private static long TrackDelayInMsecForDroneSpec(@NonNull CtDroneSpec ds, long newTrackDelayInMsec) {
        // A paired live video publisher is a second presence source. Retirement occurs only
        // after RID/peer telemetry and paired publisher activity have both been absent for this
        // configured interval.
        return newTrackDelayInMsec;
    }

    @NonNull
    private static String TrackDelayReasonSuffix(@NonNull CtDroneSpec ds) {
        if (ds.isOutOfRange()) return " (OOR)";
        return "";
    }

    public static long GetNewTrackDelayInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.newTrackDelayInSeconds;
    }

    public static long GetMaxFlatlineToneDurationInSeconds() {
        ClientClassState ccs = GetState();
        long durationInSeconds = ccs.maxFlatlineToneDurationInSeconds;
        if (durationInSeconds <= 0L) {
            durationInSeconds = DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS;
        }
        return durationInSeconds;
    }

    public static long GetBridgeCheckDistanceFeet() {
        ClientClassState ccs = GetState();
        if (ccs.bridgeCheckDistanceFeet <= 0L) {
            ccs.bridgeCheckDistanceFeet = DEFAULT_BRIDGE_CHECK_DISTANCE_FEET;
        }
        return ccs.bridgeCheckDistanceFeet;
    }

    public static int GetAlarmVolumePercent() {
        ClientClassState ccs = GetState();
        return Math.max(0, Math.min(100, ccs.alarmVolumePercent));
    }

    public static boolean GetAlarmVolumeConfigured() {
        return GetState().alarmVolumeConfigured;
    }

    public static float GetAlarmVolumeMultiplier() {
        if (!GetAlarmVolumeConfigured()) {
            return 1.0f;
        }
        return GetAlarmVolumePercent() / 100.0f;
    }

    public static int GetToneGeneratorAlarmVolumePercent() {
        return Math.max(0, Math.min(100, Math.round(100.0f * GetAlarmVolumeMultiplier())));
    }

    public static long GetMinDistanceInFeet() {
        ClientClassState ccs = GetState();
        return ccs.minDistanceInFeet;
    }

    @Nullable
    public static Uri GetArchiveUri() {
        ClientClassState ccs = GetState();
        if (ccs.archivePath.isEmpty()) return null;
        Uri archiveUri;
        try {
            archiveUri = Uri.parse(ccs.archivePath);
        } catch (Exception e) {
            CTWarn(TAG, "GetArchiveUri(): archivePath parse failed. clearing.", e);
            clearArchivePath("archivePath parse failed");
            return null;
        }
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt != null && !isArchiveUriUsable(ctxt, archiveUri)) {
            CTWarn(TAG, "GetArchiveUri(): archive uri no longer accessible. clearing.");
            ArchivePermissionMissingFlag = true;
            clearArchivePath("archivePath permission missing");
            return null;
        }
        ArchivePermissionMissingFlag = false;
        return archiveUri;
    }

    @Nullable
    public static Uri GetArchiveUriSelectionHint() {
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return null;
        String appConfigHint = AppConfigStore.getArchiveSelectionHint(ctxt);
        if (appConfigHint != null && !appConfigHint.isEmpty()) {
            try {
                return Uri.parse(appConfigHint);
            } catch (Exception e) {
                CTWarn(TAG, "GetArchiveUriSelectionHint(): app config hint parse failed.", e);
            }
        }
        String hint = ctxt.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(BACKUP_KEY_ARCHIVE_HINT, "");
        if (hint == null || hint.isEmpty()) return null;
        try {
            return Uri.parse(hint);
        } catch (Exception e) {
            CTWarn(TAG, "GetArchiveUriSelectionHint(): parse failed.", e);
            return null;
        }
    }

    public static boolean WasArchiveUriPermissionMissing() {
        return ArchivePermissionMissingFlag;
    }

    @NonNull
    public static String GetTrackerApiKey() {
        return GetTrackerCoordinationApiKey();
    }

    public static void SetTrackerApiKey(@NonNull String apiKey) {
        ClientClassState ccs = GetState();
        apiKey = apiKey.trim();
        if (!ccs.trackerApiKey.equals(apiKey)) {
            ccs.trackerApiKey = apiKey;
            NotifySettingsChanged();
            ArchiveState("tracker api key changed");
        }
    }

    @NonNull
    public static String GetTrackerUrlPfx() {
        return GetTrackerCoordinationUrlPfx();
    }

    public static void SetTrackerUrlPfx(@NonNull String urlPfx) {
        ClientClassState ccs = GetState();
        urlPfx = urlPfx.trim();
        if (!ccs.trackerUrlPfx.equals(urlPfx)) {
            ccs.trackerUrlPfx = urlPfx;
            NotifySettingsChanged();
            ArchiveState("tracker url prefix changed");
        }
    }

    public static String GetIncident() {
        if (CaltopoMap.GetMapStatus() == CaltopoMap.MapStatusListener.mapStatus.up) {
            String mapName = CaltopoMap.GetMapName().trim();
            if (!mapName.isEmpty()) return mapName;
        }
        return "Training";
    }

    public static void SetIncident(@NonNull String incident) {
        ClientClassState ccs = GetState();
        if (!incident.equals(ccs.incident)) {
            ccs.incident = incident;
        }
    }

    public static String GetOpPeriod() {
        ClientClassState ccs = GetState();
        ensureProfileStateFresh(ccs, false);
        CaltopoProfileRecord profile = GetActiveCaltopoProfile();
        if (profile != null && profile.opPeriod != null) {
            return profile.opPeriod;
        }
        return ccs.opPeriod;
    }

    public static void SetOpPeriod(@NonNull String opPeriod) {
        ClientClassState ccs = GetState();
        if (!ccs.opPeriod.equals(opPeriod)) {
            ccs.opPeriod = opPeriod;
            ArchiveState("opPeriod changed");
        }
    }

    @NonNull
    public static String GetCoordinateDisplayFormat() {
        ClientClassState ccs = GetState();
        if (ccs.coordinateDisplayFormat == null || ccs.coordinateDisplayFormat.isEmpty()) {
            ccs.coordinateDisplayFormat = "decimal";
        }
        return ccs.coordinateDisplayFormat;
    }

    public static void SetCoordinateDisplayFormat(@NonNull String format) {
        ClientClassState ccs = GetState();
        if (!ccs.coordinateDisplayFormat.equals(format)) {
            ccs.coordinateDisplayFormat = format;
            ArchiveState("coordinate display format changed");
        }
    }

    public static boolean GetPredictiveHeadEnabled() {
        return GetState().predictiveHeadEnabled;
    }

    public static void SetPredictiveHeadEnabled(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.predictiveHeadEnabled != enabled) {
            ccs.predictiveHeadEnabled = enabled;
            NotifySettingsChanged();
            ArchiveState("predictive head changed");
        }
    }

    public static long GetProximityAlertSpacingFeet() {
        return GetState().proximityAlertSpacingFeet;
    }

    public static void SetProximityAlertSpacingFeet(long feet) {
        long normalized = Math.max(0L, feet);
        ClientClassState ccs = GetState();
        if (ccs.proximityAlertSpacingFeet != normalized) {
            ccs.proximityAlertSpacingFeet = normalized;
            NotifySettingsChanged();
            ArchiveState("proximity alert spacing changed");
        }
    }

    public static boolean GetNotamEnabled() {
        return GetState().notamEnabled;
    }

    public static void SetNotamEnabled(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.notamEnabled != enabled) {
            ccs.notamEnabled = enabled;
            NotifySettingsChanged();
            ArchiveState("notam enabled changed");
        }
    }

    public static int GetNotamRadiusNm() {
        return GetState().notamRadiusNm;
    }

    public static void SetNotamRadiusNm(int radiusNm) {
        int normalized;
        if (radiusNm <= 1) normalized = 1;
        else if (radiusNm <= 2) normalized = 2;
        else if (radiusNm <= 4) normalized = 4;
        else if (radiusNm <= 8) normalized = 8;
        else normalized = 16;
        ClientClassState ccs = GetState();
        if (ccs.notamRadiusNm != normalized) {
            ccs.notamRadiusNm = normalized;
            NotifySettingsChanged();
            ArchiveState("notam radius changed");
        }
    }

    public static boolean GetNotamAutoRefresh() {
        return GetState().notamAutoRefresh;
    }

    public static void SetNotamAutoRefresh(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.notamAutoRefresh != enabled) {
            ccs.notamAutoRefresh = enabled;
            NotifySettingsChanged();
            ArchiveState("notam auto refresh changed");
        }
    }

    public static int GetNotamRefreshIntervalSeconds() {
        return GetState().notamRefreshIntervalSeconds;
    }

    public static void SetNotamRefreshIntervalSeconds(int seconds) {
        int normalized = Math.max(1800, seconds);
        ClientClassState ccs = GetState();
        if (ccs.notamRefreshIntervalSeconds != normalized) {
            ccs.notamRefreshIntervalSeconds = normalized;
            NotifySettingsChanged();
            ArchiveState("notam refresh interval changed");
        }
    }

    public static boolean GetNotamWarnInsideOneNm() {
        return GetState().notamWarnInsideOneNm;
    }

    public static void SetNotamWarnInsideOneNm(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.notamWarnInsideOneNm != enabled) {
            ccs.notamWarnInsideOneNm = enabled;
            NotifySettingsChanged();
            ArchiveState("notam warn inside one nm changed");
        }
    }

    @NonNull
    public static String GetNotamApiBaseUrl() {
        return GetState().notamApiBaseUrl;
    }

    public static void SetNotamApiBaseUrl(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.notamApiBaseUrl.equals(value)) {
            ccs.notamApiBaseUrl = value;
            NotifySettingsChanged();
            ArchiveState("notam api base url changed");
        }
    }

    @NonNull
    public static String GetNotamTokenUrl() {
        return GetState().notamTokenUrl;
    }

    public static void SetNotamTokenUrl(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.notamTokenUrl.equals(value)) {
            ccs.notamTokenUrl = value;
            NotifySettingsChanged();
            ArchiveState("notam token url changed");
        }
    }

    @NonNull
    public static String GetNotamClientId() {
        return GetState().notamClientId;
    }

    public static void SetNotamClientId(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.notamClientId.equals(value)) {
            ccs.notamClientId = value;
            NotifySettingsChanged();
            ArchiveState("notam client id changed");
        }
    }

    @NonNull
    public static String GetNotamClientSecret() {
        return GetState().notamClientSecret;
    }

    public static void SetNotamClientSecret(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.notamClientSecret.equals(value)) {
            ccs.notamClientSecret = value;
            NotifySettingsChanged();
            ArchiveState("notam client secret changed");
        }
    }

    @NonNull
    public static String GetNotamScope() {
        return GetState().notamScope;
    }

    public static void SetNotamScope(@NonNull String value) {
        value = value.trim();
        ClientClassState ccs = GetState();
        if (!ccs.notamScope.equals(value)) {
            ccs.notamScope = value;
            NotifySettingsChanged();
            ArchiveState("notam scope changed");
        }
    }

    public static long GetNotamLastUpdatedEpochMs() {
        return GetState().notamLastUpdatedEpochMs;
    }

    public static void SetNotamLastUpdatedEpochMs(long value) {
        ClientClassState ccs = GetState();
        if (ccs.notamLastUpdatedEpochMs != value) {
            ccs.notamLastUpdatedEpochMs = value;
        }
    }

    public static boolean GetLandRestrictionsEnabled() {
        return GetState().landRestrictionsEnabled;
    }

    public static void SetLandRestrictionsEnabled(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.landRestrictionsEnabled != enabled) {
            ccs.landRestrictionsEnabled = enabled;
            NotifySettingsChanged();
            ArchiveState("land restrictions enabled changed");
        }
    }

    public static boolean GetLandRestrictionsShowOnMap() {
        return GetState().landRestrictionsShowOnMap;
    }

    public static void SetLandRestrictionsShowOnMap(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.landRestrictionsShowOnMap != enabled) {
            ccs.landRestrictionsShowOnMap = enabled;
            NotifySettingsChanged();
            ArchiveState("land restrictions map visibility changed");
        }
    }

    public static boolean GetLandRestrictionsAutoRefresh() {
        return GetState().landRestrictionsAutoRefresh;
    }

    public static void SetLandRestrictionsAutoRefresh(boolean enabled) {
        ClientClassState ccs = GetState();
        if (ccs.landRestrictionsAutoRefresh != enabled) {
            ccs.landRestrictionsAutoRefresh = enabled;
            NotifySettingsChanged();
            ArchiveState("land restrictions auto refresh changed");
        }
    }

    public static int GetLandRestrictionsRadiusNm() {
        return GetState().landRestrictionsRadiusNm;
    }

    public static void SetLandRestrictionsRadiusNm(int radiusNm) {
        int normalized = Math.max(1, Math.min(50, radiusNm));
        ClientClassState ccs = GetState();
        if (ccs.landRestrictionsRadiusNm != normalized) {
            ccs.landRestrictionsRadiusNm = normalized;
            NotifySettingsChanged();
            ArchiveState("land restrictions radius changed");
        }
    }

    public static long GetLandRestrictionsLastUpdatedEpochMs() {
        return GetState().landRestrictionsLastUpdatedEpochMs;
    }

    public static void SetLandRestrictionsLastUpdatedEpochMs(long value) {
        ClientClassState ccs = GetState();
        if (ccs.landRestrictionsLastUpdatedEpochMs != value) {
            ccs.landRestrictionsLastUpdatedEpochMs = value;
            ArchiveState("land restrictions timestamp changed");
        }
    }

    @NonNull
    public static String GetFaaRemoteToken() {
        String value = GetState().faaRemoteToken;
        return value != null ? value : "";
    }

    @NonNull
    public static String GetFaaConfigLabel() {
        String value = GetState().faaConfigLabel;
        return value != null ? value : "";
    }

    @NonNull
    public static String GetFaaPayloadEnc() {
        String value = GetState().faaPayloadEnc;
        return value != null ? value : "";
    }

    public static long GetFaaLastValidatedEpochMs() {
        return GetState().faaLastValidatedEpochMs;
    }

    public static boolean GetFaaConfigStale() {
        return GetState().faaConfigStale;
    }

    @NonNull
    public static String GetFaaLastFailureReason() {
        String value = GetState().faaLastFailureReason;
        return value != null ? value : "";
    }

    public static void StoreFaaRemoteConfig(
            @NonNull String token,
            @NonNull String label,
            @NonNull String payloadEnc,
            long lastValidatedEpochMs,
            boolean stale,
            @NonNull String failureReason
    ) {
        ClientClassState ccs = GetState();
        ccs.faaRemoteToken = token.trim();
        ccs.faaConfigLabel = label.trim();
        ccs.faaPayloadEnc = payloadEnc;
        ccs.faaLastValidatedEpochMs = Math.max(0L, lastValidatedEpochMs);
        ccs.faaConfigStale = stale;
        ccs.faaLastFailureReason = failureReason.trim();
        NotifySettingsChanged();
        ArchiveState("faa remote config changed");
    }

    public static void MarkFaaConfigStale(@NonNull String reason) {
        ClientClassState ccs = GetState();
        ccs.faaConfigStale = true;
        ccs.faaLastFailureReason = reason.trim();
        NotifySettingsChanged();
        ArchiveState("faa remote config marked stale");
    }

    public static void InitArchiveDir() {
        DocumentFile todaysArchiveDir = GetTodaysTrackDir();
        if (null == todaysArchiveDir) {
            CTError(TAG, "InitArchiveDir(): archive dir is mia.");
            return;
        }
        if (DebugLogPath == null) try {
            CTDebug(TAG, "InitArchiveDir(): Initializing log stream...");
            String filepath = BuildDebugLogFilename(System.currentTimeMillis());
            Context ctxt = R2CApplication.getAppCtxt();
            if (null != ctxt) try {
                DocumentFile dataFilepath = DocumentFileCompat.createFileWithExactName(
                        todaysArchiveDir, "text/plain", filepath);
                ContentResolver resolver = ctxt.getContentResolver();
                synchronized (DebugLogLock) {
                    if (DebugLogPath == null && null != dataFilepath) {
                        Uri logPath = dataFilepath.getUri();
                        OutputStream fileOutputStream = resolver.openOutputStream(logPath);
                        if (fileOutputStream != null && DebugOutputStream instanceof DeferredLogOutputStream) {
                            ((DeferredLogOutputStream) DebugOutputStream).attach(fileOutputStream);
                            DebugLogPath = logPath;
                            DebugLogGeneration++;
                        } else if (fileOutputStream != null) {
                            DebugOutputStream = fileOutputStream;
                            DebugLogPath = logPath;
                            DebugLogGeneration++;
                        } else {
                            Log.e(TAG, "CTError: InitArchiveDir(): openOutputStream returned null for " + logPath);
                        }
                    }
                }
            } catch (Exception e) {
                CTError(TAG, "InitArchiveDir() raised: ", e);
            }

            if (null != DebugLogPath && null != DebugOutputStream) {
                LogFilePath = todaysArchiveDir + "/" + filepath;
                R2CActivity activity = R2CActivity.getR2CActivity();
                String appVers = BuildConfig.BUILD_VERSION;
                final String header = "########################################################################\n";
                CTDebug(TAG, String.format(Locale.US,
                        "Logfile is up on %s @%s\n%s#  RID2Caltopo %s(%s) running on Android OS v%s(%d)\n#  Writing logs to: %s\n%s",
                        R2CActivity.MyDeviceName, R2CMqttManager.GetMyIpAddresses(), header, appVers,
                        BuildConfig.BUILD_TIME, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, LogFilePath, header));
            }

            CheckUnreportedFiles();
        } catch (Exception e) {
            Log.e(TAG, "CTError: Not able to open DebugOutputStream: " + e);
        }
    }

    static String BuildDebugLogFilename(long startTimeInMsec) {
        return OperatorArchiveFilename.log(startTimeInMsec);
    }

    public static void SetArchiveUri(@NonNull Uri pathUri) {
        ClientClassState ccs = GetState();
        String pathString = pathUri.toString();
        TemporaryArchiveRootForSession = null;
        if (!ccs.archivePath.equals(pathString)) {
            ccs.archivePath = pathString;
            persistArchivePathBackup(pathString);
            persistArchiveHintBackup(pathString);
            ArchivePermissionMissingFlag = false;
            ArchiveState("archivePath changed.");
            InitArchiveDir();
        }
    }

    public static boolean UseTemporaryArchiveDirForSession() {
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return false;
        File root = new File(ctxt.getCacheDir(), "temporary-archive");
        if (!root.exists() && !root.mkdirs()) {
            CTWarn(TAG, "UseTemporaryArchiveDirForSession(): unable to create " + root);
            return false;
        }
        if (!root.isDirectory() || !root.canWrite()) {
            CTWarn(TAG, "UseTemporaryArchiveDirForSession(): root is not writable " + root);
            return false;
        }
        TemporaryArchiveRootForSession = root;
        ArchivePermissionMissingFlag = false;
        CTWarn(TAG, "Using temporary archive directory for this app session: " + root);
        InitArchiveDir();
        return true;
    }

    public static boolean HasArchiveDirForCurrentSession() {
        return GetArchiveDir() != null;
    }

    @Nullable
    private static DocumentFile GetTemporaryArchiveDirForSession() {
        File root = TemporaryArchiveRootForSession;
        if (root == null) return null;
        if (!root.exists() && !root.mkdirs()) return null;
        if (!root.isDirectory() || !root.canWrite()) return null;
        return DocumentFile.fromFile(root);
    }

    private static void clearArchivePath(@NonNull String reason) {
        ClientClassState ccs = GetState();
        if (ccs.archivePath.isEmpty()) return;
        persistArchiveHintBackup(ccs.archivePath);
        ccs.archivePath = "";
        persistArchivePathBackup("");
        ArchiveState(reason);
    }

    private static void applyArchivePathBackupPrefs(@NonNull Context ctxt, @NonNull ClientClassState ccs) {
        String backupArchivePath = ctxt.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(BACKUP_KEY_ARCHIVE_PATH, "");
        if (backupArchivePath == null) backupArchivePath = "";
        if (!backupArchivePath.isEmpty() && !backupArchivePath.equals(ccs.archivePath)) {
            ccs.archivePath = backupArchivePath;
            CTDebug(TAG, "RestoreState(): archivePath restored from backup preferences.");
            persistArchiveHintBackup(backupArchivePath);
        } else if (backupArchivePath.isEmpty() && !ccs.archivePath.isEmpty()) {
            // one-time migration from secure/legacy serialized state into backup-eligible prefs.
            persistArchivePathBackup(ccs.archivePath);
            persistArchiveHintBackup(ccs.archivePath);
        }
    }

    private static void persistArchivePathBackup(@Nullable String archivePath) {
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return;
        String normalized = archivePath == null ? "" : archivePath;
        ctxt.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BACKUP_KEY_ARCHIVE_PATH, normalized)
                .apply();
    }

    private static void persistArchiveHintBackup(@Nullable String archivePath) {
        Context ctxt = R2CApplication.getAppCtxt();
        if (ctxt == null) return;
        String normalized = archivePath == null ? "" : archivePath;
        ctxt.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BACKUP_KEY_ARCHIVE_HINT, normalized)
                .apply();
    }

    private static boolean hasPersistedReadPermission(@NonNull Context ctxt, @NonNull Uri uri) {
        ContentResolver resolver = ctxt.getContentResolver();
        List<UriPermission> permissions = resolver.getPersistedUriPermissions();
        for (UriPermission permission : permissions) {
            if (permission == null) continue;
            Uri granted = permission.getUri();
            if (granted == null) continue;
            if (uri.equals(granted) && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArchiveUriUsable(@NonNull Context ctxt, @NonNull Uri archiveUri) {
        try {
            if (!hasPersistedReadPermission(ctxt, archiveUri)) return false;
            DocumentFile archiveDir = DocumentFile.fromTreeUri(ctxt, archiveUri);
            return archiveDir != null && archiveDir.exists() && archiveDir.isDirectory() && archiveDir.canRead();
        } catch (Exception e) {
            CTWarn(TAG, "isArchiveUriUsable() raised.", e);
            return false;
        }
    }

    private static void NotifySettingsChanged() {
        if (!NotifySettingsChangedFlag && null != SettingsListener) {
            CTDebug(TAG, "notifySettingsChanged()");
            NotifySettingsChangedFlag = true;
            SettingsListener.settingsChanged();
            NotifySettingsChangedFlag = false;
        }
    }

    public static void QuitApplication() {
        synchronized (ShutdownLock) {
            AppExitRequested = true;
        }
        Context context = R2CApplication.getAppCtxt();

        Activity activity = R2CActivity.getR2CActivity();
        if (null != activity) {
            Runnable finishActivity = () -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.finishAndRemoveTask();
                } else {
                    activity.finish();
                }
                MapOfflinePrepRuntime.cancelActive();
            };
            // The idle watchdog runs on a background scheduler. Activity lifecycle calls must
            // always be dispatched to Android's main thread.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                finishActivity.run();
            } else {
                activity.runOnUiThread(finishActivity);
            }
        } else {
            new Handler(Looper.getMainLooper()).post(MapOfflinePrepRuntime::cancelActive);
        }

        // Submit the activity removal before any cleanup that can block on Android services,
        // radio teardown, archive I/O, or a long-lived document-provider stream.
        ScanningService.requestStop(context);
        MediaMTXService.requestStop(context);
        ShutdownAsync();
    }

    static void InstallDebugOutputStreamForTests(@NonNull OutputStream outputStream) {
        synchronized (DebugLogLock) {
            DebugOutputStream = outputStream;
            DebugLogPath = null;
            LogFilePath = "test";
            BytesWrittenToDebugOutputStream = 0;
            DebugLogGeneration++;
        }
    }

    static long CaptureDebugLogGenerationForTests() {
        return CaptureDebugLogGeneration();
    }

    static void CloseDebugOutputStreamForShutdownForTests(long shutdownDebugLogGeneration) {
        CloseDebugOutputStreamForShutdown(shutdownDebugLogGeneration);
    }

    private static long CaptureDebugLogGeneration() {
        synchronized (DebugLogLock) {
            return DebugLogGeneration;
        }
    }

    private static void CloseDebugOutputStreamForShutdown(long shutdownDebugLogGeneration) {
        synchronized (DebugLogLock) {
            if (shutdownDebugLogGeneration != DebugLogGeneration) {
                Log.w(TAG, String.format(Locale.US,
                        "Skipping stale debug log close: shutdownGeneration=%d currentGeneration=%d",
                        shutdownDebugLogGeneration, DebugLogGeneration));
                return;
            }
            if (null != DebugOutputStream) {
                try {
                    DebugOutputStream.flush();
                    DebugOutputStream.close();
                    ResetDebugOutputStreamLocked();
                } catch (IOException e) {
                    Log.e(TAG, "CTError: Shutdown raised: " + e);
                    ResetDebugOutputStreamLocked();
                }
            }
        }
    }


    public static void ShutdownAsync() {
        Thread shutdownThread = new Thread(CaltopoClient::Shutdown, "R2C-Shutdown");
        shutdownThread.start();
    }

    public static void Shutdown() {
        synchronized (ShutdownLock) {
            if (ShutdownInProgress) {
                return;
            }
            ShutdownInProgress = true;
        }
        long shutdownDebugLogGeneration = CaptureDebugLogGeneration();
        try {
            NotamCenter.INSTANCE.shutdown();
            LandRestrictionCenter.INSTANCE.shutdown();
            if (Ccstate != null) {
                ArchiveState("shutdown");
            }
            WaypointTrack.ArchiveTracks();
            ShutdownExecutorPool(LivePublishExecutorPool, true);
            LivePublishExecutorPool = null;
            ShutdownExecutorPool(GeoJsonStatsExecutorPool, true);
            GeoJsonStatsExecutorPool = null;
            ShutdownExecutorPool(ArchiveScanExecutorPool, true);
            ArchiveScanExecutorPool = null;
            try {
                CaltopoMap.Shutdown();
            } catch (Exception e) {
                CTError(TAG, "CaltopoMap.Shutdown() raised: ", e);
            }
            if (null != UiUpdatePoll) {
                UiUpdatePoll.stop();
                CTDebug(TAG, "Shutdown(): UiUpdatePoll suspended.");
            }
            AppIdleDelay.stop();
            Context context = R2CApplication.getAppCtxt();
            if (context != null) AppIdleAlarmReceiver.cancel(context);
            CloseDebugOutputStreamForShutdown(shutdownDebugLogGeneration);
        } finally {
            synchronized (ShutdownLock) {
                ShutdownInProgress = false;
            }
        }
    }

    public static void MarkAppActive() {
        MarkAppActive(false);
    }

    public static void MarkAppActive(boolean activityRecreated) {
        long nowMsec = System.currentTimeMillis();
        synchronized (ShutdownLock) {
            AppExitRequested = false;
        }
        synchronized (DebugLogLock) {
            DebugLogGeneration++;
        }
        AppActiveStartedAtMsec = ApplicationIdleTimeoutPolicy.sessionStartedAtMsec(
                AppActiveStartedAtMsec,
                activityRecreated,
                nowMsec);
        RemoveExpiredCaltopoProfiles(nowMsec, true);
    }

    public static boolean IsExitRequested() {
        synchronized (ShutdownLock) {
            return AppExitRequested;
        }
    }


    /* Maybe a better name would be MaxIdleTimeInSeconds.   If the delay between
     * waypoints exceeds this value, the track is terminated and a new track is
     * started.
     */
    public static long SetNewTrackDelayInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();

        if (delayInSeconds < MIN_NEW_TRACK_DELAY_IN_SECONDS) {
            delayInSeconds = MIN_NEW_TRACK_DELAY_IN_SECONDS;
        }

        if (ccs.newTrackDelayInSeconds != delayInSeconds) {
            ccs.newTrackDelayInSeconds = delayInSeconds;
            ArchiveState("newTrackDelayInSeconds changed");
            UpdateDroneSpecs();
        }
        return ccs.newTrackDelayInSeconds;
    }

    public static long SetMaxFlatlineToneDurationInSeconds(long durationInSeconds) {
        ClientClassState ccs = GetState();

        if (durationInSeconds < 1L) {
            durationInSeconds = 1L;
        }

        if (ccs.maxFlatlineToneDurationInSeconds != durationInSeconds) {
            ccs.maxFlatlineToneDurationInSeconds = durationInSeconds;
            ArchiveState("maxFlatlineToneDurationInSeconds changed");
            UpdateDroneSpecs();
        }
        return ccs.maxFlatlineToneDurationInSeconds;
    }

    public static long SetBridgeCheckDistanceFeet(long feet) {
        ClientClassState ccs = GetState();

        if (feet < 1L) {
            feet = 1L;
        }

        if (ccs.bridgeCheckDistanceFeet != feet) {
            ccs.bridgeCheckDistanceFeet = feet;
            ArchiveState("bridgeCheckDistanceFeet changed");
            UpdateDroneSpecs();
        }
        return ccs.bridgeCheckDistanceFeet;
    }

    public static int SetAlarmVolumePercent(int percent) {
        ClientClassState ccs = GetState();
        int normalized = Math.max(0, Math.min(100, percent));

        if (ccs.alarmVolumePercent != normalized || !ccs.alarmVolumeConfigured) {
            ccs.alarmVolumePercent = normalized;
            ccs.alarmVolumeConfigured = true;
            ArchiveState("alarmVolumePercent changed");
            UpdateDroneSpecs();
        }
        return ccs.alarmVolumePercent;
    }


    /* minimum distance in feet between waypoints necessary to
     * record a new waypoint.
     */
    public static long setMinDistanceInFeet(long minDistance) {
        ClientClassState ccs = GetState();
        if (minDistance < MIN_DISTANCE_IN_FEET) {
            minDistance = MIN_DISTANCE_IN_FEET;
        }
        if (ccs.minDistanceInFeet != minDistance) {
            ccs.minDistanceInFeet = minDistance;
            ArchiveState("minDistanceInFeet changed");
        }
        return ccs.minDistanceInFeet;
    }

    /**
     * This is the maximum app idle time.
     *
     */
    public static void SetMaxIdleTimeInMinutes(long timeval) {
        ClientClassState ccs = GetState();
        if (timeval < 0) timeval = 0;
        if (timeval != ccs.maxIdleTimeInMinutes) {
            ccs.maxIdleTimeInMinutes = timeval;
            ArchiveState("maxIdleTimeInMinutes changed");
            CheckIdle();
        }
    }

    /**
     * CheckIdle()
     * Check to see if the app has received any decoded aircraft RID messages. If
     * MaxIdleTimeInMinutes() elapses without one, assume the user forgot to quit the app,
     * so try to clean-up and exit to save their battery.   If MaxIdleTimeInMinutes == 0,
     * then this check is disabled.
     */

    public static synchronized void CheckIdle() {
        long maxIdleInMinutes = GetMaxIdleTimeInMinutes();
        AppIdleDelay.stop();
        Context context = R2CApplication.getAppCtxt();
        if (context != null) AppIdleAlarmReceiver.cancel(context);
        if (IsExitRequested()) return;
        long appActiveStartedAtMsec = AppActiveStartedAtMsec;
        long lastRidMessageMsec = CtDroneSpec.LastRidMessageTimestampMsec();
        long lastProtectedActivityMsec = MapOfflinePrepRuntime.lastActivityAtMsec();
        long nowMsec = System.currentTimeMillis();
        if (MapOfflinePrepRuntime.isActive()) {
            Log.i(TAG, "CheckIdle(): offline map preparation is active; deferring automatic shutdown.");
            return;
        }
        long remainingMsec = ApplicationIdleTimeoutPolicy.remainingDelayMsec(
                appActiveStartedAtMsec,
                lastRidMessageMsec,
                lastProtectedActivityMsec,
                maxIdleInMinutes,
                nowMsec);
        if (remainingMsec == ApplicationIdleTimeoutPolicy.DISABLED) return;
        if (remainingMsec > 0) {
            AppIdleDelay.start(CaltopoClient::CheckIdle, remainingMsec, 0);
            if (context != null) AppIdleAlarmReceiver.schedule(context, remainingMsec);
            return;
        }
        long idleBaselineMsec = Math.max(
                appActiveStartedAtMsec,
                Math.max(lastRidMessageMsec, lastProtectedActivityMsec));
        long idleInMsec = Math.max(0L, nowMsec - idleBaselineMsec);
        String exitMessage = String.format(Locale.US,
                "CheckIdle(): app idle timeout expired after %.3f/%.3f minutes without RID messages or protected activity (appActiveStarted=%s lastRidMessage=%s). Shutting down app and map to save battery.",
                idleInMsec / 60000.0,
                (double) maxIdleInMinutes,
                TimeDatestampString(appActiveStartedAtMsec),
                TimeDatestampString(lastRidMessageMsec));
        Log.w(TAG, exitMessage);
        QuitApplication();
        CTEventAsync(TAG, "MaxIdleExiting", null);
        Thread warningThread = new Thread(
                () -> CTWarn(TAG, exitMessage),
                "R2C-IdleExitLog");
        warningThread.start();
    }

    public static long GetMaxIdleTimeInMinutes() {
        ClientClassState ccs = GetState();
        return ccs.maxIdleTimeInMinutes;
    }

    public static String TimeDatestampString(long epochMsec) {
        // Yes, we really want the timestamp first to make it easier to spot
        // the latest track in caltopo's tiny feature window.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HHmmssLLLdd");
        Instant instant = Instant.ofEpochMilli(epochMsec);
        LocalDateTime localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
        return localDateTime.format(formatter);
    }

    // CaltopoClient instance methods:

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "  rid:%s, mapped:%s", remoteId, droneSpec.getMappedId());
    }

    private void terminateTrack(String msg, boolean updateDroneSpecs) {
        if (droneSpec.isActive()) {
            String trackLabel = droneSpec.trackLabel();
            CTDebug(TAG, String.format(Locale.US, "terminateTrack(): archiving '%s': %s", trackLabel, msg));
            WaypointTrack.ArchiveTrack(trackLabel);
            if (null != liveTrack) {
                CTDebug(TAG, msg);
                liveTrack.finishTrack(msg);
            } else {
                CaltopoLiveTrack.NotifyLocalTrackFinished(droneSpec, msg);
            }
            droneSpec.reset();
            idleTimeoutPoll.stop();
        } else {
            CTDebug(TAG, "terminateTrack(): Ignoring inactive track.");
        }
        if (updateDroneSpecs) {
            UpdateDroneSpecs();
        }
    }

    // Unfortunately, older versions of Android don't handle IPv4/IPv6 very well,
    // so we're forced to use OkHttpClient in place of the legacy HttpURLConnection
    // to encourage use of IPv4, which is all we ever wanted in the first place...
    public static int BgPublishGeoJsonStats(String geoJsonString) {
        if (IsExitRequested()) {
            return 499;
        }
        String trackerApiKey = GetTrackerUploadApiKey();
        String trackerUrlPfx = GetTrackerUploadUrlPfx();
        if (trackerApiKey.isEmpty() || trackerUrlPfx.isEmpty()) return 8675309;

        if (CTDebugEnabled(TAG)) {
            CTDebug(TAG, "BgPublishGeoJsonStats() tracker selection: " + DescribeTrackerCredentialSelection("upload"));
        }

        String urlStr = String.format(Locale.US, "%s/%s", trackerUrlPfx, "upload");
        long startStamp = System.currentTimeMillis();

        // Define the Media Type
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // Create the Request Body
        RequestBody body = RequestBody.create(geoJsonString, JSON);

        // Build the Request
        Request request = new Request.Builder()
                .url(urlStr)
                .put(body) // This sets the method to PUT
                .header("User-Agent", "RID2Caltopo/0.1")
                .header("X-SAR-Token", trackerApiKey)
                .header("X-R2C-Functionality-Release",
                        Integer.toString(BuildConfig.TRACKER_FUNCTIONALITY_RELEASE))
                .build();

        if (CTDebugEnabled(TAG)) CTDebug(TAG, "BgPublishStats(): uploading " + geoJsonString.length() + " characters to '" + urlStr + "'");

        try (Response response = CaltopoSession.MyOkHttpClient.newCall(request).execute()) {
            int responseCode = response.code();
            long endStamp = System.currentTimeMillis();

            StringBuilder responseLog = new StringBuilder();
            responseLog.append(String.format(Locale.US,
                    "BgPublishStats(%s) completed in %.3f seconds with code %d.\n",
                    trackerUrlPfx, (endStamp - startStamp) / 1000.0, responseCode));

            // Read the body (Response.body().string() handles stream closing)
            String bodyString = response.body() != null ? response.body().string() : "";
            responseLog.append(bodyString);

            if (CTDebugEnabled(TAG)) CTDebug(TAG, responseLog.toString());
            return responseCode;

        } catch (IOException e) {
            CTError(TAG, "BgPublishStats() raised IOException (Network/DNS issue):", e);
            return 503; // Service Unavailable / Custom error code
        }
    }

    public static Future<Integer> PublishGeoJsonStats(@NonNull String geoJsonString) {
        if (IsExitRequested()) {
            return CompletableFuture.completedFuture(499);
        }
        return GetGeoJsonStatsExecutorPool().submit(() -> BgPublishGeoJsonStats(geoJsonString));
    }

    public static Future<Integer> PublishGeoJsonStats(
            @NonNull TrackerPublisher trackerPublisher,
            @NonNull String geoJsonString) {
        if (IsExitRequested()) {
            return CompletableFuture.completedFuture(499);
        }
        return GetGeoJsonStatsExecutorPool().submit(() -> trackerPublisher.publishGeoJson(geoJsonString));
    }

    @NonNull
    private static synchronized ExecutorService GetLivePublishExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (LivePublishExecutorPool == null || LivePublishExecutorPool.isShutdown() || LivePublishExecutorPool.isTerminated()) {
            LivePublishExecutorPool = Executors.newFixedThreadPool(LivePublishThreadPoolSize);
        }
        return LivePublishExecutorPool;
    }

    @NonNull
    private static synchronized ExecutorService GetGeoJsonStatsExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (GeoJsonStatsExecutorPool == null || GeoJsonStatsExecutorPool.isShutdown() || GeoJsonStatsExecutorPool.isTerminated()) {
            GeoJsonStatsExecutorPool = Executors.newFixedThreadPool(GeoJsonStatsThreadPoolSize);
        }
        return GeoJsonStatsExecutorPool;
    }

    @NonNull
    private static synchronized ExecutorService GetArchiveScanExecutorPool() {
        if (IsExitRequested()) {
            throw new RejectedExecutionException("App exit in progress");
        }
        if (ArchiveScanExecutorPool == null || ArchiveScanExecutorPool.isShutdown() || ArchiveScanExecutorPool.isTerminated()) {
            ArchiveScanExecutorPool = Executors.newFixedThreadPool(ArchiveScanThreadPoolSize);
        }
        return ArchiveScanExecutorPool;
    }

    private static void ShutdownExecutorPool(@Nullable ExecutorService pool, boolean immediate) {
        if (pool == null) return;
        if (immediate) {
            pool.shutdownNow();
        } else {
            pool.shutdown();
        }
    }


    /**
     * newWaypoint() - process a new waypoint from OpenDroneIdDataManager().
     * Note that lat, lng, altitudeInMeters, and droneTimestampInSeconds are all values
     * provided by the drone's remote id module and quality of measurement is going to
     * vary from one source to the next.  Do a basic sanity check on anything before
     * relying on it.
     */
    public boolean newWaypoint(double lat, double lng, double altitudeInMeters, long droneTimestampInMilliseconds,
                               CtDroneSpec.TransportTypeEnum transportType, @Nullable Boolean airborne) {
        long totalStartedAtMs = System.currentTimeMillis();
        long longAltitudeInMeters = Math.round(altitudeInMeters);
        ArrayList<CtDroneSpec> proximityDrones = new ArrayList<>(GetState().droneSpecTable.values());
        if (SessionUnknownDroneRemoteIds.contains(remoteId)) {
            droneSpec.setLocalArchiveOnly(true);
        }

        if (shouldSuppressMapTracking(droneSpec)) {
            droneSpec.setLocalArchiveOnly(true);
            long archiveStartedAtMs = System.currentTimeMillis();
            WaypointTrack.AddWaypointForTrack(droneSpec, lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
            logWaypointSideEffectIfSlow("WaypointTrack.AddWaypointForTrack.suppressed", droneSpec,
                    System.currentTimeMillis() - archiveStartedAtMs);
            long localTrackNotifyStartedAtMs = System.currentTimeMillis();
            CaltopoLiveTrack.NotifyLocalTrackPoint(droneSpec, lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
            logWaypointSideEffectIfSlow("CaltopoLiveTrack.NotifyLocalTrackPoint.suppressed", droneSpec,
                    System.currentTimeMillis() - localTrackNotifyStartedAtMs);
            long proximityStartedAtMs = System.currentTimeMillis();
            ProximityAlertCenter.INSTANCE.updateDrones(proximityDrones);
            logWaypointSideEffectIfSlow("proximity.updateDrones.suppressed", droneSpec,
                    System.currentTimeMillis() - proximityStartedAtMs);
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, String.format(Locale.US,
                        "newWaypoint(): map-suppressed non-team drone remoteId=%s mappedId=%s",
                        droneSpec.getRemoteId(), droneSpec.getMappedId()));
            }
            logWaypointSideEffectIfSlow("total.suppressed", droneSpec,
                    System.currentTimeMillis() - totalStartedAtMs);
            return true;
        }

        long archiveStartedAtMs = System.currentTimeMillis();
        WaypointTrack.AddWaypointForTrack(droneSpec, lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
        logWaypointSideEffectIfSlow("WaypointTrack.AddWaypointForTrack", droneSpec,
                System.currentTimeMillis() - archiveStartedAtMs);

        long proximityStartedAtMs = System.currentTimeMillis();
        ProximityAlertCenter.INSTANCE.updateDrones(proximityDrones);
        logWaypointSideEffectIfSlow("proximity.updateDrones", droneSpec,
                System.currentTimeMillis() - proximityStartedAtMs);

        long mapStatusStartedAtMs = System.currentTimeMillis();
        CaltopoMap.MapStatusListener.mapStatus mapStatus = CaltopoMap.GetMapStatus();
        logWaypointSideEffectIfSlow("CaltopoMap.GetMapStatus", droneSpec,
                System.currentTimeMillis() - mapStatusStartedAtMs);
        if (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up) {
            long liveTrackStartedAtMs = System.currentTimeMillis();
            if (null == liveTrack) {
                if (CTDebugEnabled(TAG)) CTDebug(TAG, "newWaypoint(): starting new liveTrack for: " + droneSpec.trackLabel());
                liveTrack = new CaltopoLiveTrack(droneSpec, lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
            } else if (liveTrack.isActive()) {
                liveTrack.publishDirect(lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
            } else {
                if (CTDebugEnabled(TAG)) CTDebug(TAG, "newWaypoint(): restarting liveTrack: " + droneSpec.trackLabel());
                liveTrack.startNewTrack(lat, lng, longAltitudeInMeters, droneTimestampInMilliseconds);
            }
            logWaypointSideEffectIfSlow("liveTrack.up", droneSpec,
                    System.currentTimeMillis() - liveTrackStartedAtMs);

            // Idle-track termination is handled centrally by ProcessSortedCurrentDroneSpecArray().
        } else {
            // Keep local map motion responsive even when the Teams/Caltopo live-track path is unavailable.
            long notifyStartedAtMs = System.currentTimeMillis();
            CaltopoLiveTrack.NotifyLocalTrackPoint(droneSpec, lat, lng, altitudeInMeters, droneTimestampInMilliseconds);
            logWaypointSideEffectIfSlow("NotifyLocalTrackPoint.mapDown", droneSpec,
                    System.currentTimeMillis() - notifyStartedAtMs);
        }
        logWaypointSideEffectIfSlow("total", droneSpec, System.currentTimeMillis() - totalStartedAtMs);
        return true;
    }
}
