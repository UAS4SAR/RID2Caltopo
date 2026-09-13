package org.ncssar.rid2caltopo.video

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import DroneSpecState
import DroneDisplayState
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState

class DesignatorIndicatorTest {
    @Test
    fun freshCameraBearingSurvivesStaleAircraftPosition() {
        val display = DroneDisplayState(positionStale=true, headingDeg=null, aglFt=20.0, atoFt=20.0)
        org.junit.Assert.assertEquals("ATO:POS? AGL:POS? AOL:POS? RNG:POS? CAM:91°",
            streamTelemetryHeaderText(display, 91.0))
    }

    @Test
    fun embeddedTelemetryDoesNotClaimNoTelemetryWithoutRid() {
        val state = org.ncssar.rid2caltopo.data.DesignatorState.Yellow(emptyMap(), embeddedTelemetry = true)
        org.junit.Assert.assertEquals("Embedded Telemetry", telemetryChipTextFor(state, null))
        org.junit.Assert.assertTrue(designatorDetailText(state, "Standalone", true).contains("Embedded telemetry"))
    }

    @Test
    fun formatLiveState_reportsLiveUntilLagIsMaterial() {
        assertEquals("Starting", formatLiveState(null))
        assertEquals("lag:450ms", formatLiveState(450L))
    }

    @Test
    fun formatLiveState_formatsSubsecondAndSecondLag() {
        assertEquals("lag:750ms", formatLiveState(750L))
        assertEquals("lag:2.3s", formatLiveState(2_250L))
    }

    @Test
    fun indicatorPalette_usesComplementaryOutlineColors() {
        assertEquals(
            IndicatorPalette(Color(0xFFFF0000), Color(0xFF00D4FF)),
            indicatorPaletteFor(DesignatorState.Red)
        )
        assertEquals(
            IndicatorPalette(Color(0xFFFFFF00), Color(0xFF1F4BFF)),
            indicatorPaletteFor(DesignatorState.Yellow(emptyMap()))
        )
        assertEquals(
            IndicatorPalette(Color(0xFF00FF00), Color(0xFFFF4FD8)),
            indicatorPaletteFor(
                DesignatorState.Green(
                    DroneSpecState(CtDroneSpec("testRemoteId"))
                )
            )
        )
    }

    @Test
    fun designatorDetailText_doesNotUseLongPressPromptForUnpairedStream() {
        assertEquals(
            "Telemetry not attached (mapStatus:Standalone)",
            designatorDetailText(
                designatorState = DesignatorState.Yellow(emptyMap()),
                mapStatus = "Standalone",
                interactionEnabled = true
            )
        )
        assertEquals(
            "Telemetry not attached (mapStatus:Standalone)",
            designatorDetailText(
                designatorState = DesignatorState.Yellow(emptyMap()),
                mapStatus = "Standalone",
                interactionEnabled = false
            )
        )
    }

    @Test
    fun telemetryChipText_describesUnpairedAndPairedStreams() {
        assertEquals(
            "Pair Telemetry",
            telemetryChipTextFor(
                designatorState = DesignatorState.Yellow(emptyMap()),
                display = null
            )
        )
        assertEquals(
            "No Telemetry",
            telemetryChipTextFor(
                designatorState = DesignatorState.Red,
                display = null
            )
        )
        assertEquals(
            "ATO:Unk AGL:Unk AOL:Unk RNG:Unk TRK:Unk",
            telemetryChipTextFor(
                designatorState = DesignatorState.Green(
                    DroneSpecState(CtDroneSpec("testRemoteId"))
                ),
                display = null
            )
        )
    }

    @Test
    fun compactLiveTelemetry_matchesMapEntriesAndOrder() {
        assertEquals(
            "ATO:7' AGL:0' AOL:Unk RNG:26' TRK:Unk",
            formatCompactTelemetry(
                DroneDisplayState(
                    headingDeg = null,
                    aglFt = 0.0,
                    atoFt = 7.0,
                    rangeFt = 26.0,
                )
            )
        )
    }
}
