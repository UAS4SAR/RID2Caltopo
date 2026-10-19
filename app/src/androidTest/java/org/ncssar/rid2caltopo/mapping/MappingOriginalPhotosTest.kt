package org.ncssar.rid2caltopo.mapping

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic mission only; never changes a real flight or launches a sharing recipient. */
@RunWith(AndroidJUnit4::class)
class MappingOriginalPhotosTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun browserSelectsOriginalAndProviderSharesItsExactBytesAndName() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString();val digest="d".repeat(64)
        val root=File(context.filesDir,"mapping-missions/$id")
        val bytes=byteArrayOf(-1,-40,1,2,3,-1,-39)
        try {
            File(root,"originals/$digest.jpg").apply { parentFile!!.mkdirs();writeBytes(bytes) }
            File(root,"inventory/$digest.json").apply { parentFile!!.mkdirs();writeText(JSONObject()
                .put("sha256",digest).put("complete",true).put("sizeBytes",bytes.size).put("name","DJI_original_001.jpg").toString()) }
            val uri=Uri.parse("content://${context.packageName}.mapping-share/originals/$id/$digest.jpg")
            assertEquals("image/jpeg",context.contentResolver.getType(uri))
            context.contentResolver.query(uri,null,null,null,null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst());assertEquals("DJI_original_001.jpg",cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            }
            assertArrayEquals(bytes,context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(runCatching { context.contentResolver.openFileDescriptor(uri,"w")?.close() }.isFailure)
            compose.setContent { MaterialTheme { MappingOriginalPhotosDialog(id) {} } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("DJI_original_001.jpg").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("View").assertExists()
            compose.onNodeWithText("Select all").performClick()
            compose.onNodeWithText("Share selected (1)").assertIsEnabled()
            File(root,"originals/$digest.jpg").writeBytes(byteArrayOf(1))
            assertTrue(runCatching { context.contentResolver.openInputStream(uri)?.close() }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
