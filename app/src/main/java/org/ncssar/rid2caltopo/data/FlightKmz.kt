package org.ncssar.rid2caltopo.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One clue or local marker placed in a flight KMZ. */
class FlightKmzClue(
    val title: String,
    /** Published description (stored text plus the waypoint binding block). */
    val description: String,
    val capturedAtMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val jpeg: ByteArray?,
    val localOnly: Boolean,
    val binding: ClueBinding?,
)

data class FlightKmzPoint(val latitude: Double, val longitude: Double, val altitudeMeters: Double?)

/**
 * Local backup KMZ for a flight: its track plus every clue and local marker it owns. Written for
 * every recorded flight, with or without clues. Apple writes the same layout (OperationalFlightKMZ.swift).
 */
object FlightKmz {
    fun kml(title: String, points: List<FlightKmzPoint>, clues: List<FlightKmzClue>): String {
        val kml = StringBuilder()
        kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        kml.append("  <Document>\n")
        kml.append("    <name>").append(escapeXml(title)).append("</name>\n")
        kml.append("    <Style id=\"trackStyle\"><LineStyle><color>ffff0000</color><width>3</width></LineStyle></Style>\n")
        val coordinates = points.map {
            String.format(Locale.US, "%.6f,%.6f,%.1f", it.longitude, it.latitude, it.altitudeMeters ?: 0.0)
        }
        if (coordinates.size >= 2) {
            kml.append("    <Placemark>\n      <name>").append(escapeXml(title)).append("</name>\n      <styleUrl>#trackStyle</styleUrl>\n")
            kml.append("      <LineString><tessellate>1</tessellate><coordinates>\n")
            coordinates.forEach { kml.append("        ").append(it).append('\n') }
            kml.append("      </coordinates></LineString>\n    </Placemark>\n")
        } else if (coordinates.size == 1) {
            kml.append("    <Placemark>\n      <name>").append(escapeXml(title)).append("</name>\n")
            kml.append("      <Point><coordinates>").append(coordinates[0]).append("</coordinates></Point>\n    </Placemark>\n")
        }
        clues.forEachIndexed { index, clue ->
            kml.append("    <Placemark>\n")
            kml.append("      <name>").append(escapeXml(clue.title)).append("</name>\n")
            kml.append("      <TimeStamp><when>").append(ClueBindingText.iso(clue.capturedAtMs)).append("</when></TimeStamp>\n")
            if (clue.jpeg != null) {
                val description = clue.description.replace("]]>", "]]&gt;")
                kml.append("      <description><![CDATA[").append(description)
                    .append("<br/><img src=\"files/clue_").append(index).append(".jpg\"/>]]></description>\n")
            } else if (clue.description.isNotEmpty()) {
                kml.append("      <description>").append(escapeXml(clue.description)).append("</description>\n")
            }
            val data = mutableListOf("r2c_local_only" to clue.localOnly.toString())
            clue.binding?.let { data += ClueBindingText.extendedData(it) }
            kml.append("      <ExtendedData>\n")
            data.forEach { (name, value) ->
                kml.append("        <Data name=\"").append(escapeXml(name)).append("\"><value>")
                    .append(escapeXml(value)).append("</value></Data>\n")
            }
            kml.append("      </ExtendedData>\n")
            kml.append(String.format(Locale.US, "      <Point><coordinates>%.6f,%.6f,%.1f</coordinates></Point>\n",
                clue.longitude, clue.latitude, clue.altitudeMeters ?: 0.0))
            kml.append("    </Placemark>\n")
        }
        kml.append("  </Document>\n</kml>\n")
        return kml.toString()
    }

    fun archive(title: String, points: List<FlightKmzPoint>, clues: List<FlightKmzClue>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(kml(title, points, clues).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            clues.forEachIndexed { index, clue ->
                val jpeg = clue.jpeg ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry("files/clue_$index.jpg"))
                zip.write(jpeg)
                zip.closeEntry()
            }
            zip.finish()
        }
        return output.toByteArray()
    }

    /** True when [data] is a complete KMZ (readable zip with a non-empty doc.kml). */
    fun isValidArchive(data: ByteArray): Boolean = runCatching {
        var found = false
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                if (entry.name == "doc.kml" && bytes.isNotEmpty()) found = true
            }
        }
        found
    }.getOrDefault(false)

    /** Entry names and contents, for tests and verification. */
    fun entries(data: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
