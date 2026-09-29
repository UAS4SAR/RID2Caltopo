package org.ncssar.rid2caltopo.data;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Regression coverage for transport interruption during live coordination. */
public class TrackerOutageRecoveryTest {
    static class Transport implements TrackerCoordinationTransport {
        volatile Callback callback;
        volatile boolean connected;
        volatile int connects;
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        public void setCallback(Callback cb) { callback = cb; }
        public void connect(String url, String key) { connects++; }
        public void disconnect() { connected = false; }
        public boolean isConnected() { return connected; }
        public boolean send(String text) { messages.add(text); return true; }
        void open() { connected = true; callback.onOpen(); }
        void fail() { connected = false; callback.onFailure(new java.io.IOException("WAN unavailable"), 0, ""); }
        int confirmations() { return (int) messages.stream().filter(s -> s.contains("\"type\":\"drone_confirmed\"")).count(); }
    }
    Transport transport;
    TrackerPeerCoordinator coordinator;
    volatile long now = 1000;

    @Before public void setup() {
        transport = new Transport();
        TrackerPeerCoordinator.setTransportFactoryForTesting(() -> transport);
        TrackerPeerCoordinator.setTrackerConfigForTesting("https://tracker.example.org", "audit-token");
        TrackerPeerCoordinator.setHandoffDelayMsForTesting(0);
        TrackerPeerCoordinator.setTimeSourceForTesting(() -> now);
        coordinator = TrackerPeerCoordinator.getInstance();
        CaltopoClient.ResetPersistedClientState();
        coordinator.start("MAP1", "zone-alpha", "Alpha", null);
        transport.open();
    }
    @After public void teardown() {
        TrackerPeerCoordinator.resetForTesting();
        CaltopoClient.ResetPersistedClientState();
    }
    void confirm() { coordinator.onDroneConfirmed("DRONE1", "NCSSAR", "DJI Mini 4 Pro", "1sar7", "1sar7DjMn4Pr"); }
    boolean until(BooleanSupplier predicate, long timeout) throws Exception {
        long deadline = System.nanoTime() + timeout * 1000000;
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        return predicate.getAsBoolean();
    }

    @Test public void activityDuringHandshakeMustNotSuppressNextRetry() throws Exception {
        coordinator.stopBackgroundTimersForTesting();
        now += 10001;
        coordinator.checkAckLivenessForTesting();
        assertTrue("forced reconnect should start", until(() -> transport.connects >= 2, 1500));
        // New activity must not replace the reconnect transport mid-handshake.
        confirm();
        assertEquals(2, transport.connects);
        int beforeFailure = transport.connects;
        transport.fail();
        assertTrue("failed handshake must schedule another attempt instead of retaining stale reconnectPending",
                until(() -> transport.connects > beforeFailure, 3000));
    }

    @Test public void confirmationAcceptedBySocketButNotAcknowledgedMustReplay() {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        assertEquals(1, transport.confirmations());
        // Socket acceptance is not server delivery. Lose transport before any server echo.
        transport.connected = false;
        transport.open();
        assertTrue("unacknowledged Save must replay on the next transport", transport.confirmations() > 1);
    }

    void echo(String pilot, String guid) throws Exception {
        transport.callback.onMessage(new org.json.JSONObject()
                .put("type", "drone_confirmed").put("mapId", "MAP1")
                .put("remoteId", "DRONE1").put("confirmedByGuid", guid)
                .put("org", "NCSSAR").put("model", "DJI Mini 4 Pro")
                .put("ownerName", pilot).put("mappedId", "1sar7DjMn4Pr").toString());
    }
    @Test public void matchingServerEchoCompletesPendingConfirmation() throws Exception {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        echo("1sar7", "zone-alpha");
        transport.open();
        assertEquals("acknowledged Save must not replay", 1, transport.confirmations());
    }
    @Test public void oldEchoDoesNotAcknowledgeNewerSave() throws Exception {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        coordinator.onDroneConfirmed("DRONE1", "NCSSAR", "DJI Mini 4 Pro", "1sar8", "1sar7DjMn4Pr");
        echo("1sar7", "zone-alpha");
        int sent = transport.confirmations();
        transport.open();
        assertEquals("new Save remains pending after old echo", sent + 1, transport.confirmations());
    }
    @Test public void endedFlightDoesNotReplayOldConsent() {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        coordinator.onDroneLost("DRONE1");
        transport.open();
        assertEquals(1, transport.confirmations());
    }
    @Test public void heartbeatRetriesAreThrottledUntilAcknowledged() {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        coordinator.markHeartbeatSentForTesting(10, now);
        coordinator.handleHeartbeatAckForTesting(10, 0);
        assertEquals(1, transport.confirmations());
        now += 5000;
        coordinator.markHeartbeatSentForTesting(11, now);
        coordinator.handleHeartbeatAckForTesting(11, 0);
        assertEquals(2, transport.confirmations());
    }
    @Test public void peerConfirmationSupersedesPendingLocalSave() throws Exception {
        coordinator.stopBackgroundTimersForTesting();
        confirm();
        echo("1sar8", "zone-bravo");
        transport.open();
        assertEquals(1, transport.confirmations());
        assertFalse(coordinator.isLocalOwner("DRONE1"));
    }
}
