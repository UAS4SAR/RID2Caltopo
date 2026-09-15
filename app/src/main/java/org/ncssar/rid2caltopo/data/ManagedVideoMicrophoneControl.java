package org.ncssar.rid2caltopo.data;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Toggle an already-negotiated track without replacing it or renegotiating. */
public final class ManagedVideoMicrophoneControl {
    private ManagedVideoMicrophoneControl() { }

    public static boolean setEnabled(boolean enabled, Consumer<Boolean> trackEnabled,
                                     Predicate<Boolean> senderActive) {
        trackEnabled.accept(false);
        boolean applied = senderActive.test(enabled);
        if (enabled && applied) trackEnabled.accept(true);
        return !enabled || applied;
    }
}
