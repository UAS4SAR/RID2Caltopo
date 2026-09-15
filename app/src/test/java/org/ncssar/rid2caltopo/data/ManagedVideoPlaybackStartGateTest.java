package org.ncssar.rid2caltopo.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManagedVideoPlaybackStartGateTest {
    @Test public void waitsForTransportAndDoesNotRewindOnReconnect() {
        ManagedVideoPlaybackStartGate gate = new ManagedVideoPlaybackStartGate();
        for (int i = 0; i < 100; i++) assertFalse(gate.shouldStart(false));
        assertTrue(gate.shouldStart(true));
        assertFalse(gate.shouldStart(true));
        assertFalse(gate.shouldStart(false));
        assertFalse(gate.shouldStart(true));
    }

    @Test public void cancellationRejectsLateCallbackAndReplacementStartsFresh() {
        ManagedVideoPlaybackStartGate retired = new ManagedVideoPlaybackStartGate();
        retired.cancel();
        assertFalse(retired.shouldStart(true));
        assertTrue(new ManagedVideoPlaybackStartGate().shouldStart(true));
    }
}
