package org.ncssar.rid2caltopo.storage

import android.graphics.Bitmap
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Real OS provider and real EXIF-bearing JPEGs; restricted to disposable emulators. */
class RealArchiveProviderTest {
    @Test fun systemProviderPreservesOriginalPhotoAndRecoversAfterPublication() {
        check(Build.HARDWARE == "ranchu") { "Qualification must never run on a physical device" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("storageQualification") == "true")
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "qualification-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(255)
        bitmap.setPixels(IntArray(2048 * 1536) { random.nextInt() or (0xff shl 24) }, 0, 2048, 0, 0, 2048, 1536)
        try { source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { bitmap.recycle() }
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,7/1,2442/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,32/1,35556/1000")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val expected = source.readBytes()
        val automation = instrumentation.uiAutomation
        val directory = "Documents/r2c-qualification-${UUID.randomUUID()}"
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        shell("mkdir -p /sdcard/$directory")
        val device = UiDevice.getInstance(instrumentation)
        fun hierarchy(): String {
            val bytes = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(bytes)
            return "${device.currentPackageName}: " + bytes.toString("UTF-8")
        }
        var archive: DocumentFile? = null
        try {
            val authority = "com.android.externalstorage.documents"
            val document = DocumentsContract.buildDocumentUri(authority, "primary:$directory")
            val uri = DocumentsContract.buildTreeDocumentUri(authority, "primary:$directory")
            context.startActivity(Intent().setClassName("org.ncssar.rid2caltopo.test", QualificationArchiveGrantActivity::class.java.name)
                .putExtra("systemProvider", true).putExtra("initial", document.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000)) {
                "System folder picker did not offer its selection action: " + hierarchy()
            }.click()
            requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 5_000)) {
                "System folder picker did not ask for the tree grant: " + hierarchy()
            }.click()
            archive = requireNotNull(DocumentFile.fromTreeUri(context, uri))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (!archive.canWrite() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue("The real provider tree grant was not delivered", archive.canWrite())
            val tree = DocumentArchiveTree(context, archive)
            val target = "cache/local-maps/original.jpg"
            val interrupted = object : ArchiveTree by tree {
                override fun rename(from: String, to: String): Boolean {
                    val renamed = tree.rename(from, to)
                    if (renamed && to == target) throw IOException("process ended after publication")
                    return renamed
                }
            }
            assertThrows(IOException::class.java) { VerifiedArchiveTree(interrupted).put(source, target) }
            VerifiedArchiveTree(DocumentArchiveTree(context, archive)).recover()
            val retained = tree.read(target).use { it.readBytes() }
            assertArrayEquals(expected, retained)
            assertArrayEquals(expected, source.readBytes())
            val coordinate = FloatArray(2)
            assertTrue(ExifInterface(retained.inputStream()).getLatLong(coordinate))
            assertEquals(39.12345, coordinate[0].toDouble(), 0.00001)
            assertEquals(-121.54321, coordinate[1].toDouble(), 0.00001)
            // Access failures cannot erase a verified target or its original.
            val unavailable = object : ArchiveTree by tree {
                override fun read(path: String): InputStream = throw SecurityException("archive access revoked")
            }
            assertThrows(SecurityException::class.java) { VerifiedArchiveTree(unavailable).put(source, target) }
            assertArrayEquals(expected, tree.read(target).use { it.readBytes() })
        } finally {
            repeat(2) { if (device.currentPackageName.orEmpty().contains("documentsui")) device.pressBack() }
            archive?.delete(); source.delete()
            shell("rm -rf /sdcard/$directory")
        }
    }
}
