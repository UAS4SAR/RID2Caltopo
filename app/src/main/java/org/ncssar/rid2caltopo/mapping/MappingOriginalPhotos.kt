package org.ncssar.rid2caltopo.mapping

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class MappingOriginalPhoto(val missionId: String, val digest: String, val name: String, val sizeBytes: Long, val file: File)

/** Resolve only completed, size-verified mission originals; supplied names never become paths. */
internal object MappingOriginalFiles {
    fun root(filesDir: File, missionId: String): File {
        require(UUID.fromString(missionId).toString() == missionId) { "Invalid mission identifier." }
        return File(filesDir, "mapping-missions/$missionId")
    }
    fun photo(filesDir: File, missionId: String, digest: String): MappingOriginalPhoto {
        MappingPhotoStore.checkDigest(digest)
        val root = root(filesDir, missionId)
        val record = JSONObject(File(root, "inventory/$digest.json").readText())
        val file = File(root, "originals/$digest.jpg")
        require(record.optBoolean("complete") && record.optString("sha256") == digest &&
            file.isFile && file.length() == record.optLong("sizeBytes", -1) &&
            file.canonicalFile.parentFile == File(root, "originals").canonicalFile) { "Original photo is missing or incomplete." }
        val name = record.optString("name").takeIf { name -> name.isNotBlank() && name.length <= 180 &&
            name.none { it == '/' || it == '\\' || it.code < 32 } && name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") } }
            ?: "$digest.jpg"
        return MappingOriginalPhoto(missionId, digest, name, file.length(), file)
    }
    fun list(filesDir: File, missionId: String): List<MappingOriginalPhoto> =
        File(root(filesDir, missionId), "inventory").listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            runCatching { photo(filesDir, missionId, it.nameWithoutExtension) }.getOrNull()
        }.sortedWith(compareBy<MappingOriginalPhoto> { it.name }.thenBy { it.digest })

    fun missionForLayer(filesDir: File, layerRoot: File): String? {
        val record = runCatching { JSONObject(File(layerRoot, "layer.json").readText()) }.getOrNull()
        val explicit = record?.optString("missionId")?.takeIf { runCatching { root(filesDir, it).isDirectory }.getOrDefault(false) }
        if (explicit != null) return explicit
        // Older results may be associated by processing-status or original digests.
        val index = MappingMissionStorage.index(filesDir)
        return File(filesDir, "mapping-missions").listFiles().orEmpty().firstOrNull { mission ->
            runCatching { index.resultRoots(mission.name).any { it.canonicalFile == layerRoot.canonicalFile } }.getOrDefault(false)
        }?.name
    }
    fun uri(context: Context, photo: MappingOriginalPhoto): Uri =
        Uri.parse("content://${context.packageName}.mapping-share/originals/${photo.missionId}/${photo.digest}.jpg")
}

@Composable internal fun MappingOriginalPhotosDialog(missionId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<Result<List<MappingOriginalPhoto>>?>(null, missionId) {
        value = withContext(Dispatchers.IO) { runCatching { MappingOriginalFiles.list(context.filesDir, missionId) } }
    }
    var selected by remember(missionId) { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val photos = loaded?.getOrNull().orEmpty()
    fun open(items: List<MappingOriginalPhoto>, view: Boolean) {
        if (items.isEmpty()) return
        runCatching {
            val uris = ArrayList(items.map { MappingOriginalFiles.uri(context, it) })
            val intent = if (view) Intent(Intent.ACTION_VIEW).setDataAndType(uris.first(), "image/jpeg")
                else if (uris.size == 1) Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uris.first())
                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.clipData = ClipData.newRawUri("Original photos", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(if (view) intent else Intent.createChooser(intent, "Share original photos"))
        }.onFailure { error = if (view) "No photo viewer is available. You can still share the original photo." else it.message }
    }
    org.ncssar.rid2caltopo.ui.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("mapping-original-photos")) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Original photos", style = MaterialTheme.typography.headlineSmall)
                Text("${photos.size} verified originals · original filenames and bytes are preserved")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = if (selected.size == photos.size) emptySet() else photos.map { it.digest }.toSet() }) { Text(if (selected.size == photos.size) "Clear selection" else "Select all") }
                    TextButton(enabled = selected.isNotEmpty(), onClick = { open(photos.filter { it.digest in selected }, false) }) { Text("Share selected (${selected.size})") }
                }
                if (loaded == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: loaded?.exceptionOrNull()?.localizedMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loaded?.isSuccess == true && photos.isEmpty()) Text("No verified original photos are stored on this device for this mission.")
                LazyColumn(Modifier.weight(1f)) {
                    items(photos, key = { it.digest }) { photo ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(Modifier.clickable { selected = if (photo.digest in selected) selected - photo.digest else selected + photo.digest }) {
                                Checkbox(checked = photo.digest in selected, onCheckedChange = { checked -> selected = if (checked) selected + photo.digest else selected - photo.digest })
                                Column(Modifier.weight(1f)) { Text(photo.name); Text(MappingStorageUsage.format(photo.sizeBytes), style = MaterialTheme.typography.bodySmall) }
                            }
                            Row { TextButton(onClick = { open(listOf(photo), true) }) { Text("View") }; TextButton(onClick = { open(listOf(photo), false) }) { Text("Share") } }
                            HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text("Done") }
            }
        }
    }
}

internal data class MappingInspectorFiles(val cameraLabel: String, val missionId: String?, val hasOriginals: Boolean,
    val archiveAvailable: Boolean, val surfaces: List<String>) {
    companion object {
        fun inspect(filesDir: File, result: File, layer: MappingLayer): MappingInspectorFiles {
            val missionId = MappingOriginalFiles.missionForLayer(filesDir, result)
            val plan = missionId?.let { runCatching { JSONObject(File(MappingOriginalFiles.root(filesDir, it), "mission.json").readText()) }.getOrNull() }
            val manifest = runCatching { JSONObject(File(result, "layer.json").readText()) }.getOrNull()
            val cameraId = layer.cameraProfileId.ifBlank { manifest?.optString("cameraProfileId").orEmpty() }.ifBlank { plan?.optString("cameraProfileId").orEmpty() }
            val single = File(result, "surface/surface.glb")
            val surfaces = if (single.isFile) listOf("surface.glb") else {
                val regions = runCatching { JSONObject(File(result, "surface/collection.json").readText()).getJSONArray("regions") }.getOrNull()
                if (regions == null) emptyList() else (0 until regions.length()).mapNotNull { index ->
                    regions.optJSONObject(index)?.optString("path")?.takeIf { it.matches(Regex("regions/[0-9]{1,3}/surface\\.glb")) && File(result, "surface/$it").isFile }
                }
            }
            return MappingInspectorFiles(MappingResolution.cameraLabel(cameraId, plan?.optString("cameraName")), missionId,
                missionId?.let { File(MappingOriginalFiles.root(filesDir, it), "originals").listFiles().orEmpty().any { photo -> photo.extension == "jpg" } } == true,
                File(result, "share.zip").isFile, surfaces)
        }
    }
}
