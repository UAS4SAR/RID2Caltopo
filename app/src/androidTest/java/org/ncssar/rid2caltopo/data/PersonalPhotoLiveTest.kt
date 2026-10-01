package org.ncssar.rid2caltopo.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.ncssar.rid2caltopo.ui.CaltopoPersonalProbeActivity
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicitly opt-in. Uses only the designated test map and synthetic image. No login data leaves the device. */
@RunWith(AndroidJUnit4::class)
class PersonalPhotoLiveTest {
    @Test fun publishVerifyAndRemoveSyntheticPhoto() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("personalPhotoMap") == "G00CPSS")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        CaltopoPersonalSession.initialize(context)
        context.startActivity(Intent(context, CaltopoPersonalProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("picker", true).putExtra("catalog", true))
        var catalog: JSONObject? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (catalog == null && System.currentTimeMillis() < deadline) {
            catalog = runCatching { CaltopoPersonalSession.readCatalog() }.getOrNull()
            if (catalog == null) Thread.sleep(500)
        }
        assertNotNull("Saved CalTopo login must load the native catalog", catalog)
        instrumentation.runOnMainSync { CaltopoPersonalSession.acceptCatalog(catalog!!) }
        val token = catalog!!.getJSONObject("grants").getString("G00CPSS")
        CaltopoPersonalSession.activate(token, "G00CPSS")
        val auth = CaltopoPersonalSession.capture("")!!
        val prefs = context.getSharedPreferences("personal-photo-live-test", 0)
        val marker = prefs.getString("marker", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("marker", it).commit() }
        val media = UUID.nameUUIDFromBytes((marker + ":media").toByteArray()).toString()
        val link = UUID.nameUUIDFromBytes((marker + ":link").toByteArray()).toString()
        CaltopoPersonalSession.authorizeMedia(auth, media)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        fun request(path: String, delete: Boolean = false): Pair<Int, ByteArray> {
            val req = Request.Builder().url("https://caltopo.com$path")
                .header("Cookie", CaltopoPersonalSession.cookie(auth, path))
                .header("Origin", "https://caltopo.com").header("Referer", "https://caltopo.com/m/G00CPSS")
            if (delete) req.delete()
            return client.newCall(req.build()).execute().use { it.code to (it.body?.bytes() ?: byteArrayOf()) }
        }
        try {
            if (InstrumentationRegistry.getArguments().getString("inspectMedia") == "true") {
                val response = request("/api/v1/media/$media")
                val envelope = JSONObject(String(response.second))
                val result = envelope.optJSONObject("result")
                android.util.Log.i("PersonalPhotoLiveTest", "Media structure HTTP=${response.first}, rootKeys=${envelope.keys().asSequence().toList()}, resultKeys=${result?.keys()?.asSequence()?.toList()}, propertyKeys=${result?.optJSONObject("properties")?.keys()?.asSequence()?.toList()}, metadataKeys=${result?.optJSONObject("metadata")?.keys()?.asSequence()?.toList()}, expectedOwner=${result?.optJSONObject("properties")?.optString("creator") == auth.mediaOwnerID}")
                return
            }
            if (InstrumentationRegistry.getArguments().getString("cleanupOnly") == "true") return
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            repeat(2) {
            val done = CountDownLatch(1)
            var success = false
            var code = 0
            CaltopoSession.PublishStoredPhoto("G00CPSS", auth.credentialKey, marker, 0.0, 0.0,
                "RID2Caltopo 2.4.0 photo validation", "Synthetic test photo; removed after validation", "", System.currentTimeMillis(), image) {
                success = it.success(); code = it.responseCode; done.countDown()
            }
            assertTrue("Photo workflow timed out", done.await(120, TimeUnit.SECONDS))
            assertTrue("Native photo workflow failed with HTTP $code", success)
            }
            val snapshot = request("/api/v1/map/G00CPSS/since/0")
            assertEquals(200, snapshot.first)
            val features = JSONObject(String(snapshot.second)).getJSONObject("result").getJSONObject("state").getJSONArray("features")
            val ids = (0 until features.length()).map { features.getJSONObject(it).optString("id") }
            assertTrue("Marker missing from map", marker in ids)
            assertTrue("Photo attachment missing from map", link in ids)
            val original = request("/api/v1/media/$media/original")
            assertEquals("Uploaded photo cannot be downloaded", 200, original.first)
            assertNotNull("Uploaded photo is not a decodable image", android.graphics.BitmapFactory.decodeByteArray(original.second, 0, original.second.size))
            android.util.Log.i("PersonalPhotoLiveTest", "Native photo upload, map marker, attachment, and original image verified")
        } finally {
            var cleaned = true
            var backendRetained = false
            for (path in listOf("/api/v1/map/G00CPSS/MapMediaObject/$link", "/api/v1/map/G00CPSS/Marker/$marker", "/api/v1/media/$media")) {
                val code = runCatching { request(path, true).first }.getOrDefault(0)
                android.util.Log.i("PersonalPhotoLiveTest", "Cleanup ${if (path.contains("MapMediaObject")) "attachment" else if (path.contains("Marker")) "marker" else "backend media"}: HTTP $code")
                if (path.startsWith("/api/v1/media/") && code == 405) backendRetained = true
                else if (code !in listOf(200, 204, 400, 404)) cleaned = false
            }
            val snapshot = request("/api/v1/map/G00CPSS/since/0")
            val features = JSONObject(String(snapshot.second)).getJSONObject("result").getJSONObject("state").getJSONArray("features")
            val ids = (0 until features.length()).map { features.getJSONObject(it).optString("id") }
            android.util.Log.i("PersonalPhotoLiveTest", "Cleanup verification: markerAbsent=${marker !in ids}, attachmentAbsent=${link !in ids}")
            assertTrue("Test map cleanup incomplete", marker !in ids && link !in ids && cleaned)
            if (!backendRetained) prefs.edit().remove("marker").commit()
            else android.util.Log.i("PersonalPhotoLiveTest", "Backend media DELETE is unsupported (405); saved ID retained for future cleanup, map objects are absent")
            CaltopoPersonalSession.activate(null, "")
            android.util.Log.i("PersonalPhotoLiveTest", "Test map objects removed and absence verified")
        }
    }
}
