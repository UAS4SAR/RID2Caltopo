package org.ncssar.rid2caltopo.mapping

/** Flat-ground nadir sampling estimate. Wide timed capture uses observed 12 MP originals, not sensor maximum. */
object MappingResolution {
    data class Camera(val widthPixels: Int, val heightPixels: Int, val diagonalFovDegrees: Double)
    data class Profile(val id: String, val aircraft: String, val camera: String, val label: String,
        val thermal: Boolean, val sensor: Camera) {
        fun matchesOriginal(sensor:String, focal35mm:Double?):Boolean {
            if(thermal)return sensor=="thermal"
            val focal=focalLength35mm ?: return (sensor=="visible-wide" && (focal35mm==null || !focal35mm.isFinite() || focal35mm<=0 || kotlin.math.abs(focal35mm-24.0)<=2.0)) ||
                (sensor=="visible-zoom" && focal35mm!=null && focal35mm.isFinite() && kotlin.math.abs(focal35mm-24.0)<=2.0)
            return sensor in listOf("visible-wide","visible-zoom") && focal35mm!=null && focal35mm.isFinite() && kotlin.math.abs(focal35mm-focal)<=2.0
        }
        val focalLength35mm: Double? get() = when { id.endsWith(".visible.medium-tele") -> 70.0; id.endsWith(".visible.tele") -> 168.0; else -> null }
        val captureResolutionMode get() = if(id=="m4td.visible.wide")"observed-timed-12mp" else "planned-12mp"
        val isVisibleWide get() = !thermal && id.endsWith(".visible.wide")
        val aircraftSpec get() = aircraftSpecs.first { it.name == aircraft }
        val lens get() = if (thermal) "ir" else if (aircraftSpec.droneType == 67) "wide" else "visable"
        // Conservative planning policy; M30 photo cadence still needs controller/flight qualification.
        val minimumCaptureInterval get() = if (aircraftSpec.droneType == 67) 2.0 else if (focalLength35mm != null) 1.5 else 1.0
        val captureSettings get() = if (aircraftSpec.droneType == 67) "Pilot 2 check: Wide camera, JPEG, 4000 × 3000 (12 MP)." else "Pilot 2 check: Photo Resolution 4K and Image Ratio 4:3 (about 12 MP)."
    }

    const val MATRICE_4TD = "DJI Matrice 4TD"
    data class Aircraft(val name:String,val droneType:Int,val droneSubtype:Int,val payloadType:Int) {
        val droneModelKey get() = "0-$droneType-$droneSubtype"
        val payloadModelKey get() = "1-$payloadType-0"
    }
    val aircraftSpecs = listOf(Aircraft(MATRICE_4TD,100,1,99),Aircraft("DJI Matrice 4T",99,1,89),
        Aircraft("DJI Matrice 30",67,0,52),Aircraft("DJI Matrice 30T",67,1,53))
    val aircraftModels = aircraftSpecs.map { it.name } + "DJI Matrice 400"
    fun aircraftLabel(model:String) = when {
        model == MATRICE_4TD -> "$model · Pilot 2 lists M4D → M4TD"
        aircraftSpecs.any { it.name == model } -> model
        else -> "$model · camera profile needed"
    }
    val thermal = Camera(widthPixels = 640, heightPixels = 512, diagonalFovDegrees = 45.0)
    val daylightWide = Camera(widthPixels = 4032, heightPixels = 3024, diagonalFovDegrees = 82.0)

    /** Only profiles whose sensor geometry has been entered and reviewed are selectable. */
    val profiles = listOf(
        Profile("m4td.visible.wide", MATRICE_4TD, "Wide camera", "Visible · wide (1×), timed 12 MP", false, daylightWide),
        Profile("m4td.visible.medium-tele", MATRICE_4TD, "Medium tele camera", "Visible · medium tele (3×), 12 MP", false,
            Camera(widthPixels = 4032, heightPixels = 3024, diagonalFovDegrees = 35.0)),
        Profile("m4td.visible.tele", MATRICE_4TD, "Tele camera", "Visible · tele (7×), 12 MP", false,
            Camera(widthPixels = 4096, heightPixels = 3072, diagonalFovDegrees = 15.0)),
        Profile("m4td.thermal", MATRICE_4TD, "Thermal camera", "Thermal", true, thermal),
        Profile("m4t.visible.wide", "DJI Matrice 4T", "Wide camera", "Visible · wide (1×), timed 12 MP", false, daylightWide),
        Profile("m4t.visible.medium-tele", "DJI Matrice 4T", "Medium tele camera", "Visible · medium tele (3×), 12 MP", false, Camera(4032,3024,35.0)),
        Profile("m4t.visible.tele", "DJI Matrice 4T", "Tele camera", "Visible · tele (7×), 12 MP", false, Camera(4096,3072,15.0)),
        Profile("m4t.thermal", "DJI Matrice 4T", "Thermal camera", "Thermal", true, thermal),
        Profile("m30.visible.wide", "DJI Matrice 30", "Wide camera", "Visible · wide (1×), 12 MP", false, Camera(4000,3000,84.0)),
        Profile("m30t.visible.wide", "DJI Matrice 30T", "Wide camera", "Visible · wide (1×), 12 MP", false, Camera(4000,3000,84.0)),
        Profile("m30t.thermal", "DJI Matrice 30T", "Thermal camera", "Thermal", true, Camera(640,512,61.0))
    )

    fun cameraLabel(profileId: String?, fallbackName: String? = null): String = when (profileId) {
        "m4td.visible.wide", "m4t.visible.wide", "m30.visible.wide", "m30t.visible.wide" -> "Wide (1×), 12 MP"
        "m4td.visible.medium-tele", "m4t.visible.medium-tele" -> "Medium tele (3×), 12 MP"
        "m4td.visible.tele", "m4t.visible.tele" -> "Tele (7×), 12 MP"
        "m4td.thermal", "m4t.thermal", "m30t.thermal" -> "IR (thermal)"
        else -> fallbackName?.takeIf { it.isNotBlank() } ?: "Camera unavailable"
    }

    fun profilesFor(aircraft: String): List<Profile> = profiles.filter { it.aircraft == aircraft }

    fun gsdCmPerPixel(heightMeters: Double, camera: Camera): Double? {
        if (!heightMeters.isFinite() || heightMeters <= 0.0) return null
        val diagonalPixels = kotlin.math.hypot(camera.widthPixels.toDouble(), camera.heightPixels.toDouble())
        val horizontalFov = 2.0 * kotlin.math.atan(
            kotlin.math.tan(Math.toRadians(camera.diagonalFovDegrees / 2.0)) * camera.widthPixels / diagonalPixels
        )
        val widthMeters = 2.0 * heightMeters * kotlin.math.tan(horizontalFov / 2.0)
        return widthMeters * 100.0 / camera.widthPixels
    }

    fun heightMetersForGsd(gsdCmPerPixel: Double, camera: Camera): Double? {
        if (!gsdCmPerPixel.isFinite() || gsdCmPerPixel <= 0.0) return null
        val oneMeterGsd = MappingResolution.gsdCmPerPixel(camera = camera, heightMeters = 1.0) ?: return null
        return gsdCmPerPixel / oneMeterGsd
    }

    fun pixelsPerFoot(gsdCmPerPixel: Double): Double? =
        if (gsdCmPerPixel.isFinite() && gsdCmPerPixel > 0.0) 30.48 / gsdCmPerPixel else null
}
