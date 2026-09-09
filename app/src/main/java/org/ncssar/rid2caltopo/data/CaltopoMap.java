/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;
import static org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast;
import static org.ncssar.rid2caltopo.data.CaltopoMapHierarchyKt.parseMapHierarchy;
import static org.ncssar.rid2caltopo.data.SimpleTimer.DurationAsString;

import org.ncssar.rid2caltopo.app.R2CActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime;


import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** CaltopoMap class
 *   Support bringing up the map session and creating the drone track folders (if not
 * already present).
 * The archive folder starts with the drone track folder name and ends with the
 * current date (i.e. 25Sep).
 *   Additionally, if there are any peer R2C Instances present on the map, then
 * attempt to bring up their corresponding R2CPeers before indicating the
 * map is up.
 * FIXME:  Are there going to be scenarios where we want to skip the R2CPeer
 *     connectivity?  Maybe a slider in the settings panel that says "ignore
 *     peers" or some such and just deal with the duplicated track points...
 */
public class CaltopoMap {
    interface TimeSource {
        long now();
    }

    interface InactivityDisconnectHandler {
        void disconnect();
    }

    private static final class RelocationAnchor {
        final double latitude;
        final double longitude;
        final float accuracyMeters;

        RelocationAnchor(double latitude, double longitude, float accuracyMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
        }
    }

    public interface MapStatusListener {
        enum mapStatus {
            down,
            credentialsVerified,
            connecting,
            up
        }

        /** mapStatusUpdate():
         *
         * @param status  Sent any time map status changes.
         * @param mapNode The map who's status has changed - if known.
         * @param optErrmsg If map processing encountered an error this may qualify the status.
         */
        void mapStatusUpdate(mapStatus status, @Nullable CaltopoNode.MapNode mapNode,  @Nullable String optErrmsg);
    }

    public interface ArtifactListener {
        void onArtifactFeature(
                @NonNull JSONObject feature,
                @NonNull String source,
                long receivedAtMsec
        );
    }
    private static final String TAG = "CaltopoMap";
    private static CaltopoSession Csp;
    private static String MyUUID = null;
    public static android.location.Location MyLocation;
    private static android.location.Location MyLocationOverride;
    private static long FirstMapUpdateTimeInSeconds = 15;
    private static long RepeatMapUpdateTimeInSeconds = 90;
    private static final long FOREGROUND_MAP_UPDATE_TIME_IN_SECONDS = 15;
    private static final long FULL_ARTIFACT_RECONCILE_INTERVAL_MS = 15L * 60L * 1000L;
    public static final CtLineProperty ArchiveLineProp =
            new CtLineProperty(2, 0.5F, "#ff00ff", "solid");
    private static final int MAX_MAP_STARTUP_DELAY_IN_SECONDS = 45;
    private static final String LOCAL_DEVICE_MARKER_SYMBOL = "radiotower";
    private static final String LOCAL_DEVICE_MARKER_TITLE_PREFIX = "R2C: ";
    private static final String LOCAL_DEVICE_MARKER_GUID_PROP = "r2c-guid";
    private static final String LOCAL_DEVICE_MARKER_NAME_PROP = "r2c-name";
    private static final String LOCAL_DEVICE_MARKER_LAST_SEEN_PROP = "r2c-last-seen-epoch-ms";
    private static final String LOCAL_DEVICE_COLOR_STARTING = "#F9A825";
    private static final String LOCAL_DEVICE_COLOR_HEALTHY = "#2E7D32";
    private static final String LOCAL_DEVICE_COLOR_DEGRADED = "#F9A825";
    private static final String LOCAL_DEVICE_COLOR_UNCONFIGURED = "#1976D2";
    private static final long INITIAL_MARKER_POLL_MS = 500L;
    private static final long INITIAL_MARKER_WAIT_MS = 8_000L;
    private static final long STALE_DEVICE_MARKER_GRACE_MS = 15_000L;
    private static final long MARKER_DELETE_WAIT_MS = 4_000L;
    private static final double AUTO_QUIT_RELOCATION_DISTANCE_METERS = 50.0 * 0.3048;
    private static final float AUTO_QUIT_REQUIRED_ACCURACY_METERS = (float) (25.0 * 0.3048);
    private static final long AUTO_QUIT_FLIGHT_QUIET_MS = 5L * 60L * 1000L;
    private static final long TRACKER_SILENCE_STANDBY_MS = 60L * 1000L;
    @NonNull private static volatile TimeSource timeSource = System::currentTimeMillis;
    @NonNull private static volatile InactivityDisconnectHandler inactivityDisconnectHandler =
            () -> DisconnectIncidentMapIfInactive("relocation");

    // my peers by GUID (MQTT-based):
    private static final Hashtable<String, R2CMqttManager.PeerState> PeerIdMap = new Hashtable<>(16);

    // poll the map to see if there are any new r2c peers or user has changed one of our track labels
    private static final DelayedExec MapCheckerDelay = new DelayedExec();
    private static final DelayedExec InitialMarkerPublishDelay = new DelayedExec();
    private static String FolderId;
    private static String ArchiveFolderId;
    private static CaltopoNode.MapNode MapNode;
    private static String FolderName;
    private static MapStatusListener.mapStatus MapStatus = MapStatusListener.mapStatus.down;
    private static String LastStandaloneCoordinationScopeId = "";
    private static boolean StandaloneCoordinationStarted = false;
    @Nullable private static Boolean StandaloneCoordinationEnabledForActiveFlights = null;
    private static int WaitForGpsAccuracy;
    // NOTE: UsePeersFlag was removed as a static field.
    // MQTT peer coordination is now unconditional whenever the map is up.
    // A developer-only "Disable MQTT" toggle in Settings can suppress it via
    // CaltopoClient.GetUsePeersFlag() — that flag is read live, not cached.
    private static long LastMapSync;
    private static final Object MapRefreshLock = new Object();
    private static boolean MapRefreshInFlight;
    private static boolean MapRefreshPending;
    private static boolean FullMapRefreshPending;
    private static int MapArtifactForegroundConsumers;
    private static long LastFullArtifactSync;
    private static long MapRefreshGeneration;
    private static final ArrayList<Runnable> FullMapRefreshCallbacks = new ArrayList<>();

    // liveTracks that we are writing into the map keyed by their map ID:
    private static final Hashtable<String, CaltopoLiveTrack> LiveTracksById = new Hashtable<>();

    // All liveTracks, including those not yet/ever writing to the map:
    private static final ArrayList<CaltopoLiveTrack> liveTracks = new ArrayList<>(16);

    private static final ArrayList<JSONObject> RogueFeaturesPendingDeletes = new ArrayList<>();

    private static final HashSet<MapStatusListener> MapListeners = new HashSet<>();
    private static final HashSet<ArtifactListener> ArtifactListeners = new HashSet<>();
    private static final Object ArtifactLock = new Object();
    private static final Hashtable<String, JSONObject> ArtifactFeaturesById = new Hashtable<>();
    private static final HashSet<String> DuplicateLiveTrackArchivePending = new HashSet<>();

    private static JSONArray MyLiveTracksInThisMap;   // Actual 'LiveTrack' objects in the current map
    private static String LastErrorString = null;
    private static CaltopoCredentials MyCaltopoCredentials;
    private static String DomainAndPort;
    @Nullable private static String ResolvedMyDeviceMarkerId = null;
    @Nullable private static String LastPublishedMyDeviceMarkerColor = null;
    @Nullable private static String LastPublishedMyDeviceMarkerDescription = null;
    private static List<CaltopoNode> SessionNodeMap = null;
    private static CaltopoMap MyInstance = null; // keep around just to serve as listener.
    private static DelayedExec VerifyTimer = new DelayedExec();
    private static DelayedExec VerifyPhotoTimeout = new DelayedExec();
    private static final DelayedExec ProfileExpiryPoll = new DelayedExec();
    private static final long VERIFY_TIMEOUT_MS = 10 * 1000L;
    private static final long PROFILE_EXPIRY_POLL_MS = 10 * 1000L;
    private static volatile boolean ShutdownInProgress = false;
    private static volatile boolean DisconnectInProgress = false;
    @Nullable private static String DeferredExpiredProfileId = null;
    private static volatile boolean InitialMarkerPublishPending = false;
    private static volatile long InitialMarkerWaitStartedMs = 0L;
    @NonNull private static R2cRuntime CurrentRuntime = R2cRuntimeRegistry.getDefaultRuntime();
    @Nullable private static RelocationAnchor AutoQuitRelocationAnchor;
    private static volatile boolean IncidentDisplayInactive = false;
    public static List<CaltopoNode>GetSessionNodeMap() { return SessionNodeMap;}

    static void setTimeSourceForTesting(@NonNull TimeSource testTimeSource) {
        timeSource = testTimeSource;
    }

    static void setQuitHandlerForTesting(@NonNull InactivityDisconnectHandler testDisconnectHandler) {
        inactivityDisconnectHandler = testDisconnectHandler;
    }

    static void resetAutoQuitRelocationForTesting() {
        AutoQuitRelocationAnchor = null;
        timeSource = System::currentTimeMillis;
        inactivityDisconnectHandler = () -> DisconnectIncidentMapIfInactive("relocation");
        IncidentDisplayInactive = false;
    }

    static boolean hasAutoQuitRelocationAnchorForTesting() {
        return AutoQuitRelocationAnchor != null;
    }

    static void evaluateAutoQuitAfterRelocationForTesting(
            double latitude,
            double longitude,
            float accuracyMeters
    ) {
        evaluateAutoQuitAfterRelocation(latitude, longitude, accuracyMeters, true);
    }

    static boolean isAutoQuitAfterRelocationEligibleForTesting(float accuracyMeters, long nowMs) {
        return isAutoQuitAfterRelocationEligible(true, accuracyMeters, nowMs);
    }

    @NonNull
    private static R2cRuntime getCurrentRuntime() {
        return CurrentRuntime;
    }

    private static void recordCaltopoSessionRtt(@Nullable CaltopoOp op, @NonNull String source) {
        if (op == null || op.fail()) return;
        long rttMs = op.roundTripTimeInMsec();
        if (rttMs <= 0L) return;
        CTDebug(TAG, String.format(Locale.US,
                "recordCaltopoSessionRtt(%s): rttMs=%d", source, rttMs));
        getCurrentRuntime().getPeerCoordinator().updateCaltopoRtt(rttMs);
    }

    public static void SessionVerifyCallback(@NonNull CaltopoOp verifyOp) {
        VerifyTimer.stop();
        if (verifyOp.success()) {
            recordCaltopoSessionRtt(verifyOp, "verify");
            CTDebug(TAG, String.format(Locale.US,
                    "SessionVerifyCallback(): Parsing team data. queued:%d, sent:%d, received:%d",
                    verifyOp.queuedTimestampMsec, verifyOp.sentTimestampMsec, verifyOp.receivedTimestampMsec ));
            if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
                DumpJsonStructure(verifyOp.responseJson, "account");
            }
            SessionNodeMap = parseMapHierarchy(verifyOp.responseJson);
            CTDebug(TAG, String.format(Locale.US,
                    "parseMapHierarchy() returned list with %d top-level items", SessionNodeMap.size()));
            SetMapStatus(MapStatusListener.mapStatus.credentialsVerified, null);
        } else {
            String emsg = String.format(Locale.US,
                    "Not able to verify credentials - code:%s, reason:%s",
                    verifyOp.responseCode, verifyOp.response);
            LastErrorString = emsg;
            ShowToast(emsg);
            SetMapStatus(MapStatusListener.mapStatus.down, emsg);
            SessionNodeMap = null;
        }
    }

    private static void pollActiveProfileExpiry() {
        if (ShutdownInProgress) return;
        if (MapStatus != MapStatusListener.mapStatus.up) return;
        CaltopoProfileRecord activeProfile = CaltopoClient.GetActiveCaltopoProfile();
        long nowMs = System.currentTimeMillis();
        if (activeProfile == null || !CaltopoClient.HasExpired(activeProfile, nowMs)) {
            DeferredExpiredProfileId = null;
            return;
        }
        int activeFlightCount = CaltopoClient.GetActiveFlightCount();
        if (activeFlightCount > 0) {
            if (activeProfile.profileId != null && !activeProfile.profileId.equals(DeferredExpiredProfileId)) {
                DeferredExpiredProfileId = activeProfile.profileId;
                CTWarn(TAG, String.format(Locale.US,
                        "pollActiveProfileExpiry(): active profile '%s' expired; deferring disconnect until %d active flight(s) finish.",
                        activeProfile.displayName, activeFlightCount));
                ShowToast(String.format(Locale.US,
                        "Mutual aid access expired; waiting for %d active flight(s) to finish before disconnecting.",
                        activeFlightCount));
            }
            return;
        }
        DeferredExpiredProfileId = null;
        CTWarn(TAG, String.format(Locale.US,
                "pollActiveProfileExpiry(): active profile '%s' expired with no active flights; disconnecting.",
                activeProfile.displayName));
        CaltopoClient.RemoveExpiredCaltopoProfiles(nowMs, true);
    }

    private static void scheduleInitialDeviceMarkerPublish() {
        if (DisconnectInProgress) return;
        InitialMarkerWaitStartedMs = System.currentTimeMillis();
        InitialMarkerPublishPending = true;
        if (MyLocation != null && MapNode != null && FolderId != null && !FolderId.isEmpty()) {
            publishMyDeviceMarkerIfPossible(MyLocation);
        }
        InitialMarkerPublishDelay.start(CaltopoMap::attemptInitialDeviceMarkerPublish, 0L, INITIAL_MARKER_POLL_MS);
    }

    private static void attemptInitialDeviceMarkerPublish() {
        if (!InitialMarkerPublishPending || ShutdownInProgress || DisconnectInProgress ||
                MapStatus != MapStatusListener.mapStatus.up) {
            InitialMarkerPublishDelay.stop();
            return;
        }
        Location location = MyLocation;
        if (location == null || MapNode == null || FolderId == null || FolderId.isEmpty()) {
            return;
        }
        PeerCoordinator.CoordinationIndicatorState state =
                getCurrentRuntime().getPeerCoordinator().getCoordinationIndicatorState();
        boolean healthy = state == PeerCoordinator.CoordinationIndicatorState.HEALTHY;
        boolean timedOut = (System.currentTimeMillis() - InitialMarkerWaitStartedMs) >= INITIAL_MARKER_WAIT_MS;
        if (!healthy && !timedOut) {
            return;
        }
        publishMyDeviceMarkerIfPossible(location);
        InitialMarkerPublishPending = false;
        InitialMarkerPublishDelay.stop();
        if (!healthy) {
            CTDebug(TAG, "attemptInitialDeviceMarkerPublish(): coordination still degraded after wait; published current marker color.");
        }
    }

    private static void refreshDeviceMarkerIfNeeded() {
        if (ShutdownInProgress || DisconnectInProgress ||
                MapStatus != MapStatusListener.mapStatus.up || MapNode == null || MyLocation == null) {
            return;
        }
        String desiredColor = getLocalDeviceMarkerColor();
        String desiredDescription = buildMyDeviceMarkerDescription();
        if (desiredColor.equals(LastPublishedMyDeviceMarkerColor) &&
                desiredDescription.equals(LastPublishedMyDeviceMarkerDescription)) return;
        publishMyDeviceMarkerIfPossible(MyLocation);
    }

    @NonNull
    static String buildMyDeviceMarkerDescription() {
        PeerCoordinator.CoordinationIndicatorState state =
                getCurrentRuntime().getPeerCoordinator().getCoordinationIndicatorState();
        return TrackerTabletLink.markerDescription(
                CaltopoClient.GetTrackerCoordinationUrlPfx(),
                R2CActivity.MyDeviceName,
                state == PeerCoordinator.CoordinationIndicatorState.HEALTHY
        );
    }

    private static void onCoordinationIndicatorStateChanged(@NonNull PeerCoordinator.CoordinationIndicatorState state) {
        if (ShutdownInProgress || DisconnectInProgress || MapStatus != MapStatusListener.mapStatus.up) return;
        CTDebug(TAG, "onCoordinationIndicatorStateChanged(): " + state);
        refreshDeviceMarkerIfNeeded();
    }

    private static long getDeviceMarkerStaleAfterMs() {
        return Math.max(RepeatMapUpdateTimeInSeconds * 2L * 1000L, STALE_DEVICE_MARKER_GRACE_MS);
    }

    private static long getMarkerLastSeenEpochMs(@Nullable JSONObject properties) {
        if (properties == null) return 0L;
        Object rawValue = properties.opt(LOCAL_DEVICE_MARKER_LAST_SEEN_PROP);
        if (rawValue instanceof Number) {
            return ((Number) rawValue).longValue();
        }
        if (rawValue instanceof String) {
            try {
                return Long.parseLong((String) rawValue);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean isManagedR2cMarker(
            @Nullable JSONObject properties,
            @NonNull String expectedTitlePrefix
    ) {
        if (properties == null) return false;
        if (!"Marker".equals(properties.optString("class", ""))) return false;
        if (properties.has(LOCAL_DEVICE_MARKER_GUID_PROP)) return true;
        return properties.optString("title", "").startsWith(expectedTitlePrefix);
    }

    /***
     *
     * @return non-null diagnostic string immediately if not able to proceed, otherwise
     *         kicks off asynchronous connection verification process and returns null.
     */
    @Nullable
    public static String Init() {
        CTDebug(TAG, "Init()");
        ShutdownInProgress = false;
        CurrentRuntime = R2cRuntimeRegistry.getDefaultRuntime();
        CaltopoClient.RemoveExpiredCaltopoProfiles(System.currentTimeMillis(), true);
        if (null == MyInstance)
            MyInstance = new CaltopoMap(); // needed to receive notifications only.

        CaltopoProfileRecord activeProfile = CaltopoClient.GetActiveCaltopoProfile();
        if (activeProfile != null && CaltopoClient.HasExpired(activeProfile, System.currentTimeMillis())) {
            return "Mutual aid access expired";
        }
        CaltopoCredentials sessionCredentials = CaltopoClient.GetCaltopoCredentials();
        if (!CaltopoCredentials.sniffTest(sessionCredentials)) {
            return "Missing required credentials";
        }
        DomainAndPort = CaltopoClient.GetCaltopoDomainAndPort();
        if (null == DomainAndPort || DomainAndPort.isEmpty()) {
            DomainAndPort = "caltopo.com";
        }

        MyCaltopoCredentials = sessionCredentials;
        SessionNodeMap = null;
        CTDebug(TAG, "Init(): Initializing session...");
        getCurrentRuntime().getCalTopoSessionGateway()
                .init(MyCaltopoCredentials, DomainAndPort);
        CTDebug(TAG, "Init(): Verifying Credentials...");
        VerifyTimer.start(CaltopoMap::VerifyTimeout, VERIFY_TIMEOUT_MS, 0);
        getCurrentRuntime().getCalTopoSessionGateway()
                .verifyAccount(CaltopoMap::SessionVerifyCallback);
        // UsePeersFlag removed — MQTT starts unconditionally with the map (see SetMapStatus).
        FolderName = CaltopoClient.GetTrackFolderName();
        if (FolderName.isEmpty()) FolderName = "DroneTracks";
        if (null == MyUUID) GetMyUUID();
        return null;
    }
    private static void VerifyTimeout() {
        LastErrorString = "Timeout waiting for Caltopo response... Is the network down?";
        SetMapStatus(MapStatusListener.mapStatus.down, LastErrorString);
    }

    CaltopoMap() {
        if (null != MyInstance) {
            throw new RuntimeException("CaltopoMap instances not supported");
        }
        MyInstance = this;
    }

    public static CaltopoNode.MapNode GetMapNode() {
        return MapNode;
    }

    public static void LogJsonStructure(String keyName, Object value, int depth) {
        String indent = new String(new char[depth * 2]).replace("\0", "  ");

        if (value instanceof JSONObject obj) {
            // Log that we are entering an object
            CTInfo(TAG, indent + "OBJ: " + keyName);

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);
                LogJsonStructure(key, child, depth + 1);
            }
        }
        else if (value instanceof JSONArray array) {
            CTInfo(TAG, indent + "ARR: " + keyName + " [Len: " + array.length() + "]");

            if (array.length() > 0) {
                Object firstChild = array.opt(0);
                // We only recurse into the first element to see the "shape" of the array items
                // We use "[0]" to indicate we are looking at the structure of an array member
                LogJsonStructure(keyName + "[0]", firstChild, depth + 1);
            }
        }
        else if (value != null) {
            // This is a leaf node (String, Long, Boolean, etc.)
            CTInfo(TAG, indent + "VAL: " + keyName + " (" + value.getClass().getSimpleName() + " = " + value.toString() + ")");
        }
    }

    public static void DumpJsonStructure(JSONObject jo, String name) {
        CTInfo(TAG, "--- start " + name + " structure graph");
        LogJsonStructure("ROOT", jo, 0);
        CTInfo(TAG, "--- end " + name + " structure graph");
    }

    /***
     *
     * @param mapNode  : If null, just reset map connection.
     */
    public static void OpenMap(CaltopoNode.MapNode mapNode) {
        if (ShutdownInProgress) {
            CTDebug(TAG, "OpenMap(): ignoring request while shutdown in progress.");
            return;
        }
        DisconnectInProgress = false;
        if (mapNode == null) {
            CTDebug(TAG, "OpenMap(): Map connection reset.");
            ResetMapConnection(4_000L);
            MapNode = null;
            SetMapStatus(MapStatusListener.mapStatus.down, "Disconnect request.");
            return;
        }
        if (MapNode != null) {
            // don't wait around for reset operations to complete:
            ResetMapConnection(0);
            SetMapStatus(MapStatusListener.mapStatus.down, "Map switch request.");
        }
        MapNode = mapNode;
        DisconnectInProgress = false;

        SetMapStatus(MapStatusListener.mapStatus.connecting, null);
        try {
            CTDebug(TAG, String.format(Locale.US, "Connecting to map '%s'(%s)'", MapNode.getTitle(), MapNode.getId()));
            long requestStartedAtMs = timeSource.now();
            long refreshGeneration;
            synchronized (MapRefreshLock) {
                refreshGeneration = MapRefreshGeneration;
            }
            getCurrentRuntime().getCalTopoSessionGateway()
                    .openMap(MapNode, 0, op -> OpenMapFinished(op, requestStartedAtMs, refreshGeneration));

        } catch (Exception e) {
            String emsg = "OpenMap(): CaltopoSession.OpenMap() barfed";
            CTError(TAG, emsg, e);
            SetMapStatus(MapStatusListener.mapStatus.down, emsg);
        }
    }

    public static void AddMapStatusListener(@NonNull MapStatusListener listener) {
        MapListeners.add(listener);
    }

    public static void RemoveMapStatusListener(@NonNull MapStatusListener listener) {
        MapListeners.remove(listener);
    }

    public static void AddArtifactListener(@NonNull ArtifactListener listener) {
        AddArtifactListener(listener, true);
    }

    public static void AddArtifactListener(@NonNull ArtifactListener listener, boolean replayCachedArtifacts) {
        ArrayList<JSONObject> artifactSnapshot;
        synchronized (ArtifactLock) {
            ArtifactListeners.add(listener);
            artifactSnapshot = replayCachedArtifacts
                    ? new ArrayList<>(ArtifactFeaturesById.values())
                    : new ArrayList<>();
        }
        if (!artifactSnapshot.isEmpty()) {
            long now = System.currentTimeMillis();
            CTDebug(TAG, String.format(Locale.US,
                    "AddArtifactListener(): replaying %d cached artifacts to new listener.",
                    artifactSnapshot.size()));
            for (JSONObject feature : artifactSnapshot) try {
                listener.onArtifactFeature(feature, "full", now);
            } catch (Exception e) {
                CTError(TAG, "AddArtifactListener() replay listener raised", e);
            }
        }
    }

    public static void RemoveArtifactListener(@NonNull ArtifactListener listener) {
        synchronized (ArtifactLock) {
            ArtifactListeners.remove(listener);
        }
    }

    @NonNull
    public static ArrayList<JSONObject> GetArtifactFeatureSnapshot() {
        synchronized (ArtifactLock) {
            return new ArrayList<>(ArtifactFeaturesById.values());
        }
    }

    public static long GetMapUpdateInitialDelayInSeconds() {
        return FirstMapUpdateTimeInSeconds;
    }

    public static long GetMapUpdateRepeatDelayInSeconds() {
        return RepeatMapUpdateTimeInSeconds;
    }

    public static void SetMapUpdateDelayInSeconds(long initialDelay, long repeatDelay) {
        FirstMapUpdateTimeInSeconds = Math.max(5, initialDelay);
        RepeatMapUpdateTimeInSeconds = Math.max(10, repeatDelay);
        CTInfo(TAG, String.format(Locale.US,
                "SetMapUpdateDelayInSeconds(): initial=%d repeat=%d",
                FirstMapUpdateTimeInSeconds, RepeatMapUpdateTimeInSeconds));
    }

    public static void RequestMapRefreshNow() {
        if (MapNode == null) return;
        MapCheckerDelay.stop();
        PollMapUpdates();
    }

    public static void ReloadMapArtifactsNow(@Nullable Runnable onComplete) {
        if (ShutdownInProgress || MapNode == null) {
            CTDebug(TAG, "ReloadMapArtifactsNow(): skipping due to shutdown or no map.");
            return;
        }
        CTInfo(TAG, String.format(Locale.US,
                "ReloadMapArtifactsNow(): scheduling full artifact reconciliation for map '%s'.",
                GetMapId()));
        synchronized (MapRefreshLock) {
            FullMapRefreshPending = true;
            if (onComplete != null) FullMapRefreshCallbacks.add(onComplete);
        }
        RequestMapRefreshNow();
    }

    public static void SetMapArtifactForegroundVisible(boolean visible) {
        synchronized (MapRefreshLock) {
            if (visible) {
                MapArtifactForegroundConsumers++;
            } else if (MapArtifactForegroundConsumers > 0) {
                MapArtifactForegroundConsumers--;
            }
        }
        if (MapNode == null || ShutdownInProgress) return;
        MapCheckerDelay.stop();
        if (visible) {
            RequestMapRefreshNow();
        } else {
            ScheduleNextMapUpdate();
        }
    }

    public static void AddLiveTrack(@NonNull CaltopoLiveTrack track) {
        liveTracks.add(track);
    }

    public static void AddLiveTrack(@NonNull String trackId, @NonNull CaltopoLiveTrack track) {
        CTDebug(TAG, "addLiveTrack(): adding liveTrack with id: " + trackId);
        LiveTracksById.put(trackId, track);
    }
    public static void RemoveLiveTrack(@NonNull String trackId) {
        CaltopoLiveTrack liveTrack = LiveTracksById.remove(trackId);
        if (null != liveTrack) {
            CTDebug(TAG, String.format(Locale.US,
                    "removeLiveTrack(%s): removing liveTrack %s",
                    GetMapId(), liveTrack.getTrackLabel()));
        }
        DuplicateLiveTrackArchivePending.remove(trackId);
    }

    @Nullable
    private static CaltopoLiveTrack findPublishingDuplicateOwner(@Nullable String featureDeviceId,
                                                                 @Nullable String trackId) {
        if (featureDeviceId == null || featureDeviceId.isEmpty() || trackId == null || trackId.isEmpty()) {
            return null;
        }
        for (CaltopoLiveTrack liveTrack : liveTracks) {
            if (!liveTrack.publishingLocally()) continue;
            if (!liveTrack.matchesFeatureDeviceId(featureDeviceId)) continue;
            if (liveTrack.ownsLiveTrackId(trackId)) continue;
            return liveTrack;
        }
        return null;
    }

    private static boolean archiveDuplicateLiveTrackIfNeeded(@NonNull JSONObject feature,
                                                             @NonNull JSONObject prop,
                                                             @NonNull String featureClass,
                                                             @NonNull String trackId,
                                                             @NonNull String title) {
        if (!"LiveTrack".equals(featureClass) || trackId.isEmpty()) return false;
        if (!FolderId.equals(prop.optString("folderId", ""))) return false;

        String featureDeviceId = prop.optString("deviceId", "");
        CaltopoLiveTrack ownerTrack = findPublishingDuplicateOwner(featureDeviceId, trackId);
        if (ownerTrack == null) return false;
        if (!DuplicateLiveTrackArchivePending.add(trackId)) return true;

        CTWarn(TAG, String.format(Locale.US,
                "archiveDuplicateLiveTrackIfNeeded(): archiving stale duplicate '%s' id=%s deviceId=%s while '%s' is publishing locally",
                title, trackId, featureDeviceId, ownerTrack.getTrackLabel()));
        ArchiveFeature(feature, featureClass, System.currentTimeMillis(), 0);
        return true;
    }

    public static float DistanceFromMeInMeters(double lat, double lng) {
        float[] dbResult = {Float.NaN};
        Location myLocation = GetMyLocation();
        if (null == myLocation || !myLocation.hasAccuracy()) return Float.NaN;
        Location.distanceBetween(lat, lng, myLocation.getLatitude(), myLocation.getLongitude(), dbResult);
        return dbResult[0];
    }

    @Nullable
    public static synchronized android.location.Location GetMyLocation() {
        if (MyLocationOverride != null) return new Location(MyLocationOverride);
        if (MyLocation == null) return null;
        return new Location(MyLocation);
    }

    @Nullable
    public static synchronized android.location.Location GetDeviceLocation() {
        if (MyLocation == null) return null;
        return new Location(MyLocation);
    }

    @Nullable
    public static synchronized android.location.Location GetMyLocationOverride() {
        if (MyLocationOverride == null) return null;
        return new Location(MyLocationOverride);
    }

    public static synchronized void SetMyLocationOverride(@Nullable android.location.Location location) {
        if (location == null) {
            if (MyLocationOverride != null) {
                CTInfo(TAG, "SetMyLocationOverride(): cleared temporary location override.");
            }
            MyLocationOverride = null;
            return;
        }
        MyLocationOverride = new Location(location);
        if (!MyLocationOverride.hasAccuracy()) {
            MyLocationOverride.setAccuracy(1.0f);
        }
        CTInfo(TAG, String.format(Locale.US,
                "SetMyLocationOverride(): using temporary location %.7f,%.7f accuracy %.1fm",
                MyLocationOverride.getLatitude(), MyLocationOverride.getLongitude(), MyLocationOverride.getAccuracy()));
    }

    @NonNull
    public static String GetMyUUID() {
        if (null != MyUUID) return MyUUID;
        Context ctxt = R2CApplication.getAppCtxt();
        if (null == ctxt) {
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::GetMyUUID, 1000);
            CTDebug(TAG, "GetMyUUID() waiting for app to initialize...");
            return "";
        }
        ContentResolver contentResolver = ctxt.getContentResolver();
        // android studio warns: "Using 'GetString' to get device identifiers is not recommended",
        // but nothing in the method spec mentions this...  They might be concerned we're using
        // this for advertising, but no - just to uniquely identify the device to Caltopo Server.
        String androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
        UUID deviceUuid = UUID.nameUUIDFromBytes(androidId.getBytes(StandardCharsets.UTF_8));
        MyUUID = deviceUuid.toString();
        return MyUUID;
    }


    public static void ResetMapConnection(long maxWaitInMilliseconds) {
        if (MapStatus == MapStatusListener.mapStatus.down && MapNode == null) {
            stopPeerCoordinationForMapDisconnect();
            return;
        }
        DisconnectInProgress = true;
        MapCheckerDelay.stop();
        synchronized (MapRefreshLock) {
            MapRefreshGeneration++;
            MapRefreshInFlight = false;
            MapRefreshPending = false;
            FullMapRefreshPending = false;
            FullMapRefreshCallbacks.clear();
            LastMapSync = 0L;
            LastFullArtifactSync = 0L;
        }
        InitialMarkerPublishDelay.stop();
        InitialMarkerPublishPending = false;
        InitialMarkerWaitStartedMs = 0L;
        resetArtifactStore("ResetMapConnection");
        removeMyDeviceMarker(maxWaitInMilliseconds);
        ResolvedMyDeviceMarkerId = null;
        LastPublishedMyDeviceMarkerColor = null;
        LastPublishedMyDeviceMarkerDescription = null;
        long startTime = System.currentTimeMillis();
        for (CaltopoLiveTrack track : new ArrayList<>(liveTracks)) {
            track.shutdown(maxWaitInMilliseconds);
            if (0 != maxWaitInMilliseconds)
                maxWaitInMilliseconds = (maxWaitInMilliseconds - (System.currentTimeMillis() - startTime));
        }
        LiveTracksById.clear();
        stopPeerCoordinationForMapDisconnect();
        PeerIdMap.clear();
        FolderId = null;
        ArchiveFolderId = null;
        LastErrorString = null;
    }

    private static void stopPeerCoordinationForMapDisconnect() {
        LastStandaloneCoordinationScopeId = "";
        StandaloneCoordinationStarted = false;
        StandaloneCoordinationEnabledForActiveFlights = null;
        getCurrentRuntime().getPeerCoordinator().setCoordinationIndicatorListener(null);
        getCurrentRuntime().getPeerCoordinator().stop();
    }

    public static void StopStandaloneTrackerCoordinationIfActive() {
        if (!StandaloneCoordinationStarted) return;
        if (MapStatus == MapStatusListener.mapStatus.up && MapNode != null) return;
        if (CaltopoClient.GetStandaloneR2cCoordinationEnabled()) return;
        int activeFlightCount = CaltopoClient.GetActiveFlightCount();
        if (activeFlightCount > 0) {
            CTInfo(TAG, String.format(Locale.US,
                    "StopStandaloneTrackerCoordinationIfActive(): deferring no-map tracker stop until %d active flight(s) finish.",
                    activeFlightCount));
            return;
        }
        CTInfo(TAG, "StopStandaloneTrackerCoordinationIfActive(): stopping no-map tracker coordination.");
        LastStandaloneCoordinationScopeId = "";
        StandaloneCoordinationStarted = false;
        getCurrentRuntime().getPeerCoordinator().setCoordinationIndicatorListener(null);
        getCurrentRuntime().getPeerCoordinator().stop();
    }

    public static void OnDroneSpecStatusChanged(boolean isActiveFlag) {
        int activeFlightCount = CaltopoClient.GetActiveFlightCount();
        if (isActiveFlag && activeFlightCount == 1 && StandaloneCoordinationEnabledForActiveFlights == null) {
            StandaloneCoordinationEnabledForActiveFlights =
                    CaltopoClient.GetStandaloneR2cCoordinationEnabled();
            CTInfo(TAG, "OnDroneSpecStatusChanged(): standalone R2C coordination for active flight window is " +
                    StandaloneCoordinationEnabledForActiveFlights);
        } else if (!isActiveFlag && activeFlightCount == 0) {
            StandaloneCoordinationEnabledForActiveFlights = null;
            StopStandaloneTrackerCoordinationIfActive();
        }
    }

    @NonNull
    public static String GetMapId() {
        return (null != MapNode) ? MapNode.getId(): "";
    }
    private static void CreateArchiveDirFinished(CaltopoOp archiveFolderIdOp) {
        if (archiveFolderIdOp.fail()) {

            ShowToast(String.format(Locale.US,
                    "Could not create archive folder in map '%s' - check mapId/permissions:\n  %s",
                    GetMapId(), archiveFolderIdOp.responseString()));
            return;
        }
        ArchiveFolderId = archiveFolderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "archive folder id is %s", ArchiveFolderId));
        LookForExistingLiveTracks();
    }

    private static void CreateTrackDirFinished(CaltopoOp folderIdOp) {
        if (folderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create track folder in map '%s' - check mapId/permissions:\n  %s",
                    GetMapId(), folderIdOp.responseString()));
            return;
        }
        FolderId = folderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "track folder id is %s", FolderId));
    }

    /** parseMapUpdate()
     * Different from parseMap() in that we already have an established map w/DroneTracks and
     * Archive directories.  Now we're just interested in taking a peek at the contents of
     * the DroneTracks directory to see if there are any new Markers for R2C instances we
     * don't know about yet that haven't initiated contact with us.   This can happen if
     * there was a race condition at start-up and we both looked at the map and determined
     * that there were no other R2C instances out there when we started.
     * @param state  This contains all the map changes since our last visit.
     */
    private static void ParseMapUpdate(JSONObject state) {
        if (state == null) {
            CTDebug(TAG, "parseMapUpdate(): response missing required state.");
            return;
        }
        JSONArray features = state.optJSONArray("features");
        int count = (null == features) ? 0: features.length();
        int newCount = 0, ignoreCount = 0;
        for (int i = 0; i < count; i++) {
            JSONObject feature = features.optJSONObject(i);
            notifyArtifactFeature(feature, "delta");
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTDebug(TAG, "parseMapUpdate(): Feature missing required properties parameter.");
                ignoreCount++;
                continue;
            }
            String thisFolderId = prop.optString("folderId");
            String classStr = prop.optString("class");
            if (!FolderId.equals(thisFolderId) && !classStr.equals("Folder")) {
                if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                    CTInfo(TAG, String.format(Locale.US, "parseMapUpdate(): feature folder id '%s' doesn't match our folder id '%s': %s",
                            thisFolderId, FolderId, feature));
                }
                ignoreCount++;
                continue;
            }
            String classString = prop.optString("class");
            String idString = feature.optString("id");
            if ("LiveTrack".equals(classString)) {
                String title = prop.optString("title");
                CTDebug(TAG, "parseMapUpdate(): Found liveTrack '" + title + "' with id: " + idString);
                CaltopoLiveTrack liveTrack = LiveTracksById.get(idString);
                if (null != liveTrack) {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is one of ours - checking");
                    liveTrack.checkCaltopoTrackLabel(title);
                } else if (archiveDuplicateLiveTrackIfNeeded(feature, prop, classString, idString, title)) {
                    CTDebug(TAG, "parseMapUpdate(): duplicate liveTrack matched local publisher - archiving");
                } else {
                    CTDebug(TAG, "parseMapUpdate(): liveTrack is not one of ours - ignoring");
                    ignoreCount++;
                }
            } else if ("Marker".equals(classString)) {
                CTDebug(TAG, "parseMapUpdate(): ignoring marker (peer discovery is MQTT-based now): " + idString);
                ignoreCount++;
            }
        }
        if (newCount > 0 || ignoreCount > 0) {
            CTDebug(TAG, String.format(Locale.US,
                    "parseMapUpdate() ignored %d new features.\n  Found %d new peers since my last visit.",
                    ignoreCount, newCount));
        }
    }

    private static void ReconcileArtifactStore(@NonNull JSONObject state, @NonNull String source)
            throws JSONException {
        JSONArray features = state.optJSONArray("features");
        if (features == null) features = new JSONArray();

        HashSet<String> previousIds;
        synchronized (ArtifactLock) {
            previousIds = new HashSet<>(ArtifactFeaturesById.keySet());
        }
        HashSet<String> incomingIds = new HashSet<>();
        ArrayList<JSONObject> incomingFeatures = new ArrayList<>();
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;
            String featureId = feature.optString("id", "");
            if (featureId.isEmpty()) continue;
            incomingFeatures.add(feature);
            if (!isArtifactDelete(feature)) incomingIds.add(featureId);
        }

        int removedCount = 0;
        for (String removedId : previousIds) {
            if (incomingIds.contains(removedId)) continue;
            notifyArtifactFeature(
                    new JSONObject().put("id", removedId).put("deleted", true),
                    source);
            removedCount++;
        }
        for (JSONObject feature : incomingFeatures) {
            notifyArtifactFeature(feature, source);
        }
        CTInfo(TAG, String.format(Locale.US,
                "ReconcileArtifactStore(): source=%s incoming=%d removed=%d cached=%d",
                source, incomingIds.size(), removedCount,
                GetArtifactFeatureSnapshot().size()));
    }

    /* Parse the feature set returned by the openMap()
     * to look for our track directory and it's companion archive dir.
     * Also make a list of all other LiveTracks that might be leftover old
     * tracks in need of archival.
     * TODO: It seems like this could take a long time on a multi-op period search, so
     *  we might want to hand this off to a background thread.   Need to instrument the
     *  entire process (i.e. fetch map, parse map, connect to peers) for a big map to see
     *  how much time is required.  Also consider running this app on faster devices.
     */
    private static void ParseMap(JSONObject state)
            throws RuntimeException, JSONException {

        resetArtifactStore("ParseMap(full)");
        MyLiveTracksInThisMap = new JSONArray();
        JSONArray markerFeatures = new JSONArray();
        String archiveFolderName = archiveFolderName(FolderName, new Date());

        CTInfo(TAG, String.format(Locale.US,
                "parseMap() Checking map for folders: '%s' and '%s'",
                FolderName, archiveFolderName));

        JSONArray features = state.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            notifyArtifactFeature(feature, "full");
            CTInfo(TAG, "parseMap(): Parsing returned feature:\n" + feature.toString(2));
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTError(TAG, "parseMap(): feature missing 'properties' - skipping:" + feature);
                continue;
            }
            String title = prop.optString("title");
            if (title.isEmpty()) {
                // This happened a few times during debug with Caltopo's v1 API.
                // No way to delete these runt features w/in the GUI, so we add them to a
                // list and wait until after we have a folderId and can confirm that the
                // feature is within either of our directories before deleting.  We don't
                // delete anything that we didn't create.
                CTDebug(TAG, "parseMap(): found rogue feature missing title: " + feature.toString(4));
                RogueFeaturesPendingDeletes.add(feature);
                continue;
            }

            String classProp = prop.optString("class", "");
            switch (classProp) {
                case "": CTError(TAG, "parseMap(): feature missing class: " + feature.toString(4)); break;
                case "Marker": markerFeatures.put(feature); break;
                case "LiveTrack":
                    // collect the list of features that might be ours - don't know if they
                    // are in our folder yet, because we may not even have folders.
                        MyLiveTracksInThisMap.put(feature);
                        break;
                case "Folder": {
                    if (FolderId == null && title.equals(FolderName)) {
                        FolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", FolderName, FolderId));
                    } else if (null == ArchiveFolderId && title.equals(archiveFolderName)) {
                        ArchiveFolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", archiveFolderName, ArchiveFolderId));
                    }
                    break;
                }
            }
        }

        // Request the directories to be created if they weren't found in the existing map:
        if (null == FolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", FolderName));
            getCurrentRuntime().getCalTopoSessionGateway()
                    .addFolder(FolderName, true, true, CaltopoMap::CreateTrackDirFinished);
        }
        // Peer discovery is now MQTT-based; no marker scanning needed.

        if (null == ArchiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            getCurrentRuntime().getCalTopoSessionGateway()
                    .addFolder(archiveFolderName, false, false, CaltopoMap::CreateArchiveDirFinished);
        } else LookForExistingLiveTracks();
    }

    static String archiveFolderName(String trackFolderName, Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("ddMMM", Locale.US);
        return trackFolderName.trim() + " " + formatter.format(date);
    }

    private static void PollMapUpdates() {
        if (ShutdownInProgress || MapNode == null) {
            CTDebug(TAG, "PollMapUpdates(): skipping due to shutdown or no map.");
            return;
        }
        final long requestStartedAtMs = timeSource.now();
        final long requestCursor;
        final boolean fullReconcile;
        final long refreshGeneration;
        final CaltopoNode.MapNode requestMapNode = MapNode;
        synchronized (MapRefreshLock) {
            if (MapRefreshInFlight) {
                MapRefreshPending = true;
                return;
            }
            MapRefreshInFlight = true;
            MapRefreshPending = false;
            refreshGeneration = MapRefreshGeneration;
            fullReconcile = FullMapRefreshPending || LastFullArtifactSync == 0L ||
                    requestStartedAtMs - LastFullArtifactSync >= FULL_ARTIFACT_RECONCILE_INTERVAL_MS;
            if (fullReconcile) FullMapRefreshPending = false;
            requestCursor = fullReconcile ? 0L : LastMapSync;
        }
        CTInfo(TAG, String.format(Locale.US,
                "PollMapUpdates(): requesting %s map artifacts since %d.",
                fullReconcile ? "full" : "incremental", requestCursor));
        try {
            getCurrentRuntime().getCalTopoSessionGateway().openMap(
                    requestMapNode,
                    requestCursor,
                    op -> UpdateMapFinished(
                            op, requestStartedAtMs, fullReconcile, refreshGeneration, requestMapNode.getId()));
        } catch (Exception e) {
            CTError(TAG, "PollMapUpdates(): openMap() raised.", e);
            FinishMapRefresh(false, fullReconcile, requestStartedAtMs, refreshGeneration);
        }
    }

    private static void UpdateMapFinished(CaltopoOp updateMapOp,
                                          long requestStartedAtMs,
                                          boolean fullReconcile,
                                          long refreshGeneration,
                                          @NonNull String requestMapId) {
        if (ShutdownInProgress || refreshGeneration != MapRefreshGeneration || MapNode == null ||
                !requestMapId.equals(MapNode.getId())) {
            CTDebug(TAG, "updateMapFinished(): ignoring callback during shutdown.");
            return;
        }
        CTInfo(TAG, "updateMapFinished()");
        if (updateMapOp == null || updateMapOp.fail()) {
            CTError(TAG, String.format(Locale.US, "Not able to update map '%s':\n  %s",
                    GetMapId(), updateMapOp));
            FinishMapRefresh(false, fullReconcile, requestStartedAtMs, refreshGeneration);
            return;
        }
        recordCaltopoSessionRtt(updateMapOp, "updateMap");

        if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
            CTInfo(TAG, "updateMapFinished() dumping map updates to logfile...");
            CTInfo(TAG, updateMapOp.responseString());
        }
        JSONObject stateObj = updateMapOp.responseJson != null
                ? updateMapOp.responseJson.optJSONObject("state")
                : null;
        if (stateObj == null) {
            CTError(TAG, "updateMapFinished(): response missing map state.");
            FinishMapRefresh(false, fullReconcile, requestStartedAtMs, refreshGeneration);
            return;
        }
        try {
                if (fullReconcile) {
                    ReconcileArtifactStore(stateObj, "reconcile");
                } else {
                    ParseMapUpdate(stateObj);
                }
        } catch (Exception e) {
            CTError(TAG, "updateMapFinished(): map artifact parse raised. ", e);
            FinishMapRefresh(false, fullReconcile, requestStartedAtMs, refreshGeneration);
            return;
        }

        if (!InitialMarkerPublishPending && !DisconnectInProgress) {
            publishMyDeviceMarkerIfPossible(GetMyLocation());
        }
        SetMapStatus(MapStatusListener.mapStatus.up, null);
        FinishMapRefresh(true, fullReconcile, requestStartedAtMs, refreshGeneration);
    }

    private static void FinishMapRefresh(boolean success,
                                         boolean fullReconcile,
                                         long requestStartedAtMs,
                                         long refreshGeneration) {
        ArrayList<Runnable> completedCallbacks = new ArrayList<>();
        boolean runAgain;
        synchronized (MapRefreshLock) {
            if (refreshGeneration != MapRefreshGeneration) return;
            if (success) {
                LastMapSync = Math.max(LastMapSync, requestStartedAtMs);
                if (fullReconcile) {
                    LastFullArtifactSync = requestStartedAtMs;
                    completedCallbacks.addAll(FullMapRefreshCallbacks);
                    FullMapRefreshCallbacks.clear();
                }
            } else if (fullReconcile && !FullMapRefreshCallbacks.isEmpty()) {
                FullMapRefreshPending = true;
            }
            MapRefreshInFlight = false;
            runAgain = MapRefreshPending || FullMapRefreshPending;
            MapRefreshPending = false;
        }
        for (Runnable callback : completedCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                CTError(TAG, "FinishMapRefresh() callback raised.", e);
            }
        }
        if (ShutdownInProgress || MapNode == null) return;
        if (runAgain) {
            DelayedExec.RunAfterDelayInMsec(CaltopoMap::PollMapUpdates, 0L);
        } else {
            ScheduleNextMapUpdate();
        }
    }

    private static void ScheduleNextMapUpdate() {
        if (ShutdownInProgress || MapNode == null) return;
        long delaySeconds;
        synchronized (MapRefreshLock) {
            delaySeconds = MapArtifactForegroundConsumers > 0
                    ? FOREGROUND_MAP_UPDATE_TIME_IN_SECONDS
                    : RepeatMapUpdateTimeInSeconds;
        }
        MapCheckerDelay.start(CaltopoMap::PollMapUpdates, delaySeconds * 1000L, 0L);
    }

    private static void PhotoMarkerTimeout() {
        ShowToast("Caltopo clue upload timed out; clue is saved in the KMZ/local archive for manual upload when connected.");
    }

    private static void PhotoMarkerComplete(CaltopoOp op) {
        VerifyPhotoTimeout.stop();
        if (op.success()) {
            ShowToast("Photo Waypoint created.");
        } else {
            CTWarn(TAG, String.format(Locale.US,
                    "PhotoMarkerComplete(): clue upload failed responseCode=%d response=%s",
                    op.responseCode, op.responseString()));
            ShowToast("Caltopo clue upload failed; clue is saved in the KMZ/local archive for manual upload when connected.");
        }
    }

    public static void SubmitClueWithPhoto(
            @NonNull CtDroneSpec droneSpec,
            double clueLat, double clueLng, double clueAlt,
            @NonNull String clueTitle,
            @NonNull String clueDescription,
            long clueTimestamp,
            @NonNull Bitmap clueImage) {

        // FIXME: Can we attach the image to our local WaypointTrack when no map is available?
        if (GetMapId().isEmpty() || null == FolderId || FolderId.isEmpty()) {
            ShowToast("Map is not connected - cannot publish to map at this time.");
        }
        CTDebug(TAG, "SubmitClue(" + clueTitle + ")...");

        VerifyPhotoTimeout.start(CaltopoMap::PhotoMarkerTimeout, 30 * 1000, 0);
        getCurrentRuntime().getCalTopoSessionGateway().addPhotoMarker(
                clueLat,
                clueLng,
                clueTitle,
                clueDescription,
                FolderId,
                clueTimestamp,
                clueImage,
                CaltopoMap::PhotoMarkerComplete);
    }


    private static void SetMapStatus(MapStatusListener.mapStatus mapStatus, @Nullable String optEmsg) {
        MapStatusListener.mapStatus previousStatus = MapStatus;
        boolean statusChanged = !previousStatus.name().equals(mapStatus.name());
        boolean enteredUp = mapStatus == MapStatusListener.mapStatus.up &&
                previousStatus != MapStatusListener.mapStatus.up;

        if (statusChanged) {
            Bundle parameters = new Bundle();
            parameters.putString("r2c_mapId", MapNode != null ? MapNode.getTitle(): "");
            parameters.putInt("r2c_listenerCount", MapListeners.size());
            parameters.putInt("r2c_featDeletePending", RogueFeaturesPendingDeletes.size());
            CaltopoClient.CTEvent(TAG, "MapIs_" + mapStatus.toString(), parameters);
            CTDebug(TAG, "XYZZY: Changing map status from: " + MapStatus.name() + " to: " + mapStatus.name());
        }
        MapStatus = mapStatus;
        if (!MapListeners.isEmpty()) {
            for (MapStatusListener Listener : MapListeners) Listener.mapStatusUpdate(MapStatus, MapNode, optEmsg);
        }
        if (mapStatus == MapStatusListener.mapStatus.up) {
            if (enteredUp) {
                ProfileExpiryPoll.start(CaltopoMap::pollActiveProfileExpiry, PROFILE_EXPIRY_POLL_MS, PROFILE_EXPIRY_POLL_MS);
            }
            if (MapNode != null && enteredUp) {
                // Start peer coordination for this map.
                // The runtime chooses tracker-backed coordination when configured,
                // otherwise it falls back to MQTT.
                // The developer-only peer-coordination toggle in Settings is the only gate;
                // ownership arbitration is required whenever a map is connected.
                if (CaltopoClient.GetUsePeersFlag()) {
                    LastStandaloneCoordinationScopeId = "";
                    StandaloneCoordinationStarted = false;
                    StandaloneCoordinationEnabledForActiveFlights = null;
                    CTInfo(TAG, String.format(Locale.US,
                            "SetMapStatus(up): starting peer coordination. trackerConfigured=%s",
                            !CaltopoClient.GetTrackerApiKey().isEmpty() &&
                                    !CaltopoClient.GetTrackerUrlPfx().isEmpty()));
                    getCurrentRuntime().getPeerCoordinator()
                            .setStandaloneStandbyEligible(false);
                    getCurrentRuntime().getPeerCoordinator().start(
                            MapNode.getId(), GetMyUUID(), R2CActivity.MyDeviceName, null);
                    getCurrentRuntime().getPeerCoordinator()
                            .setCoordinationIndicatorListener(CaltopoMap::onCoordinationIndicatorStateChanged);
                    getCurrentRuntime().getPeerCoordinator().updateMyPosition(
                            MyLocation != null ? MyLocation.getLatitude()  : 0,
                            MyLocation != null ? MyLocation.getLongitude() : 0);
                    removeStaleMyDeviceMarkers();
                    scheduleInitialDeviceMarkerPublish();
                } else {
                    CTWarn(TAG, "SetMapStatus(up): peer coordination disabled by developer override.");
                    scheduleInitialDeviceMarkerPublish();
                }
            }
            while (!RogueFeaturesPendingDeletes.isEmpty()) {
                JSONObject feature = RogueFeaturesPendingDeletes.remove(0);
                JSONObject prop = feature.optJSONObject("properties");
                if (null != prop) {
                    String featureFolderId = prop.optString("folderId");
                    if (featureFolderId.equals(FolderId) || featureFolderId.equals(ArchiveFolderId)) {
                        // then it's a feature fragment that we created, so we can/should delete it.
                        String featureId = feature.optString("id");
                        getCurrentRuntime().getCalTopoSessionGateway()
                                .deleteShapeWithId(featureId, null);
                    }
                }
            }
        } else {
            ProfileExpiryPoll.stop();
            InitialMarkerPublishDelay.stop();
            InitialMarkerPublishPending = false;
            InitialMarkerWaitStartedMs = 0L;
            DeferredExpiredProfileId = null;
            getCurrentRuntime().getPeerCoordinator().setCoordinationIndicatorListener(null);
            DisconnectInProgress = false;
        }
    }

    /**
     * Called when openMapOp completed.
     * o Parse the returned map, look for existing TrackDir and ArchiveDir.
     * o Also look for any old live tracks that didn't get archived (happens
     * when the app was terminated mid-record).
     * o Create TrackDir and ArchiveDir if they weren't already present.
     */
    private static void OpenMapFinished(CaltopoOp lOpenMapOp,
                                        long requestStartedAtMs,
                                        long refreshGeneration) {
        if (null == MapNode || refreshGeneration != MapRefreshGeneration) return;
        if (lOpenMapOp.fail()) {
            if (null != lOpenMapOp.response && lOpenMapOp.response.startsWith("<!DOCTYPE html>")) {
                // Not going to figure this out on our own - write response to a file and open it with a browser:
                CaltopoClient.OpenBufferContentsInBrowser(lOpenMapOp.response);
            } else {
                LastErrorString = String.format(Locale.US, "OpenMapFinished(): Not able to open map '%s'. code:%d, reason:%s",
                        MapNode != null ? MapNode.getId(): "", lOpenMapOp.responseCode, lOpenMapOp.response);
                ShowToast(LastErrorString);
            }
            MapNode = null;
            return;
        }
        recordCaltopoSessionRtt(lOpenMapOp, "openMap");

        JSONObject responseJson = lOpenMapOp.responseJson;
        JSONObject state = (responseJson != null) ? responseJson.optJSONObject("state") : null;
        if (null == state) {
            CTError(TAG, "OpenMapFinished(): state missing from response: " + lOpenMapOp.response );
        } else try {
            ParseMap(state);
            synchronized (MapRefreshLock) {
                LastMapSync = Math.max(LastMapSync, requestStartedAtMs);
                LastFullArtifactSync = requestStartedAtMs;
            }
        } catch (Exception e) {
            CTError(TAG, "OpenMapFinished(): parseMap() raised:", e);
        }
        if (!MapCheckerDelay.isRunning()) {
            CTDebug(TAG, "openMapFinished(): starting map checker delay...");
            MapCheckerDelay.start(CaltopoMap::PollMapUpdates,
                    FirstMapUpdateTimeInSeconds * 1000L, 0L);
        }
    }

    // ParseR2cMarker is retained for backward-compat map parsing but no longer creates peer connections.
    private static JSONObject ParseR2cMarker(JSONArray ipAddrsObj, JSONObject feature, JSONObject prop) throws JSONException {
        JSONObject marker = new JSONObject();
        marker.put("ipaddrs", ipAddrsObj);
        marker.put("name", prop.optString("r2c-name"));
        marker.put("id", feature.optString("id"));
        marker.put("feature", feature);
        JSONObject geometry = feature.optJSONObject("geometry");
        if (null != geometry) {
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (null != coordinates && coordinates.length() > 1) {
                marker.put("lat", coordinates.optString(1));
                marker.put("lng", coordinates.optString(0));
            }
        }
        return marker;
    }


    // FindR2cPeers is no longer called — peer discovery is MQTT-based.
    @SuppressWarnings("unused")
    public static void FindR2cPeers(@NonNull JSONArray markerFeatures) {
        CTDebug(TAG, "FindR2cPeers(): no-op (MQTT-based peer discovery active).");
    }

    /** UpdateMyLocation()
     *  Pay attention to ongoing periodic location updates.  If the accuracy of measurement
     *  has improved, then update our marker's location.   If the distance between our
     *  previous marker location and this new location is greater than 2.5x the least
     *  accurate of our respective markers, then assume we've moved.
     * @param location  The latest location update from the GPS system.
     */
    public static void UpdateMyLocation(@NonNull android.location.Location location) {
        boolean updateNeeded = ( null == MyLocation || !MyLocation.hasAccuracy() );
        double distanceInMeters = 0F;
        if (location.hasAccuracy()) {
            CtDroneSpec.UpdateMyLocationBaseline(location.getLatitude(), location.getLongitude());
        }
        evaluateAutoQuitAfterRelocation(location);
        if (null != MyLocation && MyLocation.hasAccuracy() && location.hasAccuracy()) {
            distanceInMeters = DistanceFromMeInMeters(location.getLatitude(), location.getLongitude());
            if ((location.getAccuracy() < MyLocation.getAccuracy()) ||
                    (distanceInMeters > (2.5*location.getAccuracy()))) updateNeeded = true;
        }
        startTrackerCoordinationWithoutActiveMapIfNeeded(location);
        getCurrentRuntime().getPeerCoordinator()
                .updateMyPosition(location.getLatitude(), location.getLongitude());

        if (updateNeeded) {
            if (null == MyLocation) MyLocation = location;
            CTInfo(TAG, String.format(Locale.US,
                    """
                              UpdateMyLocation()
                                new: lat:%.7f, lng:%.7f, accuracy:%.3fm
                                old: lat:%.7f, lng:%.7f, accuracy:%.3fm, distanceInMeters:%.3fm""",
                    location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                    MyLocation.getLatitude(), MyLocation.getLongitude(), MyLocation.getAccuracy(),
                    distanceInMeters));
            MyLocation = location;
            if (!InitialMarkerPublishPending && !DisconnectInProgress) {
                publishMyDeviceMarkerIfPossible(location);
            }
            refreshDeviceMarkerIfNeeded();
        }
    }

    private static void evaluateAutoQuitAfterRelocation(@NonNull Location location) {
        evaluateAutoQuitAfterRelocation(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                location.hasAccuracy());
    }

    private static void evaluateAutoQuitAfterRelocation(
            double latitude,
            double longitude,
            float accuracyMeters,
            boolean hasAccuracy
    ) {
        long nowMs = timeSource.now();
        // A foreground service may receive the first trustworthy fix only after
        // the Activity has stopped. Capture that fix even while an active/recent
        // flight is temporarily protecting the map from disconnection.
        if (IncidentDisplayInactive && AutoQuitRelocationAnchor == null &&
                hasAccuracy && accuracyMeters > 0.0f &&
                accuracyMeters < AUTO_QUIT_REQUIRED_ACCURACY_METERS) {
            AutoQuitRelocationAnchor = new RelocationAnchor(latitude, longitude, accuracyMeters);
        }
        if (!isAutoQuitAfterRelocationEligible(hasAccuracy, accuracyMeters, nowMs)) {
            if (!IncidentDisplayInactive) AutoQuitRelocationAnchor = null;
            return;
        }

        RelocationAnchor anchor = AutoQuitRelocationAnchor;
        if (anchor == null) {
            AutoQuitRelocationAnchor = new RelocationAnchor(latitude, longitude, accuracyMeters);
            CTInfo(TAG, String.format(Locale.US,
                    "evaluateAutoQuitAfterRelocation(): armed at lat:%.7f lng:%.7f accuracy:%.2fm after %s without active drone flights.",
                    latitude, longitude, accuracyMeters,
                    DurationAsString(nowMs - CtDroneSpec.LastWaypointUpdateTimestampMsec())));
            return;
        }

        double distanceMeters = distanceMeters(anchor.latitude, anchor.longitude, latitude, longitude);
        if (distanceMeters < AUTO_QUIT_RELOCATION_DISTANCE_METERS) return;

        AutoQuitRelocationAnchor = null;
        CTWarn(TAG, String.format(Locale.US,
                "evaluateAutoQuitAfterRelocation(): tablet moved %.2fm after %s without active drone flights; disconnecting incident map.",
                distanceMeters, DurationAsString(nowMs - CtDroneSpec.LastWaypointUpdateTimestampMsec())));
        inactivityDisconnectHandler.disconnect();
    }

    private static boolean isAutoQuitAfterRelocationEligible(
            boolean hasAccuracy,
            float accuracyMeters,
            long nowMs
    ) {
        if (CaltopoClient.IsExitRequested()) return false;
        if (MapOfflinePrepRuntime.isActive()) return false;
        if (MapStatus != MapStatusListener.mapStatus.up || MapNode == null) return false;
        if (!hasAccuracy || accuracyMeters >= AUTO_QUIT_REQUIRED_ACCURACY_METERS) return false;
        if (CaltopoClient.GetActiveFlightCount() > 0) return false;
        if (getCurrentRuntime().getPeerCoordinator()
                .hasOperationalActivityPreventingMapDisconnect()) return false;
        long lastWaypointTimestampMs = CtDroneSpec.LastWaypointUpdateTimestampMsec();
        return lastWaypointTimestampMs > 0L &&
                nowMs - lastWaypointTimestampMs >= AUTO_QUIT_FLIGHT_QUIET_MS;
    }

    /**
     * Leaves only the incident map while keeping the application available for standalone
     * coordination. The tracker coordinator will enter its normal 30-second standby after the
     * map disconnect. Returns false when active operational work makes disconnect unsafe.
     */
    public static boolean DisconnectIncidentMapIfInactive(@NonNull String reason) {
        if (ShutdownInProgress || MapStatus != MapStatusListener.mapStatus.up || MapNode == null) {
            return false;
        }
        if (MapOfflinePrepRuntime.isActive() || CaltopoClient.GetActiveFlightCount() > 0) {
            return false;
        }
        long lastWaypointTimestampMs = CtDroneSpec.LastWaypointUpdateTimestampMsec();
        long nowMs = timeSource.now();
        if (lastWaypointTimestampMs > 0L &&
                nowMs - lastWaypointTimestampMs < AUTO_QUIT_FLIGHT_QUIET_MS) {
            return false;
        }
        if (getCurrentRuntime().getPeerCoordinator()
                .hasOperationalActivityPreventingMapDisconnect()) {
            return false;
        }

        String normalizedReason = reason.trim().isEmpty() ? "inactive" : reason.trim();
        String mapId = GetMapId();
        Bundle parameters = new Bundle();
        parameters.putString("r2c_mapId", mapId);
        parameters.putString("r2c_reason", normalizedReason);
        CaltopoClient.CTEvent(TAG, "IncidentMapAutoDisconnect", parameters);
        CTWarn(TAG, String.format(Locale.US,
                "DisconnectIncidentMapIfInactive(): leaving map=%s reason=%s",
                mapId, normalizedReason));
        OpenMap(null);
        IncidentDisplayInactive = false;
        DisconnectInProgress = false;
        EnsureStandaloneTrackerCoordinationStarted();
        return true;
    }

    /** Captures a relocation anchor while the UI is still alive. Active flights and
     * the normal RID quiet interval remain the safety gates after the display sleeps. */
    public static void BeginIncidentDisplayInactive() {
        IncidentDisplayInactive = true;
        Location location = MyLocation;
        if (location != null && location.getAccuracy() > 0.0f &&
                location.getAccuracy() < AUTO_QUIT_REQUIRED_ACCURACY_METERS) {
            AutoQuitRelocationAnchor = new RelocationAnchor(
                    location.getLatitude(), location.getLongitude(), location.getAccuracy());
        }
    }

    public static void EndIncidentDisplayInactive() {
        IncidentDisplayInactive = false;
    }

    public static boolean DisconnectIncidentMapAfterTrackerSilenceIfInactive() {
        if (!IncidentDisplayInactive || !getCurrentRuntime().getPeerCoordinator()
                .hasTrackerAcknowledgementSilence(TRACKER_SILENCE_STANDBY_MS)) {
            return false;
        }
        return DisconnectIncidentMapIfInactive("tracker silent for 60 seconds");
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusMeters = 6_371_000.0;
        double lat1Radians = Math.toRadians(lat1);
        double lat2Radians = Math.toRadians(lat2);
        double deltaLatRadians = Math.toRadians(lat2 - lat1);
        double deltaLonRadians = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLatRadians / 2.0) * Math.sin(deltaLatRadians / 2.0) +
                Math.cos(lat1Radians) * Math.cos(lat2Radians) *
                        Math.sin(deltaLonRadians / 2.0) * Math.sin(deltaLonRadians / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return earthRadiusMeters * c;
    }

    public static void EnsureStandaloneTrackerCoordinationStarted() {
        Location location = MyLocation;
        if (location == null) return;
        startTrackerCoordinationWithoutActiveMapIfNeeded(location);
        getCurrentRuntime().getPeerCoordinator()
                .updateMyPosition(location.getLatitude(), location.getLongitude());
    }

    private static void startTrackerCoordinationWithoutActiveMapIfNeeded(@NonNull Location location) {
        if (!CaltopoClient.GetUsePeersFlag()) return;
        if (MapStatus == MapStatusListener.mapStatus.up && MapNode != null) return;
        if (!isStandaloneTrackerCoordinationAllowedForCurrentFlightWindow()) return;
        if (CaltopoClient.GetTrackerCoordinationUrlPfx().isEmpty() ||
                CaltopoClient.GetTrackerCoordinationApiKey().isEmpty()) {
            return;
        }
        String scopeId = CaltopoClient.GetTrackerCoordinationScopeId();
        PeerCoordinator.CoordinationIndicatorState currentState =
                getCurrentRuntime().getPeerCoordinator().getCoordinationIndicatorState();
        if (StandaloneCoordinationStarted &&
                scopeId.equals(LastStandaloneCoordinationScopeId) &&
                currentState != PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED) {
            return;
        }
        LastStandaloneCoordinationScopeId = scopeId;
        StandaloneCoordinationStarted = true;
        CTInfo(TAG, String.format(Locale.US,
                "startTrackerCoordinationWithoutActiveMapIfNeeded(): scopeId='%s' lat=%.6f lng=%.6f %s",
                scopeId,
                location.getLatitude(),
                location.getLongitude(),
                CaltopoClient.DescribeTrackerCredentialSelection("coordination")));
        getCurrentRuntime().getPeerCoordinator()
                .setStandaloneStandbyEligible(true);
        getCurrentRuntime().getPeerCoordinator().start(
                scopeId,
                GetMyUUID(),
                R2CActivity.MyDeviceName,
                null);
        getCurrentRuntime().getPeerCoordinator()
                .setCoordinationIndicatorListener(CaltopoMap::onCoordinationIndicatorStateChanged);
    }

    private static boolean isStandaloneTrackerCoordinationAllowedForCurrentFlightWindow() {
        if (StandaloneCoordinationStarted) return true;
        int activeFlightCount = CaltopoClient.GetActiveFlightCount();
        if (activeFlightCount <= 0) {
            StandaloneCoordinationEnabledForActiveFlights = null;
            return CaltopoClient.GetStandaloneR2cCoordinationEnabled();
        }
        if (StandaloneCoordinationEnabledForActiveFlights == null) {
            StandaloneCoordinationEnabledForActiveFlights =
                    CaltopoClient.GetStandaloneR2cCoordinationEnabled();
            CTInfo(TAG, "isStandaloneTrackerCoordinationAllowedForCurrentFlightWindow(): " +
                    "latching standalone R2C coordination to " +
                    StandaloneCoordinationEnabledForActiveFlights +
                    " for current active flight window.");
        }
        return StandaloneCoordinationEnabledForActiveFlights;
    }

    @NonNull
    public static String GetLastErrorString() {
        return (null != LastErrorString) ? LastErrorString : "";
    }

    @NonNull public static String GetMapName() {
        return (MapNode != null) ? MapNode.getTitle() : "";
    }

    @NonNull
    public static String R2cPeerConnectionStats() {
        if (CaltopoClient.DebugLevel < CaltopoClient.DebugLevelDebug) return "";

        StringBuilder builder = new StringBuilder();
        for (R2CMqttManager.PeerState ps : getCurrentRuntime().getPeerCoordinator().getPeerList()) {
            builder.append(ps.name)
                    .append(": ctRtt=").append(ps.caltopoRttMs).append("ms")
                    .append(ps.online ? "" : " [offline]")
                    .append("\n");
        }

        if (builder.isEmpty()) {
            builder.append(R2CMqttManager.GetMyIpAddresses().toString());
            for (CaltopoLiveTrack liveTrack : LiveTracksById.values()) {
                if (liveTrack.isActive()) {
                    CtDroneSpec ds = liveTrack.getDroneSpec();
                    builder.append("\n  ")
                            .append(ds.trackLabel())
                            .append("(")
                            .append(ds.getGoodCount())
                            .append("/")
                            .append(ds.getTotalCount())
                            .append(")");
                }
            }
        }
        return builder.toString();
    }

    private static void LookForExistingLiveTracks() {
        if (null == FolderId || null == ArchiveFolderId) return;
        java.util.Set<String> recoveringTrackIds = CaltopoInterruptedTrackJournal.recover(
                GetMapId(), ArchiveFolderId, getCurrentRuntime());
        publishMyDeviceMarkerIfPossible(GetMyLocation());
        long timeNowInMilliseconds = System.currentTimeMillis();
        long maxTrackAgeInMilliseconds = CaltopoClient.GetNewTrackDelayInSeconds() * 1000;

        CTInfo(TAG, String.format(Locale.US,
                "Parsing %d liveTracks to check for idle items in the drone folder",
                MyLiveTracksInThisMap.length()));

        while (0 != MyLiveTracksInThisMap.length()) {
            JSONObject feature = (JSONObject)MyLiveTracksInThisMap.remove(0);
            if (recoveringTrackIds.contains(feature.optString("id", ""))) continue;
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) continue;

            // only interested in features w/in the drone tracks directory:
            String featureFolderId = prop.optString("folderId", "");
            if (!featureFolderId.equals(FolderId)) continue;

            // found a feature in the drone folder.
            String featureClass = prop.optString("class", null);
            String title = prop.optString("title");
            CTDebug(TAG, String.format(Locale.US, "Found a %s:%s in drone folder", featureClass, title));

            if (archiveDuplicateLiveTrackIfNeeded(feature, prop, featureClass, feature.optString("id", ""), title)) {
                continue;
            }

            String lastUpdatedStr = prop.optString("updated", "");
            long lastUpdatedInMilliseconds = Long.parseLong(lastUpdatedStr);
            long trackAgeInMilliseconds = timeNowInMilliseconds - lastUpdatedInMilliseconds;

            if (trackAgeInMilliseconds < maxTrackAgeInMilliseconds) {
                CTDebug(TAG, String.format(Locale.US,
                        "%s:%s last update was only %.3f seconds ago - ignoring",
                        featureClass, title, (double)trackAgeInMilliseconds / 1000.0));
                continue;
            }

            // found a feature in the drone folder old enough to archive.
            CTDebug(TAG, String.format(Locale.US, "%s:%s last updated %.3f seconds ago - archiving.",
                    featureClass, title, (double)trackAgeInMilliseconds/1000.0));
            ArchiveFeature(feature, featureClass, timeNowInMilliseconds, 0);
        }
        PollMapUpdates();
    }

    /* feature is the complete feature description.   featureClass is the type of feature
     * that is being archived.  All are archived ultimately as 'Shape' class, but if
     * specified feature is a LiveTrack, the LiveTrack is deleted after archiving it's
     * state as a Shape.   That's the way the Caltopo v1 API wants it to happen.
     */
    public static void ArchiveFeature(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds, long maxWaitInMilliseconds) {
        ArchiveFeature(feature, featureClass, timeNowInMilliseconds, maxWaitInMilliseconds, null);
    }

    static void ArchiveFeature(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds, long maxWaitInMilliseconds,
                               @Nullable Consumer<Boolean> onComplete) {
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null == ArchiveFolderId) {
            CTError(TAG, "archiveFeature(): can't archive - folder not created yet.");
            if (onComplete != null) onComplete.accept(false);
            return;
        }
        try {
            String trackId = feature.optString("id", "");
            if (trackId.isEmpty()) {
                CTError(TAG, "archiveFeature(): id for feature is empty - this shouldn't happen.\n  " +
                        feature.toString(4));
                if (onComplete != null) onComplete.accept(false);
                return;
            }
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                prop = new JSONObject();
                feature.put("properties", prop);
            }
            prop.put("stroke", ArchiveLineProp.color);
            prop.put("stroke-width", ArchiveLineProp.width);
            prop.put("stroke-opacity", ArchiveLineProp.opacity);
            prop.put("pattern", ArchiveLineProp.pattern);
            prop.put("folderId", ArchiveFolderId);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            prop.put("class", "Shape");  // convert from LiveTrack to shape.
            AtomicReference<CaltopoOp> deleteOp = new AtomicReference<>();
            CaltopoOp op = getCurrentRuntime().getCalTopoSessionGateway()
                    .editObjectWithId("Shape", trackId, feature, archiveOp -> {
                        onArchiveFeatureEditFinished(trackId, archiveOp);
                        if (!archiveOp.success()) {
                            if (onComplete != null) onComplete.accept(false);
                            return;
                        }
                        if (!featureClass.equals("LiveTrack")) {
                            refreshArtifactsAfterArchiveFinished(trackId, true);
                            if (onComplete != null) onComplete.accept(true);
                            return;
                        }
                        CTInfo(TAG, String.format(Locale.US, "archiveFeature(): Stopping liveTrack %s....", trackId));
                        deleteOp.set(getCurrentRuntime().getCalTopoSessionGateway()
                                .deleteLiveTrackWithId(trackId, finishedDeleteOp -> {
                                    CTInfo(TAG, String.format(Locale.US,
                                            "archiveFeature(): delete liveTrackId=%s success=%s responseCode=%d",
                                            trackId, finishedDeleteOp.success(), finishedDeleteOp.responseCode));
                                    refreshArtifactsAfterArchiveFinished(
                                            trackId, finishedDeleteOp.success());
                                    if (onComplete != null) onComplete.accept(finishedDeleteOp.success());
                                }, 400, 404));
                    });
            if (maxWaitInMilliseconds > 0) {
                op.syncOp(maxWaitInMilliseconds);
                maxWaitInMilliseconds = maxWaitInMilliseconds - (System.currentTimeMillis() - timeNowInMilliseconds);
                CaltopoOp startedDeleteOp = deleteOp.get();
                if (startedDeleteOp != null && maxWaitInMilliseconds > 0) {
                    startedDeleteOp.syncOp(maxWaitInMilliseconds);
                }
            }
        } catch (Exception e) {
            CTError(TAG, "archiveFeature() raised:", e);
            if (onComplete != null) onComplete.accept(false);
        }
    }

    private static void onArchiveFeatureEditFinished(@NonNull String trackId, @NonNull CaltopoOp archiveOp) {
        CTInfo(TAG, String.format(Locale.US,
                "archiveFeature(): edit trackId=%s success=%s responseCode=%d",
                trackId, archiveOp.success(), archiveOp.responseCode));
        if (!archiveOp.success()) return;
    }

    private static void refreshArtifactsAfterArchiveFinished(@NonNull String trackId, boolean success) {
        CTInfo(TAG, String.format(Locale.US,
                "archiveFeature(): requesting incremental artifact refresh after trackId=%s success=%s",
                trackId, success));
        RequestMapRefreshNow();
    }

    public static MapStatusListener.mapStatus GetMapStatus() {return (MapStatus);}
    public static boolean IsInitialDeviceMarkerPublishPending() { return InitialMarkerPublishPending; }

    /* N.B. map can be up, but folders not yet created, in which case these
     * will return null... patience.
     */
    @Nullable
    public static String GetFolderId() { return FolderId; }
    @Nullable
    public static String GetArchiveFolderId() { return ArchiveFolderId; }

    public static void Shutdown() {
        try {
            ShutdownInProgress = true;
            MapCheckerDelay.stop();
            VerifyTimer.stop();
            VerifyPhotoTimeout.stop();
            ProfileExpiryPoll.stop();
            InitialMarkerPublishDelay.stop();
            InitialMarkerPublishPending = false;
            InitialMarkerWaitStartedMs = 0L;
            DeferredExpiredProfileId = null;
            // Drain active map-owned LiveTracks through the normal archive path
            // before tearing down the CalTopo session.
            ResetMapConnection(10_000L);
            removeMyDeviceMarker(10_000L);
            MapNode = null;
            SessionNodeMap = null;
            SetMapStatus(MapStatusListener.mapStatus.down, "Shutdown in progress.");
            getCurrentRuntime().getPeerCoordinator().stop(); // always stop; no-op if never started
            getCurrentRuntime().getCalTopoSessionGateway().shutdown();
        } catch (Exception e) {
            CTError(TAG, "Shutdown() raised: ", e);
        }
    }

    private static void notifyArtifactFeature(@Nullable JSONObject feature, @NonNull String source) {
        if (feature == null) return;
        ArrayList<ArtifactListener> listeners;
        int artifactCount = applyArtifactToStore(feature);
        synchronized (ArtifactLock) {
            if (ArtifactListeners.isEmpty()) return;
            listeners = new ArrayList<>(ArtifactListeners);
        }
        String featureId = feature.optString("id");
        String featureClass = "";
        JSONObject properties = feature.optJSONObject("properties");
        if (properties != null) {
            featureClass = properties.optString("class");
        }
        CTDebug(TAG, String.format(Locale.US,
                "notifyArtifactFeature(): source=%s id=%s class=%s cached=%d listeners=%d",
                source, featureId, featureClass, artifactCount, listeners.size()));
        long now = System.currentTimeMillis();
        for (ArtifactListener listener : listeners) try {
            listener.onArtifactFeature(feature, source, now);
        } catch (Exception e) {
            CTError(TAG, "notifyArtifactFeature() listener raised", e);
        }
    }

    private static int applyArtifactToStore(@NonNull JSONObject feature) {
        String featureId = feature.optString("id", "");
        if (featureId.isEmpty()) {
            synchronized (ArtifactLock) {
                return ArtifactFeaturesById.size();
            }
        }
        synchronized (ArtifactLock) {
            if (isArtifactDelete(feature)) {
                ArtifactFeaturesById.remove(featureId);
            } else {
                ArtifactFeaturesById.put(featureId, feature);
            }
            return ArtifactFeaturesById.size();
        }
    }

    private static boolean isArtifactDelete(@NonNull JSONObject feature) {
        if (feature.optBoolean("deleted", false)) return true;
        JSONObject properties = feature.optJSONObject("properties");
        if (properties == null) {
            return feature.has("id") && !feature.has("geometry");
        }
        if (properties.optBoolean("deleted", false)) return true;
        String action = properties.optString("action", "");
        return "delete".equalsIgnoreCase(action) || "removed".equalsIgnoreCase(action);
    }

    private static void resetArtifactStore(@NonNull String reason) {
        synchronized (ArtifactLock) {
            ArtifactFeaturesById.clear();
        }
        CTDebug(TAG, "resetArtifactStore(): " + reason);
    }

    private static void publishMyDeviceMarkerIfPossible(@Nullable Location location) {
        if (ShutdownInProgress || DisconnectInProgress || MapStatus != MapStatusListener.mapStatus.up ||
                MapNode == null || FolderId == null || FolderId.isEmpty() || location == null) {
            return;
        }
        String markerId = GetMyUUID();
        if (markerId.isEmpty()) return;
        try {
            long nowMs = System.currentTimeMillis();
            JSONObject extraProperties = new JSONObject();
            extraProperties.put(LOCAL_DEVICE_MARKER_NAME_PROP, R2CActivity.MyDeviceName);
            extraProperties.put(LOCAL_DEVICE_MARKER_GUID_PROP, markerId);
            extraProperties.put(LOCAL_DEVICE_MARKER_LAST_SEEN_PROP, nowMs);
            String markerDescription = buildMyDeviceMarkerDescription();
            String markerColor = getLocalDeviceMarkerColor();
            extraProperties.put("description", markerDescription);
            extraProperties.put("marker-color", markerColor);
            getCurrentRuntime().getCalTopoSessionGateway().addMarker(
                    location.getLatitude(),
                    location.getLongitude(),
                    LOCAL_DEVICE_MARKER_TITLE_PREFIX + R2CActivity.MyDeviceName,
                    LOCAL_DEVICE_MARKER_SYMBOL,
                    FolderId,
                    markerId,
                    extraProperties,
                    markerOp -> {
                        String resolvedId = (markerOp != null && markerOp.success()) ? markerOp.id() : "";
                        if (!resolvedId.isEmpty()) {
                            ResolvedMyDeviceMarkerId = resolvedId;
                        }
                        LastPublishedMyDeviceMarkerColor = markerColor;
                        LastPublishedMyDeviceMarkerDescription = markerDescription;
                        CTDebug(TAG, String.format(Locale.US,
                                "publishMyDeviceMarkerIfPossible(): markerId=%s resolvedId=%s success=%s responseCode=%d",
                                markerId, resolvedId, markerOp != null && markerOp.success(),
                                markerOp != null ? markerOp.responseCode : -1));
                    });
        } catch (Exception e) {
            CTError(TAG, "publishMyDeviceMarkerIfPossible() raised", e);
        }
    }

    private static void removeStaleMyDeviceMarkers() {
        if (MapNode == null) return;
        String markerId = GetMyUUID();
        if (markerId.isEmpty()) return;
        String markerTitle = LOCAL_DEVICE_MARKER_TITLE_PREFIX + R2CActivity.MyDeviceName;
        long nowMs = System.currentTimeMillis();
        long staleAfterMs = getDeviceMarkerStaleAfterMs();
        ArrayList<String> staleIds = new ArrayList<>();
        synchronized (ArtifactLock) {
            for (JSONObject feature : ArtifactFeaturesById.values()) {
                if (feature == null) continue;
                String featureId = feature.optString("id", "");
                if (featureId.isEmpty() || markerId.equals(featureId)) continue;
                if (ResolvedMyDeviceMarkerId != null && ResolvedMyDeviceMarkerId.equals(featureId)) continue;
                JSONObject properties = feature.optJSONObject("properties");
                if (!isManagedR2cMarker(properties, LOCAL_DEVICE_MARKER_TITLE_PREFIX)) continue;
                boolean sameGuid = markerId.equals(properties.optString(LOCAL_DEVICE_MARKER_GUID_PROP, ""));
                boolean sameTitle = markerTitle.equals(properties.optString("title", ""));
                long lastSeenEpochMs = getMarkerLastSeenEpochMs(properties);
                boolean staleHeartbeat = lastSeenEpochMs > 0L && (nowMs - lastSeenEpochMs) > staleAfterMs;
                if (sameGuid || sameTitle || staleHeartbeat) {
                    staleIds.add(featureId);
                }
            }
        }
        for (String staleId : staleIds) {
            try {
                getCurrentRuntime().getCalTopoSessionGateway()
                        .deleteMarkerWithId(staleId, deleteOp ->
                                CTDebug(TAG, String.format(Locale.US,
                                        "removeStaleMyDeviceMarkers(): markerId=%s success=%s responseCode=%d",
                                        staleId, deleteOp != null && deleteOp.success(),
                                        deleteOp != null ? deleteOp.responseCode : -1)));
            } catch (Exception e) {
                CTWarn(TAG, "removeStaleMyDeviceMarkers() raised", e);
            }
        }
    }

    private static void removeMyDeviceMarker(long maxWaitInMilliseconds) {
        if (MapNode == null) return;
        String markerId = GetMyUUID();
        if (markerId.isEmpty()) return;
        long waitBudgetMs = shouldWaitForMarkerDeleteAck()
                ? Math.min(maxWaitInMilliseconds, MARKER_DELETE_WAIT_MS)
                : 0L;
        try {
            CaltopoOp deletePrimaryOp = getCurrentRuntime().getCalTopoSessionGateway()
                    .deleteMarkerWithId(markerId, deleteOp ->
                            CTDebug(TAG, String.format(Locale.US,
                                    "removeMyDeviceMarker(): markerId=%s success=%s responseCode=%d",
                                    markerId, deleteOp != null && deleteOp.success(),
                                    deleteOp != null ? deleteOp.responseCode : -1)));
            if (waitBudgetMs > 0L) {
                deletePrimaryOp.syncOp(waitBudgetMs);
            }
            if (ResolvedMyDeviceMarkerId != null && !ResolvedMyDeviceMarkerId.isEmpty() &&
                    !ResolvedMyDeviceMarkerId.equals(markerId)) {
                String resolvedId = ResolvedMyDeviceMarkerId;
                CaltopoOp deleteResolvedOp = getCurrentRuntime().getCalTopoSessionGateway()
                        .deleteMarkerWithId(resolvedId, deleteOp ->
                                CTDebug(TAG, String.format(Locale.US,
                                        "removeMyDeviceMarker(): resolvedMarkerId=%s success=%s responseCode=%d",
                                        resolvedId, deleteOp != null && deleteOp.success(),
                                        deleteOp != null ? deleteOp.responseCode : -1)));
                if (waitBudgetMs > 0L) {
                    deleteResolvedOp.syncOp(waitBudgetMs);
                }
            }
        } catch (java.util.concurrent.TimeoutException e) {
            CTWarn(TAG, String.format(Locale.US,
                    "removeMyDeviceMarker(): timed out waiting %d ms for delete acknowledgement.",
                    waitBudgetMs));
        } catch (Exception e) {
            CTWarn(TAG, "removeMyDeviceMarker() raised", e);
        }
    }

    private static boolean shouldWaitForMarkerDeleteAck() {
        Context context = R2CApplication.getAppCtxt();
        if (context == null) return false;
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            CTWarn(TAG, "shouldWaitForMarkerDeleteAck() raised", e);
            return false;
        }
    }

    @NonNull
    private static String getLocalDeviceMarkerColor() {
        PeerCoordinator.CoordinationIndicatorState state =
                getCurrentRuntime().getPeerCoordinator().getCoordinationIndicatorState();
        return localDeviceMarkerColorForState(state, InitialMarkerPublishPending);
    }

    @NonNull
    static String localDeviceMarkerColorForState(
            @NonNull PeerCoordinator.CoordinationIndicatorState state,
            boolean initialPublishPending
    ) {
        if (initialPublishPending &&
                state != PeerCoordinator.CoordinationIndicatorState.HEALTHY &&
                state != PeerCoordinator.CoordinationIndicatorState.IDLE) {
            return LOCAL_DEVICE_COLOR_STARTING;
        }
        switch (state) {
            case HEALTHY:
            case IDLE:
                return LOCAL_DEVICE_COLOR_HEALTHY;
            case DEGRADED:
                return LOCAL_DEVICE_COLOR_DEGRADED;
            case UNCONFIGURED:
            default:
                return LOCAL_DEVICE_COLOR_UNCONFIGURED;
        }
    }
}
