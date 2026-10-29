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

    @Test fun mapOptionsNamesTheCredentialsAndTheCatalogTitle() {
        assertEquals("Taylor Map", IncidentSelection.displayMapTitle("Ken 1SAR7 Taylor: Taylor Map", "Ken 1SAR7 Taylor"))
        assertEquals("Taylor Map", IncidentSelection.displayMapTitle("Taylor Map", "Ken 1SAR7 Taylor"))
        assertEquals(
            "Credentials: Personal: Ken 1SAR7 Taylor\nMap: Taylor Map",
            IncidentSelection.mapOptionsText(true, "Ken 1SAR7 Taylor", "Taylor Map"))
        assertEquals(
            "Credentials: Team: NCSSAR\nMap: Taylor Map",
            IncidentSelection.mapOptionsText(false, "NCSSAR", "Taylor Map"))
    }
}
