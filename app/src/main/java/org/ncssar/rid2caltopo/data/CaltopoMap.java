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
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.ncssar.rid2caltopo.video.MapOfflinePrepRuntime;
import org.ncssar.rid2caltopo.mapping.MappingWorkRuntime;


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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static long RepeatMapUpdateTimeInSeconds = 20;
    private static final long FOREGROUND_MAP_UPDATE_TIME_IN_SECONDS = 20;
    // Initial connection loads the map; subsequent full reads require an operator reload.
    static boolean fullArtifactRefreshRequired(long lastSync, boolean manualReload) {
        return lastSync == 0L || manualReload;
    }
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
    private static final long MARKER_DELETE_WAIT_MS = 4_000L;
    private static final double AUTO_QUIT_RELOCATION_DISTANCE_METERS = 50.0 * 0.3048;
    private static final float AUTO_QUIT_REQUIRED_ACCURACY_METERS = (float) (25.0 * 0.3048);
    private static final long AUTO_QUIT_FLIGHT_QUIET_MS = 5L * 60L * 1000L;
    private static final long TRACKER_SILENCE_STANDBY_MS = 60L * 1000L;
    /** Bounded wait for finishing owned LiveTracks before map disconnect / quit. */
    public static final long DISCONNECT_FINISH_TIMEOUT_MS = 15_000L;
    private static final ExecutorService DisconnectFinishWorker =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "caltopo-disconnect-finish");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean DisconnectFinishInFlight = new AtomicBoolean(false);
    /** Completions queued while a finish is already running (e.g. OpenMap after Disconnect). */
    private static final AtomicReference<Runnable> PendingDisconnectCompletion =
            new AtomicReference<>();
    @Nullable private static volatile Consumer<String> disconnectProgressHandler = null;
    @Nullable private static volatile Runnable pendingClueFlushHandler = null;
    @NonNull private static volatile TimeSource timeSource = System::currentTimeMillis;
    @NonNull private static volatile InactivityDisconnectHandler inactivityDisconnectHandler =
            () -> DisconnectIncidentMapIfInactive("relocation");

    // my peers by GUID (MQTT-based):
    private static final Hashtable<String, R2CMqttManager.PeerState> PeerIdMap = new Hashtable<>(16);

    // poll the map to see if there are any new r2c peers or user has changed one of our track labels
    private static final DelayedExec MapCheckerDelay = new DelayedExec();
    private static final DelayedExec ServerRetryDelay = new DelayedExec();
    private static int serverRetryAttempt = 0;
    private static final DelayedExec InitialMarkerPublishDelay = new DelayedExec();
    private static String FolderId;
    private static volatile String ArchiveFolderId;
    private static volatile CaltopoNode.MapNode MapNode;
    private static String FolderName = "DroneTracks";
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
    private static long LastMapPollStartedAt;

    static long minimumMapPollDelayMs(long now, long lastRequest) {
        if (lastRequest == 0L || now < lastRequest) return 0L;
        return Math.max(0L, 20_000L - (now - lastRequest));
    }
    private static final Object MapRefreshLock = new Object();
    private static boolean MapRefreshInFlight;
    private static boolean MapRefreshPending;
    private static boolean FullMapRefreshPending;
    private static int MapArtifactForegroundConsumers;
    private static volatile long MapRefreshGeneration;
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
    @Nullable private static MapSelection ActiveMapSelection;
    @Nullable private static CaltopoOp InitialMapRequest;

    /** Immutable identity of the request, independent of map title/catalog timestamps. */
    private static final class MapSelection {
        final String mapId, personalSessionId, domain, teamId, credentialId, credentialSecret;
        final long personalGeneration;

        MapSelection(CaltopoNode.MapNode node) {
            mapId = node.getId();
            personalSessionId = node.getPersonalSessionID();
            domain = personalSessionId == null ? DomainAndPort : null;
            CaltopoCredentials credentials = personalSessionId == null ? MyCaltopoCredentials : null;
            teamId = credentials == null ? null : credentials.teamId;
            credentialId = credentials == null ? null : credentials.credentialId;
            credentialSecret = credentials == null ? null : credentials.credentialSecret;
            personalGeneration = CaltopoPersonalSession.INSTANCE.getGeneration();
        }

        boolean matches(CaltopoNode.MapNode node) {
            if (!mapId.equals(node.getId()) ||
                    !java.util.Objects.equals(personalSessionId, node.getPersonalSessionID())) return false;
            if (personalSessionId != null) {
                return personalGeneration == CaltopoPersonalSession.INSTANCE.getGeneration();
            }
            CaltopoCredentials credentials = MyCaltopoCredentials;
            return java.util.Objects.equals(domain, DomainAndPort) &&
                    java.util.Objects.equals(teamId, credentials == null ? null : credentials.teamId) &&
                    java.util.Objects.equals(credentialId, credentials == null ? null : credentials.credentialId) &&
                    java.util.Objects.equals(credentialSecret, credentials == null ? null : credentials.credentialSecret);
        }
    }
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
                CTInfo(TAG, "Account hierarchy received.");
            }
            SessionNodeMap = parseMapHierarchy(verifyOp.responseJson);
            CTDebug(TAG, String.format(Locale.US,
                    "parseMapHierarchy() returned list with %d top-level items", SessionNodeMap.size()));
            retryMarkerCleanup();
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
                        "Organization access expired; waiting for %d active flight(s) to finish before disconnecting.",
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
            return "Organization access expired";
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
        FolderName = normalizedTrackFolderName(CaltopoClient.GetTrackFolderName());
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
        // Reselecting the active map is navigation, not a full reload. Keep tracks,
        // artifacts, polling and any pending initial request intact. A manual Reload
        // remains available when the operator actually wants a fresh full snapshot.
        if (mapNode != null && MapNode != null && ActiveMapSelection != null &&
                !ShutdownInProgress && !DisconnectInProgress &&
                (MapStatus == MapStatusListener.mapStatus.up || MapStatus == MapStatusListener.mapStatus.connecting) &&
                ActiveMapSelection.matches(mapNode)) {
            CaltopoPersonalAccountConnection.logState("map open skipped reselect map=" + mapNode.getId()
                    + " grant=" + CaltopoPersonalAccountConnection.grantKind(mapNode.getPersonalSessionID()));
            return;
        }
        if (mapNode != null && mapNode.getPersonalSessionID() != null) {
            if (!CaltopoClient.CanStartMapSession()) {
                CaltopoPersonalAccountConnection.logState("map open refused map=" + mapNode.getId() + " reason=app-closing");
                SetMapStatus(MapStatusListener.mapStatus.down, "App is closing. Reopen it before connecting.");
                return;
            }
            // Personal login bypasses organization Init/VerifyAccount. Restore
            // the credential-independent setup, including same-process reopen.
            ShutdownInProgress = false;
            CurrentRuntime = R2cRuntimeRegistry.getDefaultRuntime();
            if (MyInstance == null) MyInstance = new CaltopoMap();
            FolderName = normalizedTrackFolderName(CaltopoClient.GetTrackFolderName());
            if (MyUUID == null) GetMyUUID();
        }
        if (ShutdownInProgress) {
            CaltopoPersonalAccountConnection.logState("map open ignored shutdown"
                    + (mapNode == null ? "" : " map=" + mapNode.getId()));
            CTDebug(TAG, "OpenMap(): ignoring request while shutdown in progress.");
            return;
        }
        DisconnectInProgress = false;
        if (mapNode == null) {
            CaltopoPersonalAccountConnection.logState("map open reset");
            CTDebug(TAG, "OpenMap(): Map connection reset.");
            DisconnectAfterFinishingOwned(DISCONNECT_FINISH_TIMEOUT_MS, null);
            return;
        }
        if (MapNode != null) {
            if (hasOwnedActivePublications()) {
                // Finish owned LiveTracks off the UI thread, then open the new map.
                final CaltopoNode.MapNode target = mapNode;
                DisconnectAfterFinishingOwned(DISCONNECT_FINISH_TIMEOUT_MS, () -> OpenMap(target));
                return;
            }
            // don't wait around for reset operations to complete:
            ResetMapConnection(0);
            SetMapStatus(MapStatusListener.mapStatus.down, "Map switch request.");
        }
        CaltopoPersonalSession.activate(mapNode.getPersonalSessionID(), mapNode.getId());
        MapNode = mapNode;
        ActiveMapSelection = new MapSelection(mapNode);
        OperatingProfiles.setScope(CaltopoClient.GetTrackerCoordinationUrlPfx(), GetMapId());
        DisconnectInProgress = false;

        SetMapStatus(MapStatusListener.mapStatus.connecting, null);
        try {
            String grant = mapNode.getPersonalSessionID();
            String credential = grant == null ? "team"
                    : (CaltopoPersonalAccountConnection.usesSignedCredential(grant) ? "personal" : "session");
            String account = "team".equals(credential)
                    ? (MyCaltopoCredentials == null || MyCaltopoCredentials.teamId == null ? "" : MyCaltopoCredentials.teamId)
                    : CaltopoPersonalSession.INSTANCE.getAccountID();
            CaltopoPersonalAccountConnection.logState("map open connecting map=" + mapNode.getId()
                    + " account=" + account + " credential=" + credential
                    + " grant=" + CaltopoPersonalAccountConnection.grantKind(grant));
            CTDebug(TAG, String.format(Locale.US, "Connecting to map '%s'(%s)'", MapNode.getTitle(), MapNode.getId()));
            long requestStartedAtMs = timeSource.now();
            long refreshGeneration;
            synchronized (MapRefreshLock) {
                refreshGeneration = MapRefreshGeneration;
            }
            InitialMapRequest = getCurrentRuntime().getCalTopoSessionGateway()
                    .openMapForConnection(MapNode, op -> OpenMapFinished(op, requestStartedAtMs, refreshGeneration));

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
                CTError(TAG, "AddArtifactListener() replay listener raised" + " (" + e.getClass().getSimpleName() + ")");
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
        RepeatMapUpdateTimeInSeconds = Math.min(30, Math.max(20, repeatDelay));
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


    public static void cancelServerRetry() {
        ServerRetryDelay.stop();
        serverRetryAttempt = 0;
        CaltopoMapOpenNotice.clear();
    }

    public static void setDisconnectProgressHandler(@Nullable Consumer<String> handler) {
        disconnectProgressHandler = handler;
    }

    public static void setPendingClueFlushHandler(@Nullable Runnable handler) {
        pendingClueFlushHandler = handler;
    }

    public static boolean hasOwnedActivePublications() {
        for (CaltopoLiveTrack track : new ArrayList<>(liveTracks)) {
            if (track.hasOwnedActivePublication()) return true;
        }
        return false;
    }

    /**
     * Finish each owned LiveTrack (archive Shape once), flush pending clues where
     * possible, then drop the map connection. Bounded; journals on timeout/unreachable
     * CalTopo and disconnects anyway. Never blocks the main thread.
     */
    public static void DisconnectAfterFinishingOwned(
            long timeoutMs, @Nullable Runnable onComplete) {
        if (MapStatus == MapStatusListener.mapStatus.down && MapNode == null) {
            if (onComplete != null) CaltopoRecoveryWork.onMain(onComplete);
            return;
        }
        final String destinationMap = GetMapId();
        final String destinationFolder = GetArchiveFolderId();
        final ArrayList<CaltopoLiveTrack> tracks = new ArrayList<>(liveTracks);
        final boolean needsFinish = hasOwnedActivePublications();
        final long budgetMs = Math.max(0L, timeoutMs);
        final Runnable complete = () -> {
            completeMapDisconnect("Disconnect request.");
            if (onComplete != null) onComplete.run();
        };
        if (!needsFinish) {
            if (isMainThread()) {
                DisconnectFinishWorker.execute(() -> {
                    flushPendingCluesBestEffort();
                    CaltopoRecoveryWork.onMain(complete);
                });
            } else {
                flushPendingCluesBestEffort();
                complete.run();
            }
            return;
        }
        if (!DisconnectFinishInFlight.compareAndSet(false, true)) {
            CTDebug(TAG, "DisconnectAfterFinishingOwned(): finish already in flight.");
            // Do not OpenMap / run completion while the first finish still archives —
            // chain after that worker's disconnect completes.
            if (onComplete != null) {
                PendingDisconnectCompletion.updateAndGet(prior -> () -> {
                    if (prior != null) prior.run();
                    onComplete.run();
                });
            }
            return;
        }
        publishDisconnectProgress("Finishing live track…");
        DisconnectFinishWorker.execute(() -> {
            try {
                finishOwnedTracksOnWorker(tracks, destinationMap, destinationFolder, budgetMs);
            } finally {
                DisconnectFinishInFlight.set(false);
                final Runnable chained = PendingDisconnectCompletion.getAndSet(null);
                CaltopoRecoveryWork.onMain(() -> {
                    complete.run();
                    if (chained != null) chained.run();
                });
            }
        });
    }

    /** Blocking finish for callers already on a worker (quit / Shutdown). */
    static void finishOwnedTracksBlocking(long timeoutMs) {
        if (isMainThread()) {
            throw new IllegalStateException("finishOwnedTracksBlocking must run off Main");
        }
        if (!hasOwnedActivePublications()) {
            flushPendingCluesBestEffort();
            return;
        }
        publishDisconnectProgress("Finishing live track…");
        finishOwnedTracksOnWorker(
                new ArrayList<>(liveTracks), GetMapId(), GetArchiveFolderId(), Math.max(0L, timeoutMs));
    }

    private static void finishOwnedTracksOnWorker(
            @NonNull ArrayList<CaltopoLiveTrack> tracks,
            @Nullable String destinationMap,
            @Nullable String destinationFolder,
            long budgetMs) {
        flushPendingCluesBestEffort();
        long deadline = System.currentTimeMillis() + budgetMs;
        for (CaltopoLiveTrack track : tracks) {
            if (!track.hasOwnedActivePublication()) continue;
            long remaining = Math.max(0L, deadline - System.currentTimeMillis());
            try {
                track.finishOwnedPublicationForDisconnect(remaining, destinationMap, destinationFolder);
            } catch (Exception e) {
                CTError(TAG, "finishOwnedTracksOnWorker(): track finish raised", e);
            }
        }
    }

    private static void flushPendingCluesBestEffort() {
        Runnable flush = pendingClueFlushHandler;
        if (flush == null) return;
        try {
            flush.run();
        } catch (Exception e) {
            CTWarn(TAG, "flushPendingCluesBestEffort() raised", e);
        }
    }

    /** Internet restored: wake pending clue uploads without blocking the caller. */
    public static void flushPendingCluesOnInternetRestored() {
        flushPendingCluesBestEffort();
    }

    /**
     * Internet restored: retry interrupted / finished-track Shape archives.
     * Uses stored map + archive folder even if MapStatus has not flipped to up yet
     * (validated Internet often precedes the next successful map poll).
     */
    public static void recoverInterruptedTracksOnInternetRestored() {
        CaltopoInterruptedTrackJournal.clearInFlightForConnectivityRestore();
        String mapId = GetMapId();
        String archiveFolder = ArchiveFolderId;
        if (mapId == null || mapId.isEmpty() || archiveFolder == null || archiveFolder.isEmpty()) {
            CTDebug(TAG, "recoverInterruptedTracksOnInternetRestored(): skip — map/archive folder not ready");
            return;
        }
        if (GetMapStatus() == MapStatusListener.mapStatus.up) {
            LookForExistingLiveTracks();
            return;
        }
        // Map not yet "up", but folders from the prior session are still known — recover
        // finished-track journals immediately so a hung offline POST cannot delay them.
        final R2cRuntime runtime = getCurrentRuntime();
        final java.util.List<JSONObject> features = GetArtifactFeatureSnapshot();
        CaltopoRecoveryWork.execute(() -> {
            try {
                CaltopoInterruptedTrackJournal.recover(mapId, archiveFolder, runtime, features);
            } catch (Exception error) {
                CTWarn(TAG, "Interrupted LiveTrack recovery after reconnect raised", error);
            }
        });
    }

    /** Pending finished-track archives + owned LiveTracks for connectivity-flush logging. */
    public static int pendingInterruptedArchiveCount() {
        return CaltopoInterruptedTrackJournal.pendingCount();
    }

    private static void publishDisconnectProgress(@NonNull String message) {
        Consumer<String> handler = disconnectProgressHandler;
        if (handler != null) {
            CaltopoRecoveryWork.onMain(() -> {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    CTWarn(TAG, "disconnectProgressHandler raised", e);
                }
            });
        } else {
            CaltopoRecoveryWork.onMain(() -> ShowToast(message));
        }
    }

    private static void completeMapDisconnect(@NonNull String reason) {
        CTDebug(TAG, "completeMapDisconnect(): " + reason);
        ResetMapConnection(0);
        MapNode = null;
        ActiveMapSelection = null;
        CaltopoPersonalSession.activate(null, "");
        OperatingProfiles.endAssignment();
        SetMapStatus(MapStatusListener.mapStatus.down, reason);
    }

    private static boolean isMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Looper.myLooper() == main;
    }

    public static void ResetMapConnection(long maxWaitInMilliseconds) {
        cancelServerRetry();
        ActiveMapSelection = null;
        CaltopoOp initial = InitialMapRequest;
        InitialMapRequest = null;
        if (initial != null) initial.cancel(true);
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
            LastMapPollStartedAt = 0L;
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
        FinishInitialMapSetup();
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
        FinishInitialMapSetup();
        LookForExistingLiveTracks();
    }

    private static void FinishInitialMapSetup() {
        if (ShutdownInProgress || DisconnectInProgress || MapNode == null ||
                MapStatus != MapStatusListener.mapStatus.connecting ||
                FolderId == null || ArchiveFolderId == null) return;
        // The initial response already established connectivity and loaded artifacts.
        // Recovery stays on its ordered worker; neither it nor the next 20-second
        // incremental poll should keep the connection dialog open.
        SetMapStatus(MapStatusListener.mapStatus.up, null);
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
                    CTInfo(TAG, "parseMapUpdate(): feature belongs to a different folder.");
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
            CTInfo(TAG, "parseMap(): Parsing returned feature.");
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                CTError(TAG, "parseMap(): feature missing properties; skipped.");
                continue;
            }
            String title = prop.optString("title");
            if (title.isEmpty()) {
                // This happened a few times during debug with Caltopo's v1 API.
                // No way to delete these runt features w/in the GUI, so we add them to a
                // list and wait until after we have a folderId and can confirm that the
                // feature is within either of our directories before deleting.  We don't
                // delete anything that we didn't create. Markers are never in that list:
                // a clue marker must not be removed because its title was missing.
                CTDebug(TAG, "parseMap(): feature missing title.");
                if (!"Marker".equals(prop.optString("class", ""))) RogueFeaturesPendingDeletes.add(feature);
                else CTDebug(TAG, "parseMap(): untitled marker left in place id=" + feature.optString("id"));
                continue;
            }

            String classProp = prop.optString("class", "");
            switch (classProp) {
                case "": CTError(TAG, "parseMap(): feature missing class."); break;
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
            final String mapId = GetMapId();
            final long generation = MapRefreshGeneration;
            getCurrentRuntime().getCalTopoSessionGateway()
                    .addFolder(FolderName, true, true, op -> {
                        if (recoveryContextIsCurrent(mapId, generation)) CreateTrackDirFinished(op);
                    });
        }
        // Peer discovery is now MQTT-based; no marker scanning needed.

        if (null == ArchiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            final String mapId = GetMapId();
            final long generation = MapRefreshGeneration;
            getCurrentRuntime().getCalTopoSessionGateway()
                    .addFolder(archiveFolderName, false, false, op -> {
                        if (recoveryContextIsCurrent(mapId, generation)) CreateArchiveDirFinished(op);
                    });
        } else LookForExistingLiveTracks();
    }

    static String normalizedTrackFolderName(@Nullable String name) {
        return name == null || name.trim().isEmpty() ? "DroneTracks" : name.trim();
    }

    static String archiveFolderName(String trackFolderName, Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("ddMMM", Locale.US);
        return normalizedTrackFolderName(trackFolderName) + " " + formatter.format(date);
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
            long waitMs = FullMapRefreshPending ? 0L : minimumMapPollDelayMs(requestStartedAtMs, LastMapPollStartedAt);
            if (waitMs > 0L) {
                MapCheckerDelay.start(CaltopoMap::PollMapUpdates, waitMs, 0L);
                return;
            }
            LastMapPollStartedAt = requestStartedAtMs;
            MapRefreshInFlight = true;
            MapRefreshPending = false;
            refreshGeneration = MapRefreshGeneration;
            fullReconcile = fullArtifactRefreshRequired(LastMapSync, FullMapRefreshPending);
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
            CTError(TAG, "PollMapUpdates(): openMap() raised." + " (" + e.getClass().getSimpleName() + ")");
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
            CTError(TAG, "updateMapFinished(): map artifact parse raised. " + " (" + e.getClass().getSimpleName() + ")");
            FinishMapRefresh(false, fullReconcile, requestStartedAtMs, refreshGeneration);
            return;
        }

        retryMarkerCleanup();
        if (!InitialMarkerPublishPending && !DisconnectInProgress) {
            publishMyDeviceMarkerIfPossible(GetMyLocation());
        }
        SetMapStatus(MapStatusListener.mapStatus.up, null);
        final String archiveFolder = ArchiveFolderId;
        if (!DisconnectInProgress && archiveFolder != null) {
            final R2cRuntime runtime = getCurrentRuntime();
            final String scope = AwaitingMapFlights.selectedPublicationScope();
            // Keep this refresh in flight until recovery finishes: slow storage cannot
            // accumulate another recovery job on every polling tick.
            CaltopoRecoveryWork.execute(() -> {
                try {
                    if (!recoveryContextIsCurrent(requestMapId, refreshGeneration)) return;
                    AwaitingMapFlights.reconcile(requestMapId, scope);
                    if (!recoveryContextIsCurrent(requestMapId, refreshGeneration)) return;
                    CaltopoInterruptedTrackJournal.recover(
                            requestMapId, archiveFolder, runtime, GetArtifactFeatureSnapshot());
                } catch (Exception error) {
                    CTError(TAG, "Flight recovery deferred; journal retained for retry.", error);
                } finally {
                    CaltopoRecoveryWork.onMain(() -> FinishMapRefresh(
                            true, fullReconcile, requestStartedAtMs, refreshGeneration));
                }
            });
        } else FinishMapRefresh(true, fullReconcile, requestStartedAtMs, refreshGeneration);
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
                CTError(TAG, "FinishMapRefresh() callback raised." + " (" + e.getClass().getSimpleName() + ")");
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
                    if ("Marker".equals(prop.optString("class", ""))) continue;
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
    private static void scheduleServerRetry(@NonNull String message) {
        CaltopoMapOpenNotice.show(message);
        if (MapStatus != MapStatusListener.mapStatus.connecting) {
            SetMapStatus(MapStatusListener.mapStatus.connecting, message);
        }
        long delay = switch (Math.min(serverRetryAttempt, 3)) {
            case 0 -> 3_000L;
            case 1 -> 6_000L;
            case 2 -> 12_000L;
            default -> 15_000L;
        };
        serverRetryAttempt++;
        CaltopoPersonalAccountConnection.logState("map open server retry in " + delay + "ms " + message);
        ServerRetryDelay.start(CaltopoMap::retryCurrentMapOpen, delay, 0L);
    }

    private static void retryCurrentMapOpen() {
        if (ShutdownInProgress || DisconnectInProgress || MapNode == null) return;
        if (MapStatus != MapStatusListener.mapStatus.connecting) return;
        long requestStartedAtMs = timeSource.now();
        long refreshGeneration;
        synchronized (MapRefreshLock) {
            refreshGeneration = MapRefreshGeneration;
        }
        InitialMapRequest = getCurrentRuntime().getCalTopoSessionGateway()
                .openMapForConnection(MapNode, op -> OpenMapFinished(op, requestStartedAtMs, refreshGeneration));
    }

    private static void OpenMapFinished(CaltopoOp lOpenMapOp,
                                        long requestStartedAtMs,
                                        long refreshGeneration) {
        if (null == MapNode || refreshGeneration != MapRefreshGeneration) return;
        InitialMapRequest = null;
        if (lOpenMapOp.fail()) {
            if (lOpenMapOp.response != null && lOpenMapOp.response.startsWith("CalTopo server unavailable")) {
                scheduleServerRetry(lOpenMapOp.response);
                return;
            }
            ServerRetryDelay.stop();
            serverRetryAttempt = 0;
            CaltopoMapOpenNotice.clear();
            if (null != lOpenMapOp.response && lOpenMapOp.response.startsWith("<!DOCTYPE html>")) {
                // Not going to figure this out on our own - write response to a file and open it with a browser:
                CaltopoClient.OpenBufferContentsInBrowser(lOpenMapOp.response);
            } else {
                LastErrorString = String.format(Locale.US, "OpenMapFinished(): Not able to open map '%s'. code:%d, reason:%s",
                        MapNode != null ? MapNode.getId(): "", lOpenMapOp.responseCode, lOpenMapOp.response);
                ShowToast(LastErrorString);
            }
            MapNode = null;
            OperatingProfiles.endAssignment();
            SetMapStatus(MapStatusListener.mapStatus.down, "Map could not load. Check your connection and map permissions, then retry.");
            return;
        }
        ServerRetryDelay.stop();
        serverRetryAttempt = 0;
        CaltopoMapOpenNotice.clear();
        recordCaltopoSessionRtt(lOpenMapOp, "openMap");

        JSONObject responseJson = lOpenMapOp.responseJson;
        JSONObject state = (responseJson != null) ? responseJson.optJSONObject("state") : null;
        if (null == state) {
            CTError(TAG, "OpenMapFinished(): state missing from response. " + lOpenMapOp );
            SetMapStatus(MapStatusListener.mapStatus.down, "Map response was incomplete. Please try connecting again.");
            return;
        } else try {
            ParseMap(state);
            synchronized (MapRefreshLock) {
                long snapshotCursor = responseJson.optLong("timestamp", requestStartedAtMs);
                LastMapSync = Math.max(LastMapSync, snapshotCursor > 500L ? snapshotCursor : requestStartedAtMs);
                LastMapPollStartedAt = requestStartedAtMs;
            }
            FinishInitialMapSetup();
        } catch (Exception e) {
            CTError(TAG, "OpenMapFinished(): parseMap() raised:" + " (" + e.getClass().getSimpleName() + ")");
            SetMapStatus(MapStatusListener.mapStatus.down, "Map could not load. Please try connecting again.");
            return;
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
        if (MappingWorkRuntime.preventsMapDisconnect(nowMs, AUTO_QUIT_FLIGHT_QUIET_MS)) return false;
        if (MapOfflinePrepRuntime.isActive() ||
                (MapOfflinePrepRuntime.lastActivityAtMsec() > 0L &&
                 nowMs - MapOfflinePrepRuntime.lastActivityAtMsec() < AUTO_QUIT_FLIGHT_QUIET_MS)) return false;
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
        if (MappingWorkRuntime.preventsMapDisconnect(timeSource.now(), AUTO_QUIT_FLIGHT_QUIET_MS)) return false;
        if (MapOfflinePrepRuntime.isActive() || CaltopoClient.GetActiveFlightCount() > 0) {
            return false;
        }
        long lastWaypointTimestampMs = CtDroneSpec.LastWaypointUpdateTimestampMsec();
        long nowMs = timeSource.now();
        if (MapOfflinePrepRuntime.lastActivityAtMsec() > 0L &&
                nowMs - MapOfflinePrepRuntime.lastActivityAtMsec() < AUTO_QUIT_FLIGHT_QUIET_MS) return false;
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
        // A legacy preference or an active-flight latch must never join no-map flights.
        return false;
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

    private static boolean recoveryContextIsCurrent(String mapId, long generation) {
        CaltopoNode.MapNode current = MapNode;
        return !ShutdownInProgress && !DisconnectInProgress && current != null &&
                generation == MapRefreshGeneration && mapId.equals(current.getId());
    }

    private static void LookForExistingLiveTracks() {
        if (null == FolderId || null == ArchiveFolderId) return;
        final String mapId = GetMapId();
        final String archiveFolder = ArchiveFolderId;
        final long generation = MapRefreshGeneration;
        final R2cRuntime runtime = getCurrentRuntime();
        CaltopoRecoveryWork.execute(() -> {
            if (!recoveryContextIsCurrent(mapId, generation)) return;
            final java.util.Set<String> recoveringTrackIds;
            try {
                recoveringTrackIds = CaltopoInterruptedTrackJournal.recover(
                        mapId, archiveFolder, runtime, GetArtifactFeatureSnapshot());
            } catch (Exception error) {
                CTError(TAG, "Startup flight recovery deferred; journal retained for retry.", error);
                CaltopoRecoveryWork.onMain(() -> {
                    if (recoveryContextIsCurrent(mapId, generation)) PollMapUpdates();
                });
                return;
            }
            CaltopoRecoveryWork.onMain(() -> {
                if (recoveryContextIsCurrent(mapId, generation)) {
                    LookForExistingLiveTracksFinished(recoveringTrackIds);
                }
            });
        });
    }

    private static void LookForExistingLiveTracksFinished(java.util.Set<String> recoveringTrackIds) {
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
        // The initial snapshot is current. Normal polling will pick up subsequent
        // changes without making another read a prerequisite for connection.
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
        ArchiveFeatureOnMap(feature, featureClass, timeNowInMilliseconds, maxWaitInMilliseconds,
                GetMapId(), ArchiveFolderId, getCurrentRuntime(), onComplete);
    }

    static void ArchiveFeatureOnMap(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds, long maxWaitInMilliseconds,
                               String destinationMapId, @Nullable String destinationFolderId,
                               R2cRuntime runtime, @Nullable Consumer<Boolean> onComplete) {
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null == destinationFolderId || destinationMapId.isEmpty()) {
            CTError(TAG, "archiveFeature(): can't archive - folder not created yet.");
            if (onComplete != null) onComplete.accept(false);
            return;
        }
        try {
            String trackId = feature.optString("id", "");
            if (trackId.isEmpty()) {
                CTError(TAG, "archiveFeature(): feature ID missing.");
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
            prop.put("folderId", destinationFolderId);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            prop.put("class", "Shape");  // convert from LiveTrack to shape.
            AtomicReference<CaltopoOp> deleteOp = new AtomicReference<>();
            CaltopoOp op = runtime.getCalTopoSessionGateway()
                    .editObjectOnMap(destinationMapId, "Shape", trackId, feature, archiveOp -> {
                        onArchiveFeatureEditFinished(trackId, archiveOp);
                        if (!archiveOp.success()) {
                            if (onComplete != null) onComplete.accept(false);
                            return;
                        }
                        if (!featureClass.equals("LiveTrack")) {
                            if (destinationMapId.equals(GetMapId())) refreshArtifactsAfterArchiveFinished(trackId, true);
                            if (onComplete != null) onComplete.accept(true);
                            return;
                        }
                        CTInfo(TAG, String.format(Locale.US, "archiveFeature(): Stopping liveTrack %s....", trackId));
                        deleteOp.set(runtime.getCalTopoSessionGateway()
                                .deleteLiveTrackOnMap(destinationMapId, trackId, finishedDeleteOp -> {
                                    CTInfo(TAG, String.format(Locale.US,
                                            "archiveFeature(): delete liveTrackId=%s success=%s responseCode=%d",
                                            trackId, finishedDeleteOp.success(), finishedDeleteOp.responseCode));
                                    if (destinationMapId.equals(GetMapId())) refreshArtifactsAfterArchiveFinished(
                                            trackId, finishedDeleteOp.success());
                                    if (onComplete != null) onComplete.accept(finishedDeleteOp.success());
                                }));
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
            CTError(TAG, "archiveFeature() raised:" + " (" + e.getClass().getSimpleName() + ")");
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

    /**
     * Track folder for a clue upload. Map status can be up before this id exists
     * (a refresh promotes status, and reset clears the id). Lookup or create it
     * here. Call from a worker: folder creation waits on the CalTopo request.
     */
    @Nullable
    public static String resolveTrackFolderIdForClue() {
        String existing = GetFolderId();
        if (existing != null && !existing.isEmpty()) return existing;
        if (onAndroidUiThread()) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): refused on the UI thread");
            return null;
        }
        String found = folderIdInArtifacts(FolderName);
        if (found != null) {
            FolderId = found;
            CTInfo(TAG, "resolveTrackFolderIdForClue(): found existing folder id=" + found);
            return found;
        }
        if (GetMapId().isEmpty()) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): map id is blank");
            return null;
        }
        CTInfo(TAG, "resolveTrackFolderIdForClue(): creating '" + FolderName + "'");
        CaltopoOp created = getCurrentRuntime().getCalTopoSessionGateway()
                .addFolder(FolderName, true, true, null);
        if (created == null) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): folder create was not started");
            return null;
        }
        try {
            created.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception error) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): folder create did not finish", error);
            return blankToNull(GetFolderId());
        }
        if (created.fail()) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): folder create failed " + created.responseString());
            return blankToNull(GetFolderId());
        }
        String createdId = created.id();
        if (createdId != null && !createdId.isEmpty() &&
                (GetFolderId() == null || GetFolderId().isEmpty())) {
            FolderId = createdId;
        }
        String resolved = blankToNull(GetFolderId());
        if (resolved == null) {
            CTWarn(TAG, "resolveTrackFolderIdForClue(): folder create returned no id");
        }
        return resolved;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean onAndroidUiThread() {
        Looper main = Looper.getMainLooper();
        return main != null && main.getThread() == Thread.currentThread();
    }

    @Nullable
    private static String folderIdInArtifacts(@Nullable String title) {
        if (title == null || title.isEmpty()) return null;
        for (JSONObject feature : GetArtifactFeatureSnapshot()) {
            JSONObject properties = feature.optJSONObject("properties");
            if (properties == null) continue;
            if (!"Folder".equals(properties.optString("class", ""))) continue;
            if (!title.equals(properties.optString("title", ""))) continue;
            String id = feature.optString("id", "");
            if (!id.isEmpty()) return id;
        }
        return null;
    }
    @Nullable
    public static String GetArchiveFolderId() { return ArchiveFolderId; }

    public static void Shutdown() {
        try {
            // Finish owned LiveTracks while the map session is still usable, then
            // force the map and tracker websocket down so a surviving process cannot
            // keep a background map connection after quit.
            try {
                finishOwnedTracksBlocking(DISCONNECT_FINISH_TIMEOUT_MS);
            } catch (Exception e) {
                CTError(TAG, "Shutdown(): finishOwnedTracksBlocking raised", e);
            }
            ShutdownInProgress = true;
            MapCheckerDelay.stop();
            VerifyTimer.stop();
            VerifyPhotoTimeout.stop();
            ProfileExpiryPoll.stop();
            InitialMarkerPublishDelay.stop();
            InitialMarkerPublishPending = false;
            InitialMarkerWaitStartedMs = 0L;
            DeferredExpiredProfileId = null;
            // Journal any leftovers; do not wait on network here.
            ResetMapConnection(0);
            removeMyDeviceMarker(0);
            MapNode = null;
            ActiveMapSelection = null;
            OperatingProfiles.endAssignment();
            SessionNodeMap = null;
            SetMapStatus(MapStatusListener.mapStatus.down, "Shutdown in progress.");
            getCurrentRuntime().getPeerCoordinator().stop(); // always stop; no-op if never started
            getCurrentRuntime().getCalTopoSessionGateway().shutdown();
        } catch (Exception e) {
            CTError(TAG, "Shutdown() raised: " + " (" + e.getClass().getSimpleName() + ")");
            // Ensure map/tracker cannot remain live after an exception mid-teardown.
            try {
                ShutdownInProgress = true;
                MapCheckerDelay.stop();
                MapNode = null;
                ActiveMapSelection = null;
                SetMapStatus(MapStatusListener.mapStatus.down, "Shutdown in progress.");
                getCurrentRuntime().getPeerCoordinator().stop();
            } catch (Exception ignored) { }
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
            CTError(TAG, "notifyArtifactFeature() listener raised" + " (" + e.getClass().getSimpleName() + ")");
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

    private static DeviceMarkerJournal markerJournal;
    private static final java.util.Set<String> activeMarkerKeys=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> deletingMarkerKeys=java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Worker-owned, one latest publication per marker while an earlier DELETE is in flight.
    private static final java.util.Map<String,Runnable> deferredMarkerPublications=new java.util.HashMap<>();
    private static java.util.function.Supplier<DeviceMarkerJournal> markerJournalFactory = () ->
            new DeviceMarkerJournal(org.ncssar.rid2caltopo.storage.OperationalStorage.INSTANCE.stateFile(
                    R2CApplication.getAppCtxt(), "device-marker-cleanup.json"));
    private static DeviceMarkerJournal markerJournal() {
        // This worker is the sole owner. Never hold the location/UI class monitor during storage preparation.
        CaltopoMarkerWork.requireWorker();
        if (markerJournal == null) markerJournal = markerJournalFactory.get();
        return markerJournal;
    }
    private static void retryMarkerCleanup() {
        CaltopoMarkerWork.execute(CaltopoMap::retryMarkerCleanupOnWorker);
    }
    private static void retryMarkerCleanupOnWorker() {
        try {
            String domain=CaltopoClient.GetCaltopoDomainAndPort();
            for(DeviceMarkerJournal.Entry entry:markerJournal().entries())
                if(entry.domain.equals(domain)&&!activeMarkerKeys.contains(entry.key()))deleteJournalMarker(entry);
        }catch(Exception error){CTWarn(TAG,"Marker cleanup journal unavailable; retry retained",error);}
    }
    /** A write response may name a different feature. Only an id this device published is safe to remove. */
    static String ownedDeviceMarkerId(@Nullable JSONObject result, @NonNull String deviceId) {
        if (result == null || deviceId.isEmpty()) return "";
        String returned = result.optString("id", "");
        JSONObject properties = result.optJSONObject("properties");
        String guid = properties == null ? "" : properties.optString(LOCAL_DEVICE_MARKER_GUID_PROP, "");
        if (!guid.isEmpty() && !deviceId.equals(guid)) return "";
        if (deviceId.equals(returned)) return returned;
        if (deviceId.equals(guid) && !returned.isEmpty() && returned.matches("[A-Za-z0-9_-]+")) return returned;
        return "";
    }
    /** Clue photos use the store id. Their media and link ids are name-based UUIDs of that store id. */
    static boolean clueDerivedMarkerId(@NonNull String markerId, @NonNull String clueId) {
        if (markerId.isEmpty() || clueId.isEmpty()) return false;
        if (markerId.equalsIgnoreCase(clueId)) return true;
        String media = UUID.nameUUIDFromBytes((clueId + ":media").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String link = UUID.nameUUIDFromBytes((clueId + ":link").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return markerId.equalsIgnoreCase(media) || markerId.equalsIgnoreCase(link);
    }
    static boolean provenOwnDeviceMarker(@NonNull DeviceMarkerJournal.Entry entry, @NonNull String deviceId) {
        if (deviceId.isEmpty() || entry.marker.isEmpty()) return false;
        if (deviceId.equals(entry.marker)) return true;
        return deviceId.equals(entry.deviceGuid);
    }
    static final class ClueCleanupIds {
        final boolean readable;
        final java.util.List<String> ids;
        ClueCleanupIds(boolean readable, java.util.List<String> ids) {
            this.readable = readable;
            this.ids = ids;
        }
    }
    static java.util.function.Supplier<ClueCleanupIds> clueCleanupIds = CaltopoMap::readClueCleanupIds;
    private static ClueCleanupIds readClueCleanupIds() {
        android.content.Context context = org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt();
        if (context == null) return new ClueCleanupIds(true, java.util.List.of());
        java.io.File index = new java.io.File(new java.io.File(context.getFilesDir(), "clues"), "clues.json");
        if (!index.isFile()) return new ClueCleanupIds(true, java.util.List.of());
        try {
            org.json.JSONArray array = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(index.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", "");
                if (!id.isEmpty()) ids.add(id);
            }
            return new ClueCleanupIds(true, ids);
        } catch (Exception error) {
            CTWarn(TAG, "Clue index unreadable; marker cleanup will not delete extra ids", error);
            return new ClueCleanupIds(false, java.util.List.of());
        }
    }
    private static boolean protectedClueId(@NonNull String markerId, @NonNull ClueCleanupIds clues) {
        for (String clueId : clues.ids) if (clueDerivedMarkerId(markerId, clueId)) return true;
        return false;
    }
    private static void dropUnprovenMarker(DeviceMarkerJournal.Entry entry, String reason) {
        CTWarn(TAG, "Marker cleanup refused " + reason + " map=" + entry.map + " id=" + entry.marker);
        try { markerJournal().acknowledge(entry); }
        catch (Exception error) { CTWarn(TAG, "Refused marker journal entry retained", error); }
    }
    private static CaltopoOp deleteJournalMarker(DeviceMarkerJournal.Entry entry) {
        return deleteJournalMarker(entry, getCurrentRuntime().getCalTopoSessionGateway().prepareMarkerCleanup(entry.map));
    }
    private static CaltopoOp deleteJournalMarker(DeviceMarkerJournal.Entry entry,
            java.util.function.BiFunction<String, java.util.function.Consumer<CaltopoOp>, CaltopoOp> cleanup) {
        String mine = GetMyUUID();
        if (mine.isEmpty()) {
            CTWarn(TAG, "Marker cleanup deferred; device id unavailable");
            return null;
        }
        ClueCleanupIds clues = clueCleanupIds.get();
        if (protectedClueId(entry.marker, clues)) {
            dropUnprovenMarker(entry, "clue marker");
            return null;
        }
        if (!clues.readable && !mine.equals(entry.marker)) {
            CTWarn(TAG, "Marker cleanup deferred; clue index unreadable id=" + entry.marker);
            return null;
        }
        // The installation id is this device's marker. Any other id needs a journal row that
        // recorded this device's guid when the feature was verified. Another device, a hand-drawn
        // marker, or an unverified response id is removed from the journal without a DELETE.
        if (!provenOwnDeviceMarker(entry, mine)) {
            dropUnprovenMarker(entry, "unproven marker");
            return null;
        }
        if(!entry.domain.equals(CaltopoClient.GetCaltopoDomainAndPort())||!deletingMarkerKeys.add(entry.key()))return null;
        try {
            return cleanup.apply(entry.marker,op->CaltopoMarkerWork.execute(()->{
                try {
                    if(op!=null&&op.success())markerJournal().acknowledge(entry);
                    CTDebug(TAG,"Marker cleanup map="+entry.map+" id="+entry.marker+" success="+(op!=null&&op.success()));
                }catch(Exception error){CTWarn(TAG,"Marker cleanup acknowledgement not persisted",error);}
                finally{
                    deletingMarkerKeys.remove(entry.key());
                    Runnable publication=deferredMarkerPublications.remove(entry.key());
                    if(publication!=null)publication.run();
                }
            }));
        }catch(Exception error){deletingMarkerKeys.remove(entry.key());CTWarn(TAG,"Marker cleanup deferred",error);return null;}
    }

    private static boolean markerContextIsCurrent(String domain, String map, String folder, long generation) {
        return !ShutdownInProgress && !DisconnectInProgress && MapStatus == MapStatusListener.mapStatus.up &&
                MapNode != null && MyLocation != null && generation == MapRefreshGeneration &&
                domain.equals(CaltopoClient.GetCaltopoDomainAndPort()) && map.equals(GetMapId()) &&
                folder.equals(FolderId);
    }

    private static void publishMarkerAfterCleanup(String key, Runnable publication) {
        CaltopoMarkerWork.requireWorker();
        if(deletingMarkerKeys.contains(key)) {
            deferredMarkerPublications.put(key,publication);
            CTDebug(TAG,"Device marker publication waiting for earlier cleanup key="+key);
        } else publication.run();
    }

    private static void publishMyDeviceMarkerIfPossible(@Nullable Location location) {
        if (ShutdownInProgress || DisconnectInProgress || MapStatus != MapStatusListener.mapStatus.up ||
                MapNode == null || FolderId == null || FolderId.isEmpty() || location == null) return;
        final String markerId = GetMyUUID();
        if (markerId.isEmpty()) return;
        final String domain = CaltopoClient.GetCaltopoDomainAndPort();
        final String map = GetMapId(), folder = FolderId, name = R2CActivity.MyDeviceName;
        final String key = domain + "/" + map + "/" + markerId;
        final long generation = MapRefreshGeneration;
        final double latitude = location.getLatitude(), longitude = location.getLongitude();
        final String description = buildMyDeviceMarkerDescription(), color = getLocalDeviceMarkerColor();
        final var cleanup = getCurrentRuntime().getCalTopoSessionGateway().prepareMarkerCleanup(map);
        // Reserve current intent before the worker can retry old journal entries. Otherwise each
        // poll queues DELETE first and then skips this publication because that DELETE is active.
        activeMarkerKeys.add(key);
        CaltopoMarkerWork.execute(() -> publishMarkerAfterCleanup(key, () -> {
            try {
                // Durable ownership must exist before any POST is constructed or queued.
                final DeviceMarkerJournal.Entry ownership = markerJournal().arm(domain, map, markerId, markerId);
                activeMarkerKeys.add(key);
                final JSONObject properties = new JSONObject()
                        .put(LOCAL_DEVICE_MARKER_NAME_PROP, name)
                        .put(LOCAL_DEVICE_MARKER_GUID_PROP, markerId)
                        .put(LOCAL_DEVICE_MARKER_LAST_SEEN_PROP, System.currentTimeMillis())
                        .put("description", description).put("marker-color", color);
                CaltopoMarkerWork.onMain(() -> {
                    if (!markerContextIsCurrent(domain, map, folder, generation)) {
                        activeMarkerKeys.remove(key);
                        CaltopoMarkerWork.execute(() -> deleteJournalMarker(ownership, cleanup));
                        return;
                    }
                    try {
                        getCurrentRuntime().getCalTopoSessionGateway().addMarker(latitude, longitude,
                                LOCAL_DEVICE_MARKER_TITLE_PREFIX + name, LOCAL_DEVICE_MARKER_SYMBOL,
                                folder, markerId, properties, markerOp -> CaltopoMarkerWork.execute(() -> {
                                    CTDebug(TAG,"Device marker publication map="+map+" success="+
                                            (markerOp!=null&&markerOp.success())+" responseCode="+
                                            (markerOp!=null?markerOp.responseCode:-1));
                                    String resolved = markerOp != null && markerOp.success()
                                            ? ownedDeviceMarkerId(markerOp.responseJson, markerId) : "";
                                    try {
                                        if (!resolved.isEmpty() && !resolved.equals(markerId)) {
                                            DeviceMarkerJournal.Entry entry = markerJournal().arm(domain, map, resolved, markerId);
                                            activeMarkerKeys.add(entry.key());
                                        }
                                    } catch (Exception error) { CTWarn(TAG, "Resolved marker journal retained for retry", error); }
                                    final String ownedId = resolved;
                                    CaltopoMarkerWork.onMain(() -> {
                                        if (!markerContextIsCurrent(domain, map, folder, generation)) {
                                            activeMarkerKeys.remove(key);
                                            if (!ownedId.isEmpty()) activeMarkerKeys.remove(domain + "/" + map + "/" + ownedId);
                                            CaltopoMarkerWork.execute(() -> {
                                                try {
                                                    // A late POST may follow an earlier DELETE; persist before compensating.
                                                    // Only the verified device marker is removed. A response that names
                                                    // another feature, such as a clue, is not armed or deleted.
                                                    deleteJournalMarker(markerJournal().arm(domain, map,
                                                            ownedId.isEmpty() ? markerId : ownedId, markerId), cleanup);
                                                } catch (Exception error) { CTWarn(TAG, "Late marker cleanup deferred", error); }
                                            });
                                            return;
                                        }
                                        if (markerOp != null && markerOp.success() && !ownedId.isEmpty()) {
                                            ResolvedMyDeviceMarkerId = ownedId;
                                            LastPublishedMyDeviceMarkerColor = color;
                                            LastPublishedMyDeviceMarkerDescription = description;
                                        }
                                    });
                                }));
                    } catch (Exception error) { CTWarn(TAG, "Device marker request deferred; ownership retained", error); }
                });
            } catch (Exception error) { CTWarn(TAG, "Device marker journal failed; nothing published", error); }
        }));
    }

    private static void removeStaleMyDeviceMarkers() {
        if (MapNode == null) return;
        String markerId = GetMyUUID();
        if (markerId.isEmpty()) return;
        ArrayList<String> staleIds = new ArrayList<>();
        synchronized (ArtifactLock) {
            for (JSONObject feature : ArtifactFeaturesById.values()) {
                if (feature == null) continue;
                String featureId = feature.optString("id", "");
                if (featureId.isEmpty() || markerId.equals(featureId)) continue;
                if (ResolvedMyDeviceMarkerId != null && ResolvedMyDeviceMarkerId.equals(featureId)) continue;
                JSONObject properties = feature.optJSONObject("properties");
                if (!isManagedR2cMarker(properties, LOCAL_DEVICE_MARKER_TITLE_PREFIX)) continue;
                if ("Drone".equals(properties.optString("marker-symbol", ""))) continue;
                // Only a second copy of this device's marker. Another tablet's guid, a matching
                // title, or an old heartbeat is not enough.
                if (markerId.equals(properties.optString(LOCAL_DEVICE_MARKER_GUID_PROP, ""))) {
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

    public static void BeginShutdownMarkerCleanup() {
        DisconnectInProgress=true;
        InitialMarkerPublishDelay.stop();
        removeMyDeviceMarker(4_000L);
    }

    private static void removeMyDeviceMarker(long maxWaitInMilliseconds) {
        if(MapNode==null)return;
        String map=GetMapId(),domain=CaltopoClient.GetCaltopoDomainAndPort();
        long wait=shouldWaitForMarkerDeleteAck()?Math.min(maxWaitInMilliseconds,MARKER_DELETE_WAIT_MS):0;
        final String markerID = GetMyUUID();
        final String resolvedID = ResolvedMyDeviceMarkerId;
        final var cleanup = getCurrentRuntime().getCalTopoSessionGateway().prepareMarkerCleanup(map);
        CaltopoMarkerWork.execute(() -> {
        try {
            if (!domain.equals(CaltopoClient.GetCaltopoDomainAndPort())) return;
            // Also enroll legacy markers published before the durable journal existed.
            markerJournal().arm(domain,map,markerID,markerID);
            if(resolvedID!=null&&!resolvedID.isEmpty()&&!resolvedID.equals(markerID))markerJournal().arm(domain,map,resolvedID,markerID);
            for(DeviceMarkerJournal.Entry entry:markerJournal().entries())if(entry.domain.equals(domain)&&entry.map.equals(map)) {
                activeMarkerKeys.remove(entry.key());
                CaltopoOp op=deleteJournalMarker(entry, cleanup);
                if(wait>0&&op!=null)try{op.syncOp(wait);}catch(java.util.concurrent.TimeoutException error){CTWarn(TAG,"Marker delete timed out; durable retry retained");}
            }
        }catch(Exception error){CTWarn(TAG,"Marker cleanup deferred",error);}
        });
    }

    private static boolean shouldWaitForMarkerDeleteAck() {
        return CaltopoNetworkReachability.hasValidatedInternet();
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
