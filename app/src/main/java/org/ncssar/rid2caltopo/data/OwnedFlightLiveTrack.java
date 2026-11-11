/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * A granted lease gets its own CalTopo LiveTrack. Another tablet's id, and any id that is
 * already an archived shape, is not reused and is not overwritten.
 */
public final class OwnedFlightLiveTrack {
    private OwnedFlightLiveTrack() {}

    /** This tablet may keep sending points to an id only when it created that LiveTrack. */
    public static boolean mayAttach(@NonNull String id, boolean createdByThisTablet, boolean alreadyArchived) {
        return !id.isEmpty() && createdByThisTablet && !alreadyArchived;
    }

    /**
     * The shape id to save. Another tablet's already-archived id is replaced with {@code minted}
     * so this flight is stored whole and that shape is left alone. A LiveTrack this tablet
     * created keeps its id, including when the start response was lost and the id is not yet a shape.
     */
    @NonNull
    public static String shapeIdForArchive(@NonNull String currentId, boolean createdByThisTablet,
                                           boolean alreadyArchived, @NonNull String minted) {
        if (!alreadyArchived || createdByThisTablet) return currentId;
        return minted;
    }
}
