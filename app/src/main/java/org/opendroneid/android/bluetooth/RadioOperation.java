package org.opendroneid.android.bluetooth;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Preflight is advisory; OS state can change before the platform call. */
final class RadioOperation {
    static boolean attempt(BooleanSupplier permitted, Runnable operation, Consumer<RuntimeException> failed) {
        try {
            if (!permitted.getAsBoolean()) throw new SecurityException("Nearby devices permission unavailable");
            operation.run();
            return true;
        } catch (SecurityException | IllegalStateException error) {
            failed.accept(error);
            return false;
        }
    }
}
