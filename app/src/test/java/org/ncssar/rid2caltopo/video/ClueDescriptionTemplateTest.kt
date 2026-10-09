import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ClueDescriptionTemplateTest {
    @org.junit.Test fun assignedPilotPrefillsEditableReportLine() {
        val template = buildClueDescriptionTemplate(0L, java.time.ZoneId.of("UTC"), pilot = "  1SAR7  ")
        org.junit.Assert.assertTrue(template.contains("\nfound by: 1SAR7\n"))
        org.junit.Assert.assertTrue(template.contains("reported to IC: yes|no"))
    }

    @Test
    fun buildClueDescriptionTemplate_formatsLocalTimeAndPlaceholders() {
        val timestampMs = 1_744_764_645_000L

        val template = buildClueDescriptionTemplate(
            timestampMs = timestampMs,
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        assertEquals(
            "time: 17:50:45\nfound by: \nreported to IC: yes|no\n",
            template
        )
    }
}
