package org.ncssar.rid2caltopo.mapping

import java.io.DataInputStream
import java.io.File

/** Reads DJI's XMP camera identity without decoding or changing the original JPEG. */
internal object DjiOriginalCamera {
    fun matches(file:File, mission:org.json.JSONObject):Boolean {
        val profile=MappingResolution.profiles.firstOrNull { it.id==mission.optString("cameraProfileId") } ?: return false
        val sensor=sensor(file)
        val actual=runCatching { android.media.ExifInterface(file.path).getAttributeDouble("FocalLengthIn35mmFilm",Double.NaN) }.getOrDefault(Double.NaN)
        return profile.matchesOriginal(sensor,actual)
    }
    private val source = Regex("(?:[\\w-]+:)?ImageSource\\s*=\\s*['\"](InfraredCamera|WideCamera|ZoomCamera|TeleCamera|MediumTeleCamera)['\"]")
    fun sensor(file: File): String = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readUnsignedShort() == 0xffd8)
            var inspected = 2
            while (inspected < 2 * 1024 * 1024) {
                require(input.readUnsignedByte() == 0xff)
                var marker = input.readUnsignedByte()
                while (marker == 0xff) marker = input.readUnsignedByte()
                if (marker == 0xda || marker == 0xd9) break
                if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 0x01) continue
                val length = input.readUnsignedShort() - 2
                require(length in 0..65533)
                inspected += length + 4
                val segment = ByteArray(length); input.readFully(segment)
                if (marker == 0xe1) {
                    when (source.find(segment.toString(Charsets.UTF_8))?.groupValues?.get(1)) {
                        "InfraredCamera" -> return@use "thermal"
                        "WideCamera" -> return@use "visible-wide"
                        "ZoomCamera", "TeleCamera", "MediumTeleCamera" -> return@use "visible-zoom"
                    }
                }
            }
            "unknown"
        }
    }.getOrDefault("unknown")
}
