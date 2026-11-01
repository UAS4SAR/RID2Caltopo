import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ClueDescriptionTemplateTest {
    @Test
    fun assignedPilotPrefillsEditableReportLine() {
        val template = buildClueDescriptionTemplate(0L, ZoneId.of("UTC"), pilot = "  1SAR7  ")
        assertTrue(template.contains("\nfound by: 1SAR7\n"))
        assertTrue(template.contains("reported to IC: yes|no"))
    }

    @Test
    fun buildClueDescriptionTemplate_formatsLocalZoneAndUTC() {
        val timestampMs = 1_744_764_645_000L

        val template = buildClueDescriptionTemplate(
            timestampMs = timestampMs,
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        assertTrue(template.startsWith("time: "))
        assertTrue(template.contains("UTC"))
        assertTrue(template.contains("found by: "))
        assertTrue(template.contains("reported to IC: yes|no"))
        // Local zone abbreviation appears (PDT or PST depending on DST rules for the instant).
        assertTrue(template.contains("PDT") || template.contains("PST"))
    }
}
