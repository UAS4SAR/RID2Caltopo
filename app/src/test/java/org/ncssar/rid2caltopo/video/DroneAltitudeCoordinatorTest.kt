import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.GeoTiffDemSource

class DroneAltitudeCoordinatorTest {
    @Test fun manualCalibrationOverridesReportedHeightForAgl() {
        val takeoff=500.0-15.24
        val result=DroneAltitudeCoordinator.calculateDemBackedAglMeters(500.0,5.0,
            DroneAltitudeCalibration(takeoff,AtoSeedSource.MANUAL),takeoff-300.0,300.0,1.0)
        assertEquals(15.24,result,0.00001)
    }
    @Test
    fun shouldPreserveCalibrationOnMapReconnect_keepsLockedReferences() {
        assertTrue(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO_SEALED)
            )
        )
        assertTrue(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.MANUAL)
            )
        )
    }

    @Test
    fun shouldPreserveCalibrationOnMapReconnect_dropsUnsealedAutoReference() {
        assertFalse(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO)
            )
        )
        assertFalse(DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(null))
    }

    @Test
    fun shouldResetAutoCalibrationForFlightChange_resetsAutoReferences() {
        assertTrue(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO)
            )
        )
        assertTrue(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO_SEALED)
            )
        )
        assertTrue(DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(null))
    }

    @Test
    fun shouldResetAutoCalibrationForFlightChange_preservesManualReferences() {
        assertFalse(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.MANUAL)
            )
        )
    }

    @Test
    fun calculateDemBackedAglMeters_prefersAtoOverDriftingAbsoluteAltitude() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglAtReturn = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = 0.0,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw,
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(0.0, aglAtReturn, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_appliesTerrainDeltaToAto() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglOverLowerGround = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = 10.0,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw - (5.0 / demScaleToMeters),
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(15.0, aglOverLowerGround, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_fallsBackToAbsoluteAltitudeWithoutAto() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglWithoutAto = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = null,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw,
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(0.0, aglWithoutAto, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_clampsNegativeDemResultToGroundLevel() {
        val calibration = DroneAltitudeCalibration(100.0, AtoSeedSource.AUTO_SEALED)

        val aglMeters = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 100.0,
            ridHeightAtoM = 0.0,
            calibration = calibration,
            correctionM = 0.0,
            demGroundRaw = 100.9144,
            demScaleToMeters = 1.0,
        )

        assertEquals(0.0, aglMeters, 0.000001)
    }

    @Test
    fun demPositionSamplingKeyChangesWithinOneArcSecondCell() {
        val first = DemElevationService.positionSamplingKey(39.153600, -121.132110)
        val nearby = DemElevationService.positionSamplingKey(39.153620, -121.132090)

        assertFalse(first == nearby)
    }

    @Test
    fun geoTiffUtmConversionMatchesKnownCoordinate() {
        val utm = GeoTiffDemSource.latLonToUtm(43.19113, -110.92524, 26912)!!
        assertEquals(506_075.0, utm.first, 5.0)
        assertEquals(4_782_042.0, utm.second, 5.0)
    }

    @Test
    fun geoTiffConusAlbersConversionMatchesEpsg6350Reference() {
        val albers = GeoTiffDemSource.latLonToConusAlbers(39.2616, -121.0608)
        assertEquals(-2_117_785.808, albers.first, 0.02)
        assertEquals(2_085_095.786, albers.second, 0.02)
    }

    @Test
    fun sealedCorrectionUsesTakeoffTerrainAndPreservesExistingWhenUnavailable() {
        assertEquals(
            -20.78,
            DroneAltitudeCoordinator.refinedCorrectionFromTakeoffDem(
                takeoffTrackAltitudeM = 523.5,
                takeoffDemGroundRaw = 544.28,
                demScaleToMeters = 1.0,
                existingCorrectionM = -8.2,
            )!!,
            0.000001,
        )
        assertEquals(
            -8.2,
            DroneAltitudeCoordinator.refinedCorrectionFromTakeoffDem(
                takeoffTrackAltitudeM = 523.5,
                takeoffDemGroundRaw = null,
                demScaleToMeters = 1.0,
                existingCorrectionM = -8.2,
            )!!,
            0.000001,
        )
    }
}
