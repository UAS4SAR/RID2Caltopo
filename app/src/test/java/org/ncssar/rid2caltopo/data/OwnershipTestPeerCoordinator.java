package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only peer coordinator that uses {@link PeerOwnershipEngine} directly and
 * exchanges observations through an in-memory hub.
 *
 * This lets us validate "two runtimes, one owner" behavior without relying on
 * the production static MQTT manager.
 */
public final class OwnershipTestPeerCoordinator implements PeerCoordinator {

    private static final class Observation implements PeerOwnershipEngine.ObservationView {
        @NonNull final String observerGuid;
        volatile double distMeters;
        volatile long firstSeenTs;

        Observation(@NonNull String observerGuid, double distMeters, long firstSeenTs) {
            this.observerGuid = observerGuid;
            this.distMeters = distMeters;
            this.firstSeenTs = firstSeenTs;
        }

        @NonNull
        @Override
        public String getObserverGuid() {
            return observerGuid;
        }

        @Override
        public double getDistMeters() {
            return distMeters;
        }

        @Override
        public long getFirstSeenTs() {
            return firstSeenTs;
        }

        @Override
        public boolean isExpired(long nowMs) {
            return false;
        }
    }

    @NonNull private final String runtimeLabel;
    @NonNull private final OwnershipTestPeerHub hub;
    @NonNull private final PeerOwnershipEngine ownershipEngine = new PeerOwnershipEngine();
    @NonNull private final Map<String, Map<String, Observation>> observationsByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final Map<String, String> ownerByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final Set<String> locallyOwnedRemoteIds = ConcurrentHashMap.newKeySet();
    @NonNull private final List<R2CMqttManager.PeerState> peerStates = Collections.synchronizedList(new ArrayList<>());
    @NonNull private final Set<String> startedTrackRemoteIds = ConcurrentHashMap.newKeySet();

    @Nullable private volatile String mapId;
    @Nullable private volatile String guid;
    @Nullable private volatile String name;
    @Nullable private volatile CalTopoSessionGateway calTopoSessionGateway;
    @Nullable private volatile TrackerPublisher trackerPublisher;
    @Nullable private volatile CoordinationIndicatorListener coordinationIndicatorListener;
    private volatile long caltopoRttMs = 2_000L;

    public OwnershipTestPeerCoordinator(@NonNull String runtimeLabel,
                                        @NonNull OwnershipTestPeerHub hub) {
        this.runtimeLabel = runtimeLabel;
        this.hub = hub;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        this.mapId = mapId;
        this.guid = guid;
        this.name = name;
        hub.register(this);
        refreshPeerStates();
    }

    @Override
    public void stop() {
        hub.unregister(this);
        observationsByRemoteId.clear();
        ownerByRemoteId.clear();
        locallyOwnedRemoteIds.clear();
        startedTrackRemoteIds.clear();
        peerStates.clear();
        coordinationIndicatorListener = null;
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        observeRemoteId(droneSpec.getRemoteId(), distMeters, firstSeenTs);
    }

    @Override
    public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters,
                                   long timestampMsec,
                                   @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        String remoteId = droneSpec.getRemoteId();
        long firstSeenTs = firstSeenTsFor(remoteId);
        observeRemoteId(remoteId, distMeters, firstSeenTs != 0L ? firstSeenTs : System.currentTimeMillis());
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        Map<String, Observation> observations = observationsByRemoteId.get(remoteId);
        if (observations != null && guid != null) {
            observations.remove(guid);
        }
        ownerByRemoteId.remove(remoteId);
        locallyOwnedRemoteIds.remove(remoteId);
        hub.removeObservation(this, remoteId);
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        return locallyOwnedRemoteIds.contains(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        caltopoRttMs = rttMs;
        refreshPeerStates();
        recomputeAllOwners();
    }

    @Override
    public long getCaltopoRttMs() {
        return caltopoRttMs;
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        // Not needed for ownership tests yet.
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        // Test coordinator does not currently push UI callbacks.
    }

    @Override
    public void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener) {
        coordinationIndicatorListener = listener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(getCoordinationIndicatorState());
        }
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        synchronized (peerStates) {
            return new ArrayList<>(peerStates);
        }
    }

    @NonNull
    @Override
    public CoordinationIndicatorState getCoordinationIndicatorState() {
        return guid != null ? CoordinationIndicatorState.HEALTHY : CoordinationIndicatorState.UNCONFIGURED;
    }

    public void observeRemoteId(@NonNull String remoteId, double distMeters, long firstSeenTs) {
        String localGuid = requireGuid();
        Map<String, Observation> observationMap = observationsByRemoteId.computeIfAbsent(
                remoteId, ignored -> new ConcurrentHashMap<>());
        Observation observation = observationMap.get(localGuid);
        if (observation == null) {
            observation = new Observation(localGuid, distMeters, firstSeenTs);
            observationMap.put(localGuid, observation);
        } else {
            observation.distMeters = distMeters;
            if (firstSeenTs < observation.firstSeenTs) observation.firstSeenTs = firstSeenTs;
        }
        recomputeOwner(remoteId);
        hub.publishObservation(this, remoteId, distMeters, observation.firstSeenTs);
    }

    void receiveObservation(@NonNull String observerGuid, @NonNull String remoteId,
                            double distMeters, long firstSeenTs) {
        Map<String, Observation> observationMap = observationsByRemoteId.computeIfAbsent(
                remoteId, ignored -> new ConcurrentHashMap<>());
        Observation observation = observationMap.get(observerGuid);
        if (observation == null) {
            observation = new Observation(observerGuid, distMeters, firstSeenTs);
            observationMap.put(observerGuid, observation);
        } else {
            observation.distMeters = distMeters;
            if (firstSeenTs < observation.firstSeenTs) observation.firstSeenTs = firstSeenTs;
        }
        recomputeOwner(remoteId);
        refreshPeerStates();
    }

    void removeObservationFor(@NonNull String observerGuid, @NonNull String remoteId) {
        Map<String, Observation> observationMap = observationsByRemoteId.get(remoteId);
        if (observationMap != null) {
            observationMap.remove(observerGuid);
        }
        recomputeOwner(remoteId);
        refreshPeerStates();
    }

    @Nullable
    public String getOwnerGuid(@NonNull String remoteId) {
        return ownerByRemoteId.get(remoteId);
    }

    @NonNull
    public String getGuidForTesting() {
        return requireGuid();
    }

    public long getCaltopoRttMsForTesting() {
        return caltopoRttMs;
    }

    public void setCalTopoSessionGatewayForTesting(@Nullable CalTopoSessionGateway calTopoSessionGateway) {
        this.calTopoSessionGateway = calTopoSessionGateway;
    }

    public void setTrackerPublisherForTesting(@Nullable TrackerPublisher trackerPublisher) {
        this.trackerPublisher = trackerPublisher;
    }

    public boolean publishTrackPointIfOwner(@NonNull String remoteId,
                                            double lat,
                                            double lng,
                                            double eleMeters) {
        if (!isLocalOwner(remoteId)) return false;
        CalTopoSessionGateway gateway = calTopoSessionGateway;
        if (gateway == null) return false;

        String deviceId = remoteId;
        if (startedTrackRemoteIds.add(remoteId)) {
            gateway.startLiveTrack(java.util.UUID.randomUUID().toString(), deviceId, remoteId, null, null, null, null);
        }
        gateway.addLiveTrackPoint(deviceId, lat, lng, eleMeters, null, null, null);
        return true;
    }

    public boolean publishTrackerIfOwner(@NonNull String remoteId,
                                         @NonNull String geoJsonString) {
        if (!isLocalOwner(remoteId)) return false;
        TrackerPublisher publisher = trackerPublisher;
        if (publisher == null) return false;
        publisher.publishGeoJson(geoJsonString);
        return true;
    }

    private void recomputeAllOwners() {
        for (String remoteId : observationsByRemoteId.keySet()) {
            recomputeOwner(remoteId);
        }
    }

    private void recomputeOwner(@NonNull String remoteId) {
        Map<String, Observation> observationMap = observationsByRemoteId.get(remoteId);
        if (observationMap == null || observationMap.isEmpty()) {
            ownerByRemoteId.remove(remoteId);
            locallyOwnedRemoteIds.remove(remoteId);
            return;
        }
        PeerOwnershipEngine.ScoreResult result = ownershipEngine.selectOwner(
                observationMap.values(),
                hub::lookupRttMs,
                System.currentTimeMillis()
        );
        if (result.ownerGuid == null) {
            ownerByRemoteId.remove(remoteId);
            locallyOwnedRemoteIds.remove(remoteId);
            return;
        }
        ownerByRemoteId.put(remoteId, result.ownerGuid);
        if (result.ownerGuid.equals(requireGuid())) {
            locallyOwnedRemoteIds.add(remoteId);
        } else {
            locallyOwnedRemoteIds.remove(remoteId);
        }
    }

    private long firstSeenTsFor(@NonNull String remoteId) {
        Map<String, Observation> observationMap = observationsByRemoteId.get(remoteId);
        if (observationMap == null) return 0L;
        Observation observation = observationMap.get(requireGuid());
        return observation != null ? observation.firstSeenTs : 0L;
    }

    private void refreshPeerStates() {
        synchronized (peerStates) {
            peerStates.clear();
            for (OwnershipTestPeerCoordinator coordinator : hub.snapshotCoordinators()) {
                if (coordinator == this) continue;
                R2CMqttManager.PeerState ps = new R2CMqttManager.PeerState(coordinator.getGuidForTesting());
                ps.name = coordinator.name != null ? coordinator.name : coordinator.runtimeLabel;
                ps.caltopoRttMs = coordinator.getCaltopoRttMsForTesting();
                ps.online = true;
                ps.lastSeenMs = System.currentTimeMillis();
                peerStates.add(ps);
            }
        }
    }

    @NonNull
    private String requireGuid() {
        if (guid == null) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Coordinator '%s' has not been started.", runtimeLabel));
        }
        return guid;
    }
}
