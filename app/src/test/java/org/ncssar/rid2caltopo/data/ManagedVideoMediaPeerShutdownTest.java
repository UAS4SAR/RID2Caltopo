package org.ncssar.rid2caltopo.data;

import org.junit.Test;
import org.webrtc.PeerConnection;

import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Native close waits for a signaling callback while the caller owns the peer lock. */
public class ManagedVideoMediaPeerShutdownTest {
    @Test public void closedCallbackDoesNotWaitForCleanupMonitor() throws Exception {
        ManagedVideoMediaPeer media = new ManagedVideoMediaPeer(new ManagedVideoMediaPeer.Sink() {
            public void sendAnswer(String id, String sdp) { }
            public void onMetrics(ManagedVideoMediaPeer.Metrics metrics) { }
            public void onFailure(String id, String reason) { }
            public void onMicrophoneState(String id, boolean enabled, String error) { }
            public void onReady(String id) { throw new AssertionError("Closed peer became ready"); }
        });
        Constructor<?> constructor = Class.forName(
                ManagedVideoMediaPeer.class.getName() + "$PeerObserver")
                .getDeclaredConstructor(ManagedVideoMediaPeer.class);
        constructor.setAccessible(true);
        PeerConnection.Observer observer = (PeerConnection.Observer) constructor.newInstance(media);
        ExecutorService signaling = Executors.newSingleThreadExecutor();
        try {
            synchronized (media) {
                Future<?> callback = signaling.submit(() ->
                        observer.onConnectionChange(PeerConnection.PeerConnectionState.CLOSED));
                callback.get(2, TimeUnit.SECONDS);
            }
        } finally {
            signaling.shutdownNow();
            media.close();
        }
    }
}
