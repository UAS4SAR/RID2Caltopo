package org.opendroneid.android.bluetooth;

import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.opendroneid.android.Constants;
import org.opendroneid.android.data.AircraftObject;
import org.opendroneid.android.data.Identification;
import org.opendroneid.android.data.LocationData;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class OpenDroneIdDataManagerParserSeamsTest {

    @Test
    public void horizontalAccuracy_respectsDefaultThirtyMeterContainment() {
        assertEquals(false, OpenDroneIdDataManager.isHorizontalAccuracySufficient(0));
        assertEquals(false, OpenDroneIdDataManager.isHorizontalAccuracySufficient(8));
        assertEquals(true, OpenDroneIdDataManager.isHorizontalAccuracySufficient(9));
        assertEquals(true, OpenDroneIdDataManager.isHorizontalAccuracySufficient(10));
        assertEquals(true, OpenDroneIdDataManager.isHorizontalAccuracySufficient(11));
        assertEquals(true, OpenDroneIdDataManager.isHorizontalAccuracySufficient(12));
        assertEquals(false, OpenDroneIdDataManager.isHorizontalAccuracySufficient(13));
    }

    @Test
    public void optionalMotionTelemetry_rejectsRemoteIdSentinels() {
        assertNull(OpenDroneIdDataManager.validRidDirectionDegrees(361.0));
        assertEquals(214.0, OpenDroneIdDataManager.validRidDirectionDegrees(214.0), 0.0);
        assertNull(OpenDroneIdDataManager.validRidHorizontalSpeedKnots(255.0));
        assertEquals(19.4384449, OpenDroneIdDataManager.validRidHorizontalSpeedKnots(10.0), 0.000001);
        assertNull(OpenDroneIdDataManager.validRidVerticalRateFpm(63.0));
        assertEquals(-393.700788, OpenDroneIdDataManager.validRidVerticalRateFpm(-2.0), 0.000001);
    }

    @Test
    public void locationParser_preservesHorizontalAccuracyCode() throws Exception {
        byte[] payload = new byte[Constants.MAX_MESSAGE_SIZE];
        payload[0] = 0x12; // LOCATION, protocol version 2
        payload[19] = 10;

        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseMessage(payload, 0, 1234L, null, 7);
        OpenDroneIdParser.Location location = (OpenDroneIdParser.Location) message.payload;
        assertEquals(10, location.horizontalAccuracy);

        LocationData normalized = new LocationData();
        normalized.setHorizontalAccuracy(location.horizontalAccuracy);
        assertEquals(10, normalized.getHorizontalAccuracyCode());
        assertEquals(LocationData.HorizontalAccuracyEnum.meters_10,
                normalized.getHorizontalAccuracy());
    }

    @Test
    public void bluetoothServiceDataParser_usesUuidRelativePayloadOffset() {
        byte[] serviceData = new byte[Constants.MAX_MESSAGE_SIZE + 2];
        serviceData[0] = 0x0D; // Open Drone ID application code
        serviceData[1] = 7;    // message counter
        serviceData[2] = 0x02; // BASIC_ID, protocol version 2
        serviceData[3] = 0x12; // serial number, multirotor
        byte[] idBytes = "DRONESCOUTBRIDGE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, serviceData, 4, idBytes.length);

        OpenDroneIdParser.Message<?> message =
                OpenDroneIdDataManager.parseBluetoothServiceData(serviceData, 1234L, null);

        assertNotNull(message);
        assertEquals(OpenDroneIdParser.Type.BASIC_ID, message.header.type);
        assertEquals(7, message.msgCounter);
        assertEquals("DRONESCOUTBRIDGE",
                new String(
                        ((OpenDroneIdParser.BasicId) message.payload).uasId,
                        StandardCharsets.US_ASCII).trim());
    }

    @Test
    public void bluetoothRelayPing_matchesRawIdentityRegardlessOfIdType() {
        byte[] serviceData = new byte[Constants.MAX_MESSAGE_SIZE + 2];
        serviceData[0] = 0x0D;
        serviceData[1] = 9;
        serviceData[2] = 0x02;
        serviceData[3] = 0x02; // ID type None, multirotor.
        byte[] idBytes = "DRONESCOUTBRIDGE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, serviceData, 4, idBytes.length);

        assertEquals(
                "DRONESCOUTBRIDGE\0\0\0\0",
                OpenDroneIdDataManager.bluetoothRelayPingIdentity(serviceData, 1234L, null));
    }

    @Test
    public void bluetoothRelayPing_rejectsOrdinaryRawIdentity() {
        byte[] serviceData = new byte[Constants.MAX_MESSAGE_SIZE + 2];
        serviceData[0] = 0x0D;
        serviceData[1] = 10;
        serviceData[2] = 0x02;
        serviceData[3] = 0x12;
        byte[] idBytes = "1581F6Z9C24BH0036EJL".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, serviceData, 4, idBytes.length);

        assertNull(OpenDroneIdDataManager.bluetoothRelayPingIdentity(serviceData, 1234L, null));
    }

    @Test
    public void ridTimestamp_invalidUnknown_returnsZero() {
        long nowWallMsec = 1_700_000_000_000L;
        assertEquals(0L, OpenDroneIdDataManager.ridTimestampTenthsToUtcMsec(0xFFFF, nowWallMsec));
    }

    @Test
    public void ridTimestamp_nearHourBoundary_choosesNearestHour() {
        long nowWallMsec = (13L * 60L * 60L * 1000L) - 100L; // 12:59:59.900 UTC within a synthetic day
        long expected = (13L * 60L * 60L * 1000L) + 200L; // 13:00:00.200

        assertEquals(expected, OpenDroneIdDataManager.ridTimestampTenthsToUtcMsec(2.0, nowWallMsec));
    }

    @Test
    public void selectTrackAltitude_ridAndMslValid_prefersRidAndUpdatesReference() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(43.6, 1043.6, null);

        assertEquals(44L, selected.roundedMeters);
        assertEquals(1000.0, selected.geodeticMinusRidHeightMeters, 0.000001);
    }

    @Test
    public void selectTrackAltitude_ridInvalid_usesReferenceAgainstMsl() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(
                        OpenDroneIdDataManager.RID_INVALID_ALTITUDE_METERS,
                        1510.2,
                        1000.0
                );

        assertEquals(510L, selected.roundedMeters);
        assertEquals(1000.0, selected.geodeticMinusRidHeightMeters, 0.000001);
    }

    @Test
    public void selectTrackAltitude_noReference_fallsBackToMsl() {
        OpenDroneIdDataManager.SelectedTrackAltitude selected =
                OpenDroneIdDataManager.selectTrackAltitudeMeters(
                        OpenDroneIdDataManager.RID_INVALID_ALTITUDE_METERS,
                        120.6,
                        null
                );

        assertEquals(121L, selected.roundedMeters);
        assertNull(selected.geodeticMinusRidHeightMeters);
    }

    @Test
    public void liveDataUpdates_backgroundRidIngestThread_postsAsynchronously() {
        Thread mainThread = Thread.currentThread();
        Thread ridIngestThread = new Thread();

        assertEquals(false,
                OpenDroneIdDataManager.shouldSetLiveDataSynchronously(ridIngestThread, mainThread));
    }

    @Test
    public void receiveData_backgroundThread_createsAircraftWithoutLiveDataThreadCrash() throws Exception {
        byte[] payload = new byte[Constants.MAX_MESSAGE_SIZE];
        payload[0] = 0x02; // BASIC_ID, protocol version 2
        payload[1] = 0x12; // serial number, multirotor
        byte[] idBytes = "RID2CALTOPO12345".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, payload, 2, idBytes.length);
        OpenDroneIdParser.Message<?> message =
                OpenDroneIdParser.parseMessage(payload, 0, 1234L, null, 7);
        OpenDroneIdDataManager manager = new OpenDroneIdDataManager(null);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ingestThread = new Thread(() -> {
            try {
                manager.receiveData(1234L, "AA:BB:CC:DD:EE:FF", 0xAABBCCDDEEFFL, -48,
                        message, CtDroneSpec.TransportTypeEnum.BT4);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "RID-ingest-test");
        ingestThread.start();
        ingestThread.join();

        assertNull(failure.get());
        AircraftObject aircraft = manager.getAircraft().get(0xAABBCCDDEEFFL);
        assertNotNull(aircraft);
        assertEquals(Identification.IdTypeEnum.Serial_Number,
                aircraft.getIdentification1().getIdType());
    }

    @Test
    public void ridIngestFailureLogging_onlyLogsFirstTenOccurrences() {
        assertEquals(true, OpenDroneIdDataManager.shouldLogLimitedOccurrence(1, 10));
        assertEquals(true, OpenDroneIdDataManager.shouldLogLimitedOccurrence(10, 10));
        assertEquals(false, OpenDroneIdDataManager.shouldLogLimitedOccurrence(11, 10));
        assertEquals(false, OpenDroneIdDataManager.shouldLogLimitedOccurrence(100, 10));
    }
}
