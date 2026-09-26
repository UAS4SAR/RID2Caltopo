/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.bluetooth;

import android.bluetooth.le.ScanResult;

import androidx.annotation.Nullable;

import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.opendroneid.android.Constants;
import org.opendroneid.android.data.AircraftObject;
import org.opendroneid.android.data.Connection;
import org.opendroneid.android.data.Identification;
import org.opendroneid.android.data.AuthenticationData;
import org.opendroneid.android.data.LocationData;
import org.opendroneid.android.data.SelfIdData;
import org.opendroneid.android.data.SystemData;
import org.opendroneid.android.data.OperatorIdData;
import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.RidLocationAccuracyPrefs;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.util.Arrays;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class OpenDroneIdDataManager {
    static final double RID_INVALID_ALTITUDE_METERS = -1000.0;

    public final ConcurrentHashMap<Long, AircraftObject> aircraft = new ConcurrentHashMap<>();

    static class SelectedTrackAltitude {
        final long roundedMeters;
        final Double geodeticMinusRidHeightMeters;

        SelectedTrackAltitude(long roundedMeters, Double geodeticMinusRidHeightMeters) {
            this.roundedMeters = roundedMeters;
            this.geodeticMinusRidHeightMeters = geodeticMinusRidHeightMeters;
        }
    }

    private static final String TAG = "OpenDroneIdDataManager";
    private static final int RID_INGEST_QUEUE_CAPACITY = 2048;
    private static final long RID_INGEST_SLOW_PROCESSING_MS = 250L;
    private static final long RID_INGEST_SLOW_PROCESSING_LOG_LIMIT = 10L;
    private static final long RID_INGEST_FAILURE_LOG_LIMIT = 10L;

    public android.location.Location receiverLocation;

    private final Callback callback;
    private final ThreadPoolExecutor ridIngestExecutor;
    private final AtomicLong droppedRidIngestPackets = new AtomicLong(0L);
    private final AtomicLong slowRidIngestPacketsLogged = new AtomicLong(0L);
    private final AtomicLong failedRidIngestPacketsLogged = new AtomicLong(0L);
    private final AtomicLong receivedBluetoothPackets = new AtomicLong(0L);
    private final AtomicLong lowLevelBridgePings = new AtomicLong(0L);

    // Tracks the last-logged altitude-source key per drone to suppress duplicate log spam.
    // Logged once on first contact and again whenever the key changes (altSource, isAtoType, fallback).
    private final ConcurrentHashMap<String, String> lastLoggedAltKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> lastLoggedHorizontalAccuracyCode = new ConcurrentHashMap<>();

    public static class Callback {
        public void onNewAircraft(AircraftObject object) {}
    }

    public OpenDroneIdDataManager(Callback callback) {
        this.callback = callback;
        ridIngestExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(RID_INGEST_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "RID-ingest");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> {
                    long dropped = droppedRidIngestPackets.incrementAndGet();
                    if (dropped == 1 || dropped % 100 == 0) {
                        CaltopoClient.CTError(TAG, String.format(Locale.US,
                                "RID ingest queue full; dropped packet count=%d queueSize=%d",
                                dropped, executor.getQueue().size()));
                    }
                }
        );
    }

    public ConcurrentHashMap<Long, AircraftObject> getAircraft() {
        return aircraft;
    }

    public int getRidIngestQueueDepth() {
        return ridIngestExecutor.getQueue().size();
    }

    public long getDroppedRidIngestPacketCount() {
        return droppedRidIngestPackets.get();
    }

    public long getSlowRidIngestPacketCount() {
        return slowRidIngestPacketsLogged.get();
    }

    public long getFailedRidIngestPacketCount() {
        return failedRidIngestPacketsLogged.get();
    }

    static long ridTimestampTenthsToUtcMsec(double locationTimestampTenths, long nowWallMsec) {
        long rawTenths = Math.round(locationTimestampTenths);
        if (rawTenths == 0xFFFFL) {
            return 0L; // invalid/unknown
        }
        if (rawTenths < 0L) {
            rawTenths = 0L;
        }
        if (rawTenths > 36_000L) {
            CaltopoClient.CTError(TAG, String.format(Locale.US,
                    "Received invalid location timestamp tenths:%d", rawTenths));
            rawTenths = rawTenths % 36_000L;
        }
        // Timestamp is time within current UTC hour in 0.1 second units.
        long withinHourMsec = (rawTenths % 36_000L) * 100L;
        long hourMsec = 60L * 60L * 1000L;
        long baseHourMsec = (nowWallMsec / hourMsec) * hourMsec;

        long candidatePrev = baseHourMsec - hourMsec + withinHourMsec;
        long candidateThis = baseHourMsec + withinHourMsec;
        long candidateNext = baseHourMsec + hourMsec + withinHourMsec;

        long best = candidateThis;
        long bestDelta = Math.abs(nowWallMsec - candidateThis);
        long prevDelta = Math.abs(nowWallMsec - candidatePrev);
        if (prevDelta < bestDelta) {
            best = candidatePrev;
            bestDelta = prevDelta;
        }
        long nextDelta = Math.abs(nowWallMsec - candidateNext);
        if (nextDelta < bestDelta) {
            best = candidateNext;
        }
        return best;
    }

    private static boolean isRidAltitudeValid(double altitudeMeters) {
        return altitudeMeters != RID_INVALID_ALTITUDE_METERS && Double.isFinite(altitudeMeters);
    }

    /** F3411-19 Table 2: NACp codes 9-12 are supported, subject to the saved minimum threshold. */
    static boolean isHorizontalAccuracySufficient(int horizontalAccuracyCode) {
        return horizontalAccuracyCode >= RidLocationAccuracyPrefs.getMinimumCode(R2CApplication.getAppCtxt())
                && horizontalAccuracyCode <= 12;
    }

    @Nullable
    static Double validRidDirectionDegrees(double directionDegrees) {
        return Double.isFinite(directionDegrees)
                && directionDegrees >= 0.0
                && directionDegrees <= 360.0
                ? directionDegrees
                : null;
    }

    @Nullable
    static Double validRidHorizontalSpeedKnots(double speedMetersPerSecond) {
        return Double.isFinite(speedMetersPerSecond) && speedMetersPerSecond != 255.0
                ? speedMetersPerSecond * 1.94384449
                : null;
    }

    @Nullable
    static Double validRidVerticalRateFpm(double speedMetersPerSecond) {
        return Double.isFinite(speedMetersPerSecond) && speedMetersPerSecond != 63.0
                ? speedMetersPerSecond * 196.850394
                : null;
    }

    static SelectedTrackAltitude selectTrackAltitudeMeters(
            double ridHeightMeters,
            double altitudeMslMeters,
            Double geodeticMinusRidHeightMeters
    ) {
        boolean ridHeightValid = isRidAltitudeValid(ridHeightMeters);
        boolean altitudeMslValid = isRidAltitudeValid(altitudeMslMeters);
        double selectedTrackAltitudeMeters;
        Double nextReference = geodeticMinusRidHeightMeters;
        if (ridHeightValid) {
            // Primary source for ATO: RID height (relative to takeoff/ground semantics from aircraft).
            selectedTrackAltitudeMeters = ridHeightMeters;
            if (altitudeMslValid) {
                nextReference = altitudeMslMeters - ridHeightMeters;
            }
        } else if (geodeticMinusRidHeightMeters != null && altitudeMslValid) {
            // Keep continuity if RID height temporarily drops out.
            selectedTrackAltitudeMeters = altitudeMslMeters - geodeticMinusRidHeightMeters;
        } else {
            // Last resort if we have no relative-height reference yet.
            selectedTrackAltitudeMeters = altitudeMslMeters;
        }
        return new SelectedTrackAltitude(Math.round(selectedTrackAltitudeMeters), nextReference);
    }

    void receiveDataBluetooth(byte[] serviceData, ScanResult result, CtDroneSpec.TransportTypeEnum transportType) {
        String macAddress = result.getDevice().getAddress();
        long timestampNanos = result.getTimestampNanos();
        int rssi = result.getRssi();
        byte[] packet = Arrays.copyOf(serviceData, serviceData.length);
        android.location.Location location = receiverLocation;
        long bluetoothPacketCount = receivedBluetoothPackets.incrementAndGet();
        String bridgePingIdentity = bluetoothRelayPingIdentity(packet, timestampNanos, location);
        boolean bridgePingAlreadyNoted = bridgePingIdentity != null;
        if (bridgePingAlreadyNoted) {
            long pingCount = lowLevelBridgePings.incrementAndGet();
            DroneScoutBridgeMonitor.noteCandidate(bridgePingIdentity, rssi);
            DroneScoutBridgeSignal bridgeSignal = DroneScoutBridgeMonitor.INSTANCE
                    .getSignal().getValue();
            CaltopoClient.CTInfo(
                    "DroneScoutBridge",
                    String.format(Locale.US,
                            "Bridge event=%d classification=relay-ping source=bluetooth-callback " +
                                    "bluetoothPacket=%d relayPing=%d transmitter=%s " +
                                    "aircraft=%s bridgeRssiDbm=%d bytes=%d",
                            bridgeSignal != null ? bridgeSignal.getEventCount() : -1,
                            bluetoothPacketCount,
                            pingCount,
                            macAddress,
                            bridgePingIdentity,
                            rssi,
                            packet.length));
        }

        enqueueRidPacket(
                String.format(Locale.US,
                        "processBluetoothPacket(mac=%s,rssi=%d,transport=%s,bytes=%d,timestampNanos=%d)",
                        macAddress, rssi, transportType, packet.length, timestampNanos),
                () -> processBluetoothPacket(
                        packet, timestampNanos, macAddress, rssi, transportType, location,
                        bridgePingAlreadyNoted));
    }

    private void processBluetoothPacket(byte[] data, long timestampNanos, String macAddress, int rssi,
                                        CtDroneSpec.TransportTypeEnum transportType,
                                        android.location.Location location,
                                        boolean bridgePingAlreadyNoted) {
        String macAddressCleaned = macAddress.replace(":", "");
        long macAddressLong = Long.parseLong(macAddressCleaned,16);
        OpenDroneIdParser.Message<?> message = parseBluetoothServiceData(
                data, timestampNanos, location);
        if (message == null)
            return;
        receiveData(timestampNanos, macAddress, macAddressLong, rssi,
                    message, transportType, bridgePingAlreadyNoted);
    }

    static OpenDroneIdParser.Message<?> parseBluetoothServiceData(
            byte[] serviceData,
            long timestampNanos,
            android.location.Location receiverLocation) {
        // Android's ScanRecord#getServiceData strips the AD length/type and 16-bit UUID.
        // The remaining bytes are application code, message counter, then the ODID message.
        return OpenDroneIdParser.parseData(serviceData, 2, timestampNanos, receiverLocation);
    }

    @Nullable
    static String bluetoothRelayPingIdentity(
            byte[] serviceData,
            long timestampNanos,
            android.location.Location receiverLocation) {
        OpenDroneIdParser.Message<?> message = parseBluetoothServiceData(
                serviceData, timestampNanos, receiverLocation);
        return relayPingIdentity(message, receiverLocation);
    }

    @Nullable
    private static String relayPingIdentity(
            OpenDroneIdParser.Message<?> message,
            android.location.Location receiverLocation) {
        if (message == null || message.payload == null) return null;
        if (message.header.type == OpenDroneIdParser.Type.BASIC_ID) {
            OpenDroneIdParser.BasicId basicId = (OpenDroneIdParser.BasicId) message.payload;
            String identity = new String(basicId.uasId, StandardCharsets.US_ASCII);
            return DroneScoutBridgeMonitor.isRelayPingIdentity(identity) ? identity : null;
        }
        if (message.header.type != OpenDroneIdParser.Type.MESSAGE_PACK) return null;
        OpenDroneIdParser.MessagePack pack = (OpenDroneIdParser.MessagePack) message.payload;
        if (pack.messageSize != Constants.MAX_MESSAGE_SIZE ||
                pack.messagesInPack <= 0 ||
                pack.messagesInPack > Constants.MAX_MESSAGES_IN_PACK) {
            return null;
        }
        for (int i = 0; i < pack.messagesInPack; i++) {
            int offset = i * pack.messageSize;
            byte[] data = Arrays.copyOfRange(pack.messages, offset, offset + pack.messageSize);
            OpenDroneIdParser.Message<?> subMessage = OpenDroneIdParser.parseMessage(
                    data, 0, message.timestamp, receiverLocation, message.msgCounter);
            String identity = relayPingIdentity(subMessage, receiverLocation);
            if (identity != null) return identity;
        }
        return null;
    }

    void receiveDataNaN(byte[] data, int peerHash, long timeNano, CtDroneSpec.TransportTypeEnum transportType) {
        byte[] packet = Arrays.copyOf(data, data.length);
        android.location.Location location = receiverLocation;
        enqueueRidPacket(
                String.format(Locale.US,
                        "processNaNPacket(peerHash=%d,transport=%s,bytes=%d,timeNano=%d)",
                        peerHash, transportType, packet.length, timeNano),
                () -> processNaNPacket(packet, peerHash, timeNano, transportType, location));
    }

    private void processNaNPacket(byte[] data, int peerHash, long timeNano,
                                  CtDroneSpec.TransportTypeEnum transportType,
                                  android.location.Location location) {
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseData(data, 1, timeNano, location);
        if (message == null) {
            CaltopoClient.CTError(TAG, "Not able to parse NaN data.");
            return;
        }
        CaltopoClient.CTInfo(TAG, "Caltopo: Wireless NaN for NAN ID: " + peerHash);
        receiveData(timeNano, "NaN ID: " + peerHash, peerHash, 0, message, transportType);
    }

    void receiveDataWiFiBeacon(byte[] data, String mac, long macLong, int rssi, long timeNano,
                               CtDroneSpec.TransportTypeEnum transportType) {
        byte[] packet = Arrays.copyOf(data, data.length);
        android.location.Location location = receiverLocation;
        enqueueRidPacket(
                String.format(Locale.US,
                        "processWiFiBeaconPacket(mac=%s,macLong=%d,rssi=%d,transport=%s,bytes=%d,timeNano=%d)",
                        mac, macLong, rssi, transportType, packet.length, timeNano),
                () -> processWiFiBeaconPacket(
                        packet, mac, macLong, rssi, timeNano, transportType, location));
    }

    private void processWiFiBeaconPacket(byte[] data, String mac, long macLong, int rssi, long timeNano,
                                         CtDroneSpec.TransportTypeEnum transportType,
                                         android.location.Location location) {
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseData(data, 1, timeNano, location);
        if (message == null)
            return;
        receiveData(timeNano, mac, macLong, rssi, message, transportType);
    }

    private void enqueueRidPacket(String description, Runnable runnable) {
        ridIngestExecutor.execute(() -> {
            long startedAtMs = System.currentTimeMillis();
            try {
                runnable.run();
            } catch (Exception e) {
                long logged = failedRidIngestPacketsLogged.incrementAndGet();
                if (shouldLogLimitedOccurrence(logged, RID_INGEST_FAILURE_LOG_LIMIT)) {
                    CaltopoClient.CTError(TAG, String.format(Locale.US,
                            "RID ingest worker failed while processing packet occurrence=%d/%d.",
                            logged, RID_INGEST_FAILURE_LOG_LIMIT), e);
                }
            } finally {
                long elapsedMs = System.currentTimeMillis() - startedAtMs;
                if (elapsedMs >= RID_INGEST_SLOW_PROCESSING_MS) {
                    long logged = slowRidIngestPacketsLogged.incrementAndGet();
                    if (shouldLogLimitedOccurrence(logged, RID_INGEST_SLOW_PROCESSING_LOG_LIMIT)) {
                        CaltopoClient.CTError(TAG, String.format(Locale.US,
                                "RID ingest slow processing elapsedMs=%d occurrence=%d/%d task=%s",
                                elapsedMs, logged, RID_INGEST_SLOW_PROCESSING_LOG_LIMIT, description));
                    }
                }
            }
        });
    }

    static boolean shouldLogLimitedOccurrence(long occurrence, long limit) {
        return occurrence >= 1L && occurrence <= limit;
    }

    // So many standards to choose from.  Pick them in priority order and hope the drone
    // doesn't vacillate between measurement types.
    double getAltitudeInMeters(LocationData location) {
        double altitudeInMeters = -1000.0;
        double atmoAltMeters = location.getAltitudePressure();           // use first if valid.
        if (isRidAltitudeValid(atmoAltMeters)) {
            altitudeInMeters = atmoAltMeters;
        } else {
            double altitudeMslMeters = location.getAltitudeGeodetic();   // use only if atmo invalid.
            if (isRidAltitudeValid(altitudeMslMeters)) {
                altitudeInMeters = altitudeMslMeters;
            } else {
                double ridHeightMeters = location.getHeight();           // use only if atmo and geo invalid.
                if (isRidAltitudeValid(ridHeightMeters)) {
                    altitudeInMeters = ridHeightMeters;
                }
            }
        }
        return altitudeInMeters;
    }

    void updateCaltopo(AircraftObject ac, CtDroneSpec.TransportTypeEnum transportType,
                       @Nullable DroneScoutRelayMetadata relayMetadata) {
        Identification acId = ac.getIdentification1();

        // Some drones (e.g. DS154) broadcast TWO BASIC_ID messages in the same message pack:
        // one with IdType = Serial_Number and one with IdType = UTM_Assigned_ID / Session_ID.
        // Depending on the order they appear in the pack, either type can land in identification1.
        // Always prefer the Serial_Number for stable, long-lived drone tracking; if identification1
        // is a non-serial type but identification2 is a serial number, promote identification2.
        Identification acId2 = ac.getIdentification2();
        if (acId != null
                && acId.getIdType() != Identification.IdTypeEnum.Serial_Number
                && acId2 != null
                && acId2.getIdType() == Identification.IdTypeEnum.Serial_Number) {
            acId = acId2;
        }

        if (null == acId) return;
        String rawStr = acId.getUasIdAsString();
        if (null == rawStr) return;
        // remove nulls and any other garbage from idstr:
        String idStr = rawStr.replaceAll("[^A-Z0-9]", "");
        if (idStr.isEmpty()) {
            CtDroneSpec.BumpInvalidWaypointCount();
            if (CaltopoClient.DebugLevel > CaltopoClient.DebugLevelDebug) {
                CaltopoClient.CTInfo(TAG, String.format(Locale.US,
                        "updateCaltopo(): Ignoring message with invalid id from mac:0x%x transport:%s",
                        ac.getMacAddress(), transportType));
            }
            return;
        }

        LocationData location = ac.getLocation();
        if (null == location) return;

        CaltopoClient client = CaltopoClient.ClientForRemoteId(idStr);
        CtDroneSpec droneSpec = client.getDroneSpec();
        if (relayMetadata != null) {
            droneSpec.updateDroneToBridgeRssi(
                    relayMetadata.getDroneToBridgeRssiDbm(),
                    relayMetadata.getReceptionMode());
        }

        long nowWallMsec = System.currentTimeMillis();
        long timestampInMilliseconds = ridTimestampTenthsToUtcMsec(location.getLocationTimestamp(), nowWallMsec);
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        double altitudeInMeters = getAltitudeInMeters(location);

        // Count any received RID position packet as connectivity, even if waypoint-quality
        // filters later reject it for tracking purposes.
        droneSpec.noteRidPositionPacketReceived(nowWallMsec);

        if (org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry.hasFreshPosition(idStr, nowWallMsec)) return;

        int horizontalAccuracyCode = location.getHorizontalAccuracyCode();
        if (!isHorizontalAccuracySufficient(horizontalAccuracyCode)) {
            // The packet was received and decoded, but its position quality is below
            // the operator-selected threshold. Count it in the operator-facing invalid
            // RID total as well as the detailed accuracy diagnostic.
            CtDroneSpec.BumpInvalidWaypointCount();
            Integer previousCode = lastLoggedHorizontalAccuracyCode.put(idStr, horizontalAccuracyCode);
            if (previousCode == null || previousCode != horizontalAccuracyCode) {
                CaltopoClient.CTDebug(TAG, String.format(Locale.US,
                                "rid_filter remoteId=%s reason=horizontal_accuracy code=%d requiredCode=%d",
                                idStr, horizontalAccuracyCode,
                                RidLocationAccuracyPrefs.getMinimumCode(R2CApplication.getAppCtxt())));
            }
            return;
        }
        lastLoggedHorizontalAccuracyCode.put(idStr, horizontalAccuracyCode);

        // feed altitude context into droneSpec for cross-broadcast tracking.
        CtDroneSpec.AltSourceEnum altSource =
                isRidAltitudeValid(location.getAltitudePressure())  ? CtDroneSpec.AltSourceEnum.BARO
                        : isRidAltitudeValid(location.getAltitudeGeodetic())  ? CtDroneSpec.AltSourceEnum.GEODETIC
                        : CtDroneSpec.AltSourceEnum.NONE;
        double ridHeightM = location.getHeight();
        boolean isAtoType = location.getHeightType() == LocationData.heightTypeEnum.Takeoff;
        // Avoid feeding ridHeight as absAltM: if neither baro nor geodetic alt is available,
        // getAltitudeInMeters() fell back to ridHeight, making absAltM == ridHeightM → sample=0.
        // Pass -1000.0 in that case so updateAltitudeContext's validity check short-circuits.
        boolean absAltFromRidFallback = !isRidAltitudeValid(location.getAltitudePressure())
                && !isRidAltitudeValid(location.getAltitudeGeodetic());
        // Log altitude source context once on first contact and again on any change — not every packet.
        if (CaltopoClient.CTDebugEnabled(TAG)) {
            String altKey = altSource + ":" + isAtoType + ":" + absAltFromRidFallback;
            if (!altKey.equals(lastLoggedAltKey.put(idStr, altKey))) {
                CaltopoClient.CTDebug(TAG, String.format(Locale.US,
                        "updateCaltopo(%s): atmoAlt=%.1f geodeticAlt=%.1f ridHeight=%.1f isAto=%b ridFallback=%b",
                        idStr,
                        location.getAltitudePressure(), location.getAltitudeGeodetic(),
                        ridHeightM, isAtoType, absAltFromRidFallback));
            }
        }
        LocationData.StatusEnum status = location.getStatus();
        Boolean airborne = (status == LocationData.StatusEnum.Airborne || status == LocationData.StatusEnum.Ground)
                ? status == LocationData.StatusEnum.Airborne
                : null;

        // Let the droneSpec be final arbiter of what constitutes a reasonable waypoint.
        if (!droneSpec.checkNewWaypoint(lat, lng, altitudeInMeters, timestampInMilliseconds, nowWallMsec, airborne, transportType,
                org.ncssar.rid2caltopo.data.ProximityTelemetry.fromRid(horizontalAccuracyCode,
                        location.getAltitudeGeodetic(), location.getAltitudePressure(),
                        location.getVerticalAccuracy().ordinal(), location.getBaroAccuracy().ordinal()))) return;

        // A relayed packet's callback RSSI is bridge-to-device, not drone-to-device. Preserve
        // the direct per-transport RSSI until another genuinely direct packet updates it.
        if (relayMetadata == null) {
            droneSpec.updateLastRssi(ac.getConnection().rssi, transportType);
        }

        // Only update altitude context for waypoints that passed all validity checks.
        droneSpec.updateAltitudeContext(
                absAltFromRidFallback ? -1000.0 : altitudeInMeters, altSource,
                isRidAltitudeValid(ridHeightM) ? ridHeightM : -1000.0,
                isAtoType);

        // Telemetry is always optional:
        double speedVerticalMps = location.getSpeedVertical();
        double speedGroundMps = location.getSpeedHorizontal();
        double directionDeg = location.getDirection();
        Double aircraftAltitudeRateFpm = validRidVerticalRateFpm(speedVerticalMps);
        Double aircraftGsKnots = validRidHorizontalSpeedKnots(speedGroundMps);
        Double aircraftTrackDeg = validRidDirectionDegrees(directionDeg);
        // ASTM RID does not provide true nose heading, so use direction as a heading proxy.
        if (aircraftAltitudeRateFpm != null || aircraftGsKnots != null || aircraftTrackDeg != null) {
            CtDroneSpec.PositionTelemetry telemetry = new CtDroneSpec.PositionTelemetry(
                    aircraftAltitudeRateFpm,
                    aircraftGsKnots,
                    aircraftTrackDeg
            );
            droneSpec.setLastPositionTelemetry(telemetry);

        }
        // When only ridHeight is available (absAltFromRidFallback), altitudeInMeters is a
        // relative ATO/AGL value, not an MSL reference.  Sending it to Caltopo as an absolute
        // altitude would mislead the server and — more critically — MapPane's fallback
        // calibration would store ridHeight as takeoffTrackAltitudeM, producing a garbage
        // correctionF (e.g. 518 m ridHeight vs 1569 m DEM → -1051 m error).
        // Pass -1000.0 (RID invalid sentinel) so MapPane skips the fallback calibration.
        // Position (lat/lng) is still recorded; ATO display uses spec.getLastRidHeightM() directly.
        double trackAltitudeM = absAltFromRidFallback ? -1000.0 : altitudeInMeters;
        client.newWaypoint(lat, lng, trackAltitudeM, timestampInMilliseconds, transportType, airborne);
    }

    @SuppressWarnings("unchecked")
    void receiveData(long timeNano, String macAddress, long macAddressLong, int rssi,
                     OpenDroneIdParser.Message<?> message, CtDroneSpec.TransportTypeEnum transportType) {
        receiveData(timeNano, macAddress, macAddressLong, rssi, message, transportType, false);
    }

    @SuppressWarnings("unchecked")
    private void receiveData(long timeNano, String macAddress, long macAddressLong, int rssi,
                     OpenDroneIdParser.Message<?> message, CtDroneSpec.TransportTypeEnum transportType,
                     boolean bridgePingAlreadyNoted) {

        // Handle connection
        boolean newAircraft = false;

        /* FIXME: This assumes that macAddressLong doesn't change.
         *  Unfortunately, the remote mac address for wireless NaN messages changes.  The spec for the interface
         *  says not to rely on it for a unique handle to the remote.   So, we really need to parse the message in
         *  a common data buffer, then use that information to look up the Remote ID (Serial Number) and use that
         *  as the unique handle to reference an aircraft.  Note that some aircraft remote modules broadcast both
         *  Wireless and Bluetooth, so ideally should keep track of transport information for each within a single
         *  aircraft instance.
         */
        AircraftObject ac = aircraft.get(macAddressLong);
        if (ac == null) {
            ac = createNewAircraft(macAddress, macAddressLong);
            newAircraft = true;
        }
        long currentTime = System.currentTimeMillis();
        ac.getConnection().msgDelta = currentTime - ac.getConnection().lastSeen;
        ac.getConnection().lastSeen = currentTime;
        ac.getConnection().rssi = rssi;
        ac.getConnection().transportType = transportType;
        ac.getConnection().setTimestamp(timeNano);
        ac.getConnection().setMsgVersion(message.header.version);
        ac.updateConnection(ac.getConnection());

        if (newAircraft) {
            aircraft.put(macAddressLong, ac);
            if (null != callback) callback.onNewAircraft(ac);
        }

        boolean locationUpdated;
        if (message.header.type == OpenDroneIdParser.Type.MESSAGE_PACK) {
            locationUpdated = handleMessagePack(ac,
                    (OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack>) message,
                    timeNano, message.msgCounter);
        } else {
            locationUpdated = handleMessages(ac, message);
        }

        DroneScoutRelayMetadata relayMetadata = droneScoutRelayMetadata(ac);
        if (relayMetadata != null) {
            long bridgeEventCount = DroneScoutBridgeMonitor.noteRelayedPacket(rssi);
            CaltopoClient.CTDebug(
                    "DroneScoutBridge",
                    String.format(Locale.US,
                            "Bridge event=%d classification=relayed-aircraft transmitter=%s " +
                                    "messageCounter=%d messageType=%s locationUpdated=%s " +
                                    "aircraft=%s bridgeRssiDbm=%d " +
                                    "droneToBridgeRssiDbm=%d mode=%s",
                            bridgeEventCount,
                            macAddress,
                            message.msgCounter,
                            message.header.type,
                            locationUpdated,
                            preferredAircraftIdentity(ac),
                            rssi,
                            relayMetadata.getDroneToBridgeRssiDbm(),
                            relayMetadata.getReceptionMode()));
        }
        boolean isDroneScoutBridgePing = bridgePingAlreadyNoted || noteDroneScoutBridgePing(
                ac, rssi, macAddress, message.msgCounter);

        if (!isDroneScoutBridgePing) {
            CtDroneSpec.NoteRidMessageReceived(currentTime);
            noteActiveAircraftMessage(ac, currentTime);
        }

        if (locationUpdated && !isDroneScoutBridgePing) {
            updateCaltopo(ac, transportType, relayMetadata);
        }
    }

    private void noteActiveAircraftMessage(AircraftObject ac, long nowWallMsec) {
        String rawIdentity = preferredAircraftIdentity(ac);
        if (rawIdentity == null) return;
        String remoteId = rawIdentity.replaceAll("[^A-Z0-9]", "");
        if (remoteId.isEmpty() || "UNKNOWN".equals(remoteId)) return;
        CaltopoClient.NoteActiveAircraftMessageReceived(remoteId, nowWallMsec);
    }

    @Nullable
    private DroneScoutRelayMetadata droneScoutRelayMetadata(AircraftObject ac) {
        SelfIdData selfID = ac.getSelfID();
        if (selfID == null) return null;
        return DroneScoutRelayMetadata.parse(selfID.getOperationDescriptionAsString());
    }

    private boolean noteDroneScoutBridgePing(
            AircraftObject ac, int rssi, String transmitter, int messageCounter) {
        Identification primary = ac.getIdentification1();
        Identification secondary = ac.getIdentification2();
        String primaryIdentity = primary != null ? primary.getUasIdAsString() : null;
        String secondaryIdentity = secondary != null ? secondary.getUasIdAsString() : null;
        boolean primaryMatches = DroneScoutBridgeMonitor.isRelayPingIdentity(primaryIdentity);
        boolean secondaryMatches = DroneScoutBridgeMonitor.isRelayPingIdentity(secondaryIdentity);
        if (!primaryMatches && !secondaryMatches) return false;

        DroneScoutBridgeMonitor.noteCandidate(
                primaryMatches ? primaryIdentity : secondaryIdentity,
                rssi);
        DroneScoutBridgeSignal bridgeSignal = DroneScoutBridgeMonitor.INSTANCE
                .getSignal().getValue();
        CaltopoClient.CTInfo(
                "DroneScoutBridge",
                String.format(Locale.US,
                        "Bridge event=%d classification=relay-ping transmitter=%s " +
                                "messageCounter=%d aircraft=%s bridgeRssiDbm=%d",
                        bridgeSignal != null ? bridgeSignal.getEventCount() : -1,
                        transmitter,
                        messageCounter,
                        primaryMatches ? primaryIdentity : secondaryIdentity,
                        rssi));
        return true;
    }

    private String preferredAircraftIdentity(AircraftObject ac) {
        Identification primary = ac.getIdentification1();
        Identification secondary = ac.getIdentification2();
        if (secondary != null && secondary.getIdType() == Identification.IdTypeEnum.Serial_Number) {
            return secondary.getUasIdAsString();
        }
        return primary != null ? primary.getUasIdAsString() : "unknown";
    }

    @SuppressWarnings("unchecked")
    private boolean handleMessages(AircraftObject ac, OpenDroneIdParser.Message<?> message) {
        switch (message.header.type) {
            case BASIC_ID:
                handleBasicId(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.BasicId>) message);
                return false;
            case LOCATION:
                handleLocation(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Location>) message);
                return true;
            case AUTH:
                handleAuthentication(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.Authentication>) message);
                return false;
            case SELFID:
                handleSelfID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SelfID>) message);
                return false;
            case SYSTEM:
                handleSystem(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.SystemMsg>) message);
                return false;
            case OPERATOR_ID:
                handleOperatorID(ac, (OpenDroneIdParser.Message<OpenDroneIdParser.OperatorID>) message);
                return false;
            default:
                return false;
        }
    }

    private AircraftObject createNewAircraft(String macAddress, long macAddressLong) {
        AircraftObject ac = new AircraftObject(macAddressLong);
        Connection connection = new Connection();
        connection.firstSeen = System.currentTimeMillis();
        connection.macAddress = macAddress;
        ac.updateConnection(connection);

        ac.updateIdentification1(new Identification());
        ac.updateIdentification2(new Identification());
        ac.updateLocation(new LocationData());
        ac.updateAuthentication(new AuthenticationData());
        ac.updateSelfID(new SelfIdData());
        ac.updateSystem(new SystemData());
        ac.updateOperatorID(new OperatorIdData());
        return ac;
    }

    static boolean shouldSetLiveDataSynchronously(Thread currentThread, Thread mainThread) {
        return AircraftObject.shouldSetLiveDataSynchronously(currentThread, mainThread);
    }

    private void handleBasicId(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.BasicId> message) {
        OpenDroneIdParser.BasicId raw = message.payload;
        Identification data = new Identification();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setUaType(raw.uaType);
        data.setIdType(raw.idType);
        data.setUasId(raw.uasId);

        // This implementation can receive up-to two different types of Basic ID messages
        // Find a free slot to store the current message in or overwrite old data of same type
        Identification id1 = ac.getIdentification1();
        Identification id2 = ac.getIdentification2();
        if (id1 == null || id2 == null)
            return;
        Identification.IdTypeEnum type1 = id1.getIdType();
        Identification.IdTypeEnum type2 = id2.getIdType();
        if (type1 == Identification.IdTypeEnum.None || type1 == data.getIdType()) {
            ac.updateIdentification1(data);
        } else {
            if (type2 == Identification.IdTypeEnum.None || type2 == data.getIdType()) {
                ac.updateIdentification2(data);
            } else {
                CaltopoClient.CTInfo(TAG, "Discarded Basic ID message of type: " + data.getIdType().toString() +
                        ". Already have " + type1.toString() + " and " + type2.toString());
            }
        }
    }

    private void handleLocation(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.Location> message) {
        OpenDroneIdParser.Location raw = message.payload;
        LocationData data = new LocationData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setStatus(raw.status);
        data.setHeightType(raw.heightType);
        data.setDirection(raw.getDirection());
        data.setSpeedHorizontal(raw.getSpeedHori());
        data.setSpeedVertical(raw.getSpeedVert());
        data.setLatitude(raw.getLatitude());
        data.setLongitude(raw.getLongitude());
        data.setAltitudePressure(raw.getAltitudePressure());
        data.setAltitudeGeodetic(raw.getAltitudeGeodetic());
        data.setHeight(raw.getHeight());
        data.setHorizontalAccuracy(raw.horizontalAccuracy);
        data.setVerticalAccuracy(raw.verticalAccuracy);
        data.setBaroAccuracy(raw.baroAccuracy);
        data.setSpeedAccuracy(raw.speedAccuracy);
        data.setLocationTimestamp(raw.timestamp);
        data.setTimeAccuracy(raw.getTimeAccuracy());
        data.setDistance(raw.distance);
        ac.updateLocation(data);
    }

    private void handleAuthentication(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.Authentication> message) {
        OpenDroneIdParser.Authentication raw = message.payload;
        AuthenticationData data = new AuthenticationData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setAuthType(raw.authType);
        data.setAuthDataPage(raw.authDataPage);
        if (raw.authDataPage == 0) {
            data.setAuthLastPageIndex(raw.authLastPageIndex);
            data.setAuthLength(raw.authLength);
            data.setAuthTimestamp(raw.authTimestamp);
        }
        data.setAuthData(raw.authData);
        ac.updateAuthentication(ac.combineAuthentication(data));
    }

    private void handleSelfID(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.SelfID> message) {
        OpenDroneIdParser.SelfID raw = message.payload;
        SelfIdData data = new SelfIdData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setDescriptionType(raw.descriptionType);
        data.setOperationDescription(raw.operationDescription);
        ac.updateSelfID(data);
    }

    private void handleSystem(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.SystemMsg> message) {
        OpenDroneIdParser.SystemMsg raw = message.payload;
        SystemData data = new SystemData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setOperatorLocationType(raw.operatorLocationType);
        data.setClassificationType(raw.classificationType);
        data.setOperatorLatitude(raw.getLatitude());
        data.setOperatorLongitude(raw.getLongitude());
        data.setAreaCount(raw.areaCount);
        data.setAreaRadius(raw.getAreaRadius());
        data.setAreaCeiling(raw.getAreaCeiling());
        data.setAreaFloor(raw.getAreaFloor());
        data.setCategory(raw.category);
        data.setClassValue(raw.classValue);
        data.setOperatorAltitudeGeo(raw.getOperatorAltitudeGeo());
        data.setSystemTimestamp(raw.systemTimestamp);
        ac.updateSystem(data);
    }

    private void handleOperatorID(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.OperatorID> message) {
        OpenDroneIdParser.OperatorID raw = message.payload;
        OperatorIdData data = new OperatorIdData();
        data.setMsgCounter(message.msgCounter);
        data.setTimestamp(message.timestamp);

        data.setOperatorIdType(raw.operatorIdType);
        data.setOperatorId(raw.operatorId);
        ac.updateOperatorID(data);
    }

    private boolean handleMessagePack(AircraftObject ac, OpenDroneIdParser.Message<OpenDroneIdParser.MessagePack> message,
                                      long timestamp, int msgCounter) {
        OpenDroneIdParser.MessagePack raw = message.payload;
        if (raw == null)
            return false;

        if (raw.messageSize != Constants.MAX_MESSAGE_SIZE ||
            raw.messagesInPack <= 0 ||
            raw.messagesInPack > Constants.MAX_MESSAGES_IN_PACK)
            return false;

        boolean locationUpdated = false;
        for (int i = 0; i < raw.messagesInPack; i++) {
            int offset = i*raw.messageSize;
            byte[] data = Arrays.copyOfRange(raw.messages, offset, offset + raw.messageSize);
            OpenDroneIdParser.Message<?> subMessage =
                    OpenDroneIdParser.parseMessage(data, 0, timestamp, receiverLocation, msgCounter);
            if (subMessage == null)
                return locationUpdated;

            if (handleMessages(ac, subMessage)) {
                locationUpdated = true;
            }
        }
        return locationUpdated;
    }
}
