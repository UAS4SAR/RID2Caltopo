package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class IncidentSelectionTest {
    @Test fun namedIncidentWorksWithoutMapAndMapTitleWinsOnlyWhileConnected() {
        assertEquals("Airport Exercise", IncidentSelection.name(false, "Old map", " Airport Exercise "))
        assertEquals("Current map", IncidentSelection.name(true, " Current map ", "Airport Exercise"))
        assertEquals("Airport Exercise", IncidentSelection.name(false, "Current map", "Airport Exercise"))
    }
    @Test fun missingNamesHaveConsistentDefault() {
        assertEquals("Training", IncidentSelection.name(false, "Old map", "  "))
        assertEquals("Exercise", IncidentSelection.name(true, "", "Exercise"))
    }
}
