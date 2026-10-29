package org.opendroneid.android.bluetooth;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class RadioOperationTest {
    @Test public void permissionDenialNeverCallsPlatform() {
        AtomicBoolean called = new AtomicBoolean();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        assertFalse(RadioOperation.attempt(() -> false, () -> called.set(true), error::set));
        assertFalse(called.get());
        assertTrue(error.get() instanceof SecurityException);
    }

    @Test public void stateAndPermissionRacesRecoverAndPermitRetry() {
        for (RuntimeException failure : new RuntimeException[]{new SecurityException(), new IllegalStateException()}) {
            AtomicReference<RuntimeException> error = new AtomicReference<>();
            assertFalse(RadioOperation.attempt(() -> true, () -> { throw failure; }, error::set));
            assertSame(failure, error.get());
            assertTrue(RadioOperation.attempt(() -> true, () -> {}, error::set));
        }
    }

    @Test public void programmingErrorsAreNotHidden() {
        assertThrows(IllegalArgumentException.class,
            () -> RadioOperation.attempt(() -> true, () -> { throw new IllegalArgumentException(); }, error -> {}));
    }
}
