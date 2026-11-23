package org.ncssar.rid2caltopo.ui

import java.util.concurrent.CopyOnWriteArrayList

/**
 * System Back closes an open BTRX map popup before anything else (Ken, Nov 23 field test: Back
 * brought up the Quit prompt while the focus panel stayed open). The map registers a closer;
 * the app's Back handler asks here first.
 */
object BtrxPopupBack {
    private val closers = CopyOnWriteArrayList<() -> Boolean>()

    fun register(closer: () -> Boolean): AutoCloseable {
        closers.add(closer)
        return AutoCloseable { closers.remove(closer) }
    }

    /** True when a popup was open and is now closed. */
    @JvmStatic
    fun closeOpenPopup(): Boolean {
        var closed = false
        for (closer in closers.reversed()) if (closer()) closed = true
        return closed
    }
}
