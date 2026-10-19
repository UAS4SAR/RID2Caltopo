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
        val focalLength35mm: Double? get() = when(id) { "m4td.visible.medium-tele" -> 70.0; "m4td.visible.tele" -> 168.0; else -> null }
    }

    const val MATRICE_4TD = "DJI Matrice 4TD"
    val aircraftModels = listOf(MATRICE_4TD, "DJI Matrice 4T", "DJI Matrice 30/30T", "DJI Matrice 400")
    val thermal = Camera(widthPixels = 640, heightPixels = 512, diagonalFovDegrees = 45.0)
    val daylightWide = Camera(widthPixels = 4032, heightPixels = 3024, diagonalFovDegrees = 82.0)

    /** Only profiles whose sensor geometry has been entered and reviewed are selectable. */
    val profiles = listOf(
        Profile("m4td.visible.wide", MATRICE_4TD, "Wide camera", "Visible · wide (1×), timed 12 MP", false, daylightWide),
        Profile("m4td.visible.medium-tele", MATRICE_4TD, "Medium tele camera", "Visible · medium tele (3×), 12 MP", false,
            Camera(widthPixels = 4032, heightPixels = 3024, diagonalFovDegrees = 35.0)),
        Profile("m4td.visible.tele", MATRICE_4TD, "Tele camera", "Visible · tele (7×), 12 MP", false,
            Camera(widthPixels = 4096, heightPixels = 3072, diagonalFovDegrees = 15.0)),
        Profile("m4td.thermal", MATRICE_4TD, "Thermal camera", "Thermal", true, thermal)
    )

    fun cameraLabel(profileId: String?, fallbackName: String? = null): String = when (profileId) {
        "m4td.visible.wide" -> "Wide (1×), 12 MP"
        "m4td.visible.medium-tele" -> "Medium tele (3×), 12 MP"
        "m4td.visible.tele" -> "Tele (7×), 12 MP"
        "m4td.thermal" -> "IR (thermal)"
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
