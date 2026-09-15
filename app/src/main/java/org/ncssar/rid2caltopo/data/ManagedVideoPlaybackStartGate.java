package org.ncssar.rid2caltopo.data;

/** One finite playback start per media attempt; reconnects do not rewind it. */
public final class ManagedVideoPlaybackStartGate {
    private boolean pending = true;

    public boolean shouldStart(boolean connected) {
        if (!pending || !connected) return false;
        pending = false;
        return true;
    }

    public void cancel() { pending = false; }
}
