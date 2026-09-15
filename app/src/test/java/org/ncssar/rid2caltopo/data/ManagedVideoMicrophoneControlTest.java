package org.ncssar.rid2caltopo.data;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ManagedVideoMicrophoneControlTest {
    @Test public void enablesOnlyAfterSenderActivationSucceeds() {
        List<String> events = new ArrayList<>();
        assertTrue(ManagedVideoMicrophoneControl.setEnabled(true,
                value -> events.add("track:" + value),
                value -> { events.add("sender:" + value); return true; }));
        assertEquals(List.of("track:false", "sender:true", "track:true"), events);
    }

    @Test public void failedActivationLeavesMicrophoneMuted() {
        List<Boolean> track = new ArrayList<>();
        assertFalse(ManagedVideoMicrophoneControl.setEnabled(true, track::add, value -> false));
        assertEquals(List.of(false), track);
    }

    @Test public void mutingWorksEvenWhenRtpUpdateFails() {
        List<Boolean> track = new ArrayList<>();
        assertTrue(ManagedVideoMicrophoneControl.setEnabled(false, track::add, value -> false));
        assertEquals(List.of(false), track);
    }
}
