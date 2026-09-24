package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RidMappingRulesTest {
    @Test
    fun samePilotCanOwnTwoNeoDronesWithDistinctModelDescriptions() {
        val first = EditableRidMapping("RID1", "", "1sar7", "DJI Neo")
        val second = EditableRidMapping("RID2", "", "1sar7", "DJI Neo - 2")
        assertTrue(RidMappingRules.validateEntry("", second, listOf(first), false).isEmpty())
        assertTrue(first.mappedId() != second.mappedId())
        val duplicate = second.copy(ownerCallsign = " 1SAR7 ", model = " dji neo ")
        assertTrue(RidMappingRules.validateEntry("", duplicate, listOf(first), false)
            .contains("Model must be unique for this owner callsign."))
    }

    @Test
    fun acceptsPilotNamesAndArbitraryCallsignsAndPreservesExplicitValues() {
        for (pilot in listOf("SAR7", "Ken Taylor", "Alpha-2", "O'Neil", "1 SAR 7", "山田 太郎")) {
            val mapping = EditableRidMapping("RID1", "", pilot, "Mini 4 Pro")
            assertTrue(RidMappingRules.validateEntry("", mapping, emptyList(), false).isEmpty())
            assertEquals(pilot, RidMappingRules.resolveOwnerFields("", pilot, pilot, mapping.mappedId(), mapping.model, mapping.remoteId).ownerCallsign)
        }
    }

    @Test
    fun standaloneMappingAcceptsBlankOrganizationButManagedMappingRequiresIt() {
        val mapping = EditableRidMapping("1581F8HGX1234567890", "", "1SAR7", "DJI Mini 4 Pro")
        for (organization in listOf("", "  ")) {
            assertTrue(RidMappingRules.validateEntry(organization, mapping, emptyList(), requireOrganization = false).isEmpty())
            assertTrue(RidMappingRules.validate(organization, listOf(mapping), requireOrganization = false).isEmpty())
            assertEquals(listOf("Organization is required."), RidMappingRules.validateEntry(organization, mapping, emptyList(), requireOrganization = true))
            assertEquals(listOf("Organization is required."), RidMappingRules.validate(organization, listOf(mapping), requireOrganization = true))
        }
        assertEquals("1SAR7DjMn4Pr", mapping.mappedId())
    }

    @Test
    fun standaloneMappingStillRequiresValidAircraftFieldsAndRejectsDuplicates() {
        val invalid = EditableRidMapping("BAD-RID", "", "", "")
        val errors = RidMappingRules.validateEntry("", invalid, emptyList(), requireOrganization = false)
        assertTrue(errors.contains("Remote ID must contain only A-Z and 0-9."))
        assertTrue(errors.contains("Pilot callsign or name is required."))
        assertTrue(errors.contains("Model is required."))
        val valid = EditableRidMapping("RID1", "", "1SAR7", "DJI Mini 4 Pro")
        val duplicates = RidMappingRules.validateEntry("", valid, listOf(valid), requireOrganization = false)
        assertTrue(duplicates.contains("Remote ID is already listed."))
        assertTrue(duplicates.contains("Model must be unique for this owner callsign."))
    }

    @Test
    fun singleEditIgnoresInvalidLegacyEntriesButStillChecksDuplicates() {
        val valid = EditableRidMapping("RID1", "Owner", "1SAR7", "Model")
        val invalid = EditableRidMapping("RID2", "Legacy", "Legacy", "Other", AircraftReadiness(accessories = listOf(AircraftAccessory("battery", "", null))))
        assertTrue(RidMappingRules.validateEntry("SAR", valid, listOf(invalid)).isEmpty())
        assertTrue(RidMappingRules.validateEntry("SAR", invalid, listOf(valid)).isNotEmpty())
        val duplicates = RidMappingRules.validateEntry("SAR", valid, listOf(valid))
        assertTrue(duplicates.contains("Remote ID is already listed."))
        assertTrue(duplicates.contains("Model must be unique for this owner callsign."))
    }

    @Test
    fun resolveOwnerFields_repairsLegacyTeamOwnerWhenNameIsMissing() {
        val fields = RidMappingRules.resolveOwnerFields(
            ownerName = "",
            ownerCallsign = "NCSSAR Team",
            legacyOwner = "NCSSAR Team",
            mappedId = "1sar1001DjAvt2-01",
            model = "DJI Avata2",
            remoteId = "1581F6W8W258F0022EZZ"
        )

        assertEquals("NCSSAR Team", fields.ownerName)
        assertEquals("1sar1001-01", fields.ownerCallsign)
    }

    @Test
    fun validate_acceptsTeamCallsignSuffix() {
        val mapping = EditableRidMapping(
            remoteId = "1581F6W8W258F0022EZZ",
            ownerName = "NCSSAR Team",
            ownerCallsign = "1SAR1001-01",
            model = "DJI Avata2"
        )

        assertTrue(RidMappingRules.validate("NCSSAR", listOf(mapping)).isEmpty())
    }

    @Test
    fun acceptsValidMappingAndDerivesMappedId() {
        val mapping = EditableRidMapping(
            remoteId = "1581F8HGX1234567890",
            ownerName = "Jerry Example",
            ownerCallsign = "1SAR7",
            model = "DJI Matrice 4TD"
        )
        assertTrue(RidMappingRules.validate("NCSSAR", listOf(mapping)).isEmpty())
        assertEquals("1SAR7DjMtrc4td", mapping.mappedId())
    }

    @Test
    fun rejectsInvalidRidCallsignAndDuplicateOwnerModel() {
        val mappings = listOf(
            EditableRidMapping("BAD-RID", "", "", "DJI Mini 4 Pro"),
            EditableRidMapping("1581F6Z9C123", "", "", "DJI Mini 4 Pro")
        )
        val errors = RidMappingRules.validate("NCSSAR", mappings).joinToString("\n")
        assertTrue(errors.contains("only A-Z and 0-9"))
        assertTrue(errors.contains("Pilot callsign or name is required."))
        assertTrue(errors.contains("unique for this owner callsign"))
    }
}
