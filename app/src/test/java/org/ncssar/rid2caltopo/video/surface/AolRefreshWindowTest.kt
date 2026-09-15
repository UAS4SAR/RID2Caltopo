package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.assertEquals
import org.junit.Test

class AolRefreshWindowTest {
    @Test fun decoderAndPositionCallbackInterleavingPreservesVideoReference() {
        val references = AolVideoReferenceContinuity()
        val window = AolRefreshWindow()
        fun update(candidate: Pair<Double, Double>?, time: Long): AolState {
            val anchor = references.observe(100L, candidate)
            val reference = aolRefreshReference("flight", anchor, anchor != null, null, "tiles")
            return window.display(reference, if (time == 0L) AolState(feet = -63.0) else null, true, time)
        }
        update(39.0 to -121.0, 0)
        // A newer decoder callback arrives before its matching position callback.
        assertEquals(-63.0, update(null, 200).feet!!, 0.0)
        assertEquals(-63.0, update(39.000001 to -121.0, 400).feet!!, 0.0)
        assertEquals(-63.0, update(null, 600).feet!!, 0.0)
        assertEquals(MeasurementStatus.Pending, update(null, 1700).status)
        assertEquals(null, references.observe(200L, null))
    }

    @Test fun movingStreamCanDeliverCompletedWorkWithoutExtendingSampleAge() {
        val window = AolRefreshWindow()
        window.display("flight", null, true, 0)
        window.display("flight", null, true, 200) // New position arrives during calculation.
        window.completed("flight", AolState(feet = 42.0), 0, 300)
        assertEquals(42.0, window.display("flight", null, true, 400).feet!!, 0.0)
        assertEquals(42.0, window.display("flight", null, true, 1499).feet!!, 0.0)
        assertEquals(MeasurementStatus.Pending, window.display("flight", null, true, 1500).status)
        window.completed("flight", AolState(feet = 43.0), 0, 1800)
        assertEquals(MeasurementStatus.Pending, window.display("flight", null, true, 1800).status)
        window.completed("flight", AolState(feet = 44.0), 2000, 2100)
        assertEquals(MeasurementStatus.Pending, window.display("new flight", null, true, 2101).status)
    }

    @Test fun pendingAglShowsProvisionalValueButMissingAndStaleRemainExplicit() {
        assertEquals("112'?", measurementLabel(112.0, MeasurementStatus.Pending, "'", 1000.0, true))
        assertEquals("--", measurementLabel(null, MeasurementStatus.Pending, "'", 1000.0, true))
        assertEquals("--", measurementLabel(112.0, MeasurementStatus.Pending))
        assertEquals("POS?", measurementLabel(112.0, MeasurementStatus.Stale, "'", 1000.0, true))
        assertEquals("Unk", measurementLabel(112.0, MeasurementStatus.Unknown, "'", 1000.0, true))
    }

    @Test fun streamedReferenceUpdatesRetainOnlyBoundedGrace() {
        val window = AolRefreshWindow()
        fun reference(lat: Double, flight: String = "flight1", manual: Double? = null) =
            aolRefreshReference(flight, lat to -121.0, true, manual, "tiles1")
        window.display(reference(39.0), AolState(feet = -63.0), true, 0)
        assertEquals(-63.0, window.display(reference(39.000001), null, true, 100).feet!!, 0.0)
        assertEquals(-63.0, window.display(reference(39.000002), null, true, 1599).feet!!, 0.0)
        assertEquals(MeasurementStatus.Pending, window.display(reference(39.000003), null, true, 1600).status)
        window.display(reference(39.000003), AolState(feet = -62.0), true, 1601)
        assertEquals(MeasurementStatus.Pending, window.display(reference(39.000003, manual = 100.0), null, true, 1602).status)
        window.display(reference(39.0), AolState(feet = -63.0), true, 1700)
        assertEquals(MeasurementStatus.Pending, window.display(reference(39.0, flight = "flight2"), null, true, 1701).status)
    }

    @Test fun airborneVideoUsesExplicitReferenceWithoutGroundStatus() {
        val reference = videoAolReference(39.15, -121.13, 20.0, true)
        assertEquals(39.15 to -121.13, reference)
        assertEquals(null, aolPrerequisiteState(reference != null, true, false))
        assertEquals(null, videoAolReference(null, -121.13, 20.0, true))
        assertEquals(null, videoAolReference(Double.NaN, -121.13, 20.0, true))
        assertEquals(null, videoAolReference(39.15, -121.13, 20.0, false))
        assertEquals(null, videoAolReference(39.15, -121.13, null, true))
        assertEquals(null, videoAolReference(0.0, 0.0, 20.0, true))
    }

    @Test fun missingLaunchStaysUnknownUntilCalibrationProvidesReference() {
        val window = AolRefreshWindow()
        for (time in 0L..3000L step 100) {
            val blocked = aolPrerequisiteState(false, true, false)
            assertEquals(MeasurementStatus.CalibrationRequired, window.display("no anchor", blocked, true, time).status)
            assertEquals("CAL", measurementLabel(null, blocked!!.status, " ft"))
        }
        assertEquals(null, aolPrerequisiteState(true, true, false))
        assertEquals(MeasurementStatus.Pending, window.display("calibrated", null, true, 3100).status)
        assertEquals(8.0, window.display("calibrated", AolState(feet = 8.0), true, 3200).feet!!, 0.0)
        assertEquals(MeasurementStatus.Stale, aolPrerequisiteState(false, true, true)!!.status)
        assertEquals(MeasurementStatus.Unknown, aolPrerequisiteState(true, false, false)!!.status)
    }

    @Test fun graceExpiresWithoutBeingExtendedByUpdates() {
        val window = AolRefreshWindow()
        assertEquals(MeasurementStatus.Pending, window.display("launch", null, true, 0).status)
        window.display("launch", AolState(feet = -9.0), true, 100)
        assertEquals(-9.0, window.display("launch", null, true, 200).feet!!, 0.0)
        assertEquals(-9.0, window.display("launch", null, true, 1699).feet!!, 0.0)
        assertEquals(MeasurementStatus.Pending, window.display("launch", null, true, 1700).status)
        assertEquals(-8.0, window.display("launch", AolState(feet = -8.0), true, 1800).feet!!, 0.0)
    }
    @Test fun failureReferenceChangeAndMissingHeightClearOldValue() {
        val window = AolRefreshWindow()
        window.display("launch", AolState(feet = -9.0), true, 0)
        assertEquals(MeasurementStatus.Unknown, window.display("launch", AolState(reason = "Missing tile"), true, 1).status)
        assertEquals(MeasurementStatus.Pending, window.display("launch", null, true, 2).status)
        window.display("launch", AolState(feet = -9.0), true, 3)
        assertEquals(MeasurementStatus.Pending, window.display("new launch", null, true, 4).status)
        window.display("new launch", AolState(feet = -9.0), true, 5)
        assertEquals(MeasurementStatus.Pending, window.display("new launch", null, false, 6).status)
    }
}
