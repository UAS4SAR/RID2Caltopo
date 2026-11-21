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

    /**
     * Reconnect rules since the PR #14/#15 merge (f2a767a2): an open socket waits 30 s for
     * hello_ack, an ack timeout takes the next ladder step (2, 4, 8, 10 s) with equal jitter
     * (1-2, 2-4, 4-8, 5-10 s), and the legacy immediate redial is off unless Developer Tools
     * turns it on. Both switches and the jitter draw are pinned, and the reconnect timer is run
     * by hand, so the test does not depend on wall-clock waits.
     */
    @Test public void activityDuringHandshakeMustNotSuppressNextRetry() throws Exception {
        TrackerPeerCoordinator.setLegacyImmediateAckRetryForTesting(false);
        TrackerPeerCoordinator.setReconnectJitterForTesting(true, 0.0);
        coordinator.stopBackgroundTimersForTesting();
        coordinator.setSuppressReconnectTimerForTesting(true);
        now += 10_001;
        coordinator.checkAckLivenessForTesting();
        assertEquals("an open socket still waits for hello_ack at 10 s", 0L, coordinator.getForcedReconnectCountForTesting());
        now += 20_000;
        coordinator.checkAckLivenessForTesting();
        assertEquals("hello_ack missing after 30 s forces a reconnect", 1L, coordinator.getForcedReconnectCountForTesting());
        assertEquals("ack timeout takes the first ladder step (2 s, jittered to 1 s)", 1_000L, coordinator.getScheduledReconnectDelayMsForTesting());
        assertEquals(4_000L, coordinator.getNextReconnectDelayMsForTesting());
        coordinator.runScheduledReconnectForTesting();
        assertEquals("forced reconnect should start", 2, transport.connects);
        // New activity must not replace the reconnect transport mid-handshake.
        confirm();
        assertEquals(2, transport.connects);
        transport.fail();
        assertTrue("failed handshake must schedule another attempt instead of retaining stale reconnectPending",
                coordinator.isReconnectPendingForTesting());
        assertEquals("the next attempt is the next ladder step (4 s, jittered to 2 s)", 2_000L, coordinator.getScheduledReconnectDelayMsForTesting());
        coordinator.runScheduledReconnectForTesting();
        assertEquals(3, transport.connects);
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
