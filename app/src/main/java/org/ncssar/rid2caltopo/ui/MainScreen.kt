/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import StreamsViewModel
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.location.Location
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.ArchiveCleanupDeleteResult
import org.ncssar.rid2caltopo.app.ArchiveCleanupDirectoryOption
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.app.LogArchiveDayOption
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.app.canDeleteArchiveCleanupSelection
import org.ncssar.rid2caltopo.app.defaultSelectedArchiveCleanupDirectories
import org.ncssar.rid2caltopo.app.formatArchiveSize
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.AppUpdateAdvisory
import org.ncssar.rid2caltopo.data.BluetoothRidTestPrefs
import org.ncssar.rid2caltopo.data.AppConfigStore
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.TrackerEnrollmentClient
import org.ncssar.rid2caltopo.data.TrackerEnrollmentResult
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.DriveSyncAction
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.FaaConfigManager
import org.ncssar.rid2caltopo.data.GoogleDriveConfigSync
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.data.MutualAidPackageManager
import org.ncssar.rid2caltopo.data.OrgConfigManager
import org.ncssar.rid2caltopo.data.OrgConfigToken
import org.ncssar.rid2caltopo.data.WaypointTrack
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionPanel
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionStatusChip
import org.ncssar.rid2caltopo.video.ComplianceAlertBell
import org.ncssar.rid2caltopo.video.ComplianceAlertDialog
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge
import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor
import org.opendroneid.android.bluetooth.DroneScoutBridgeStatusLogGate
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun showConfigImportToast(context: Context, message: String) {
    Toast.makeText(
        context.applicationContext,
        message,
        Toast.LENGTH_LONG
    ).show()
}

internal fun applyTrackerEnrollmentAndRefreshNotams(
    result: TrackerEnrollmentResult,
    requestNotamRefresh: () -> Unit = NotamCenter::requestImmediateRefresh,
    requestAirspaceRefresh: () -> Unit = AirspaceCenter::requestImmediateRefresh,
    requestTrackReplay: () -> Unit = CaltopoClient::CheckUnreportedFiles
) {
    TrackerEnrollmentClient.apply(result)
    if (result.reauthenticationUrl == null) {
        requestNotamRefresh()
        requestAirspaceRefresh()
        requestTrackReplay()
    }
}

internal fun resetPersistedStateAndRequestRequiredSetup(
    resetState: () -> Unit = CaltopoClient::ResetPersistedClientState,
    resetNotamRuntimeState: () -> Unit = NotamCenter::resetRuntimeState,
    requestArchiveSelection: () -> Unit,
    requestNotamRefresh: () -> Unit = NotamCenter::requestImmediateRefresh,
    requestAirspaceRefresh: () -> Unit = AirspaceCenter::requestImmediateRefresh
) {
    resetState()
    resetNotamRuntimeState()
    requestNotamRefresh()
    requestAirspaceRefresh()
    requestArchiveSelection()
}

private fun parseCsvTags(csv: String): List<String> {
    val tags = linkedSetOf<String>()
    csv.split(",").forEach { raw ->
        val tag = raw.trim()
        if (tag.isNotEmpty()) tags.add(tag)
    }
    return tags.toList()
}

private fun buildTagCsv(selectedKnownTags: Set<String>, customTagsText: String): String {
    val tags = linkedSetOf<String>()
    tags.addAll(selectedKnownTags)
    tags.addAll(parseCsvTags(customTagsText))
    return tags.joinToString(",")
}

private fun formatLocationOverride(location: Location?): String {
    if (location == null) return "Device GPS"
    return "%.6f, %.6f".format(location.latitude, location.longitude)
}

private fun formatOperationalProfileExpiry(epochMs: Long): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private fun parseLocationOverride(input: String): Location? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(",")
    if (parts.size != 2) throw IllegalArgumentException("Use 'lat,lng'")
    val lat = parts[0].trim().toDoubleOrNull() ?: throw IllegalArgumentException("Latitude is invalid")
    val lng = parts[1].trim().toDoubleOrNull() ?: throw IllegalArgumentException("Longitude is invalid")
    if (lat !in -90.0..90.0) throw IllegalArgumentException("Latitude must be between -90 and 90")
    if (lng !in -180.0..180.0) throw IllegalArgumentException("Longitude must be between -180 and 180")
    return Location("manual-override").apply {
        latitude = lat
        longitude = lng
        accuracy = 1.0f
    }
}

private val REQUIRED_MAP_CACHE_TAGS = listOf(
    "MapCacheDebug",
    "MapCacheTile",
    "MapCacheDEM",
    "MapCacheIcon",
    "MapCacheStore"
)

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
}

private fun shouldOfferDriveRestore(context: Context): Boolean {
    return !AppConfigStore.hasMeaningfulConfig(context) && CaltopoClient.GetArchiveUri() == null
}

internal fun shouldLaunchArchiveDirPicker(
    archiveUriMissing: Boolean,
    sessionArchiveDirAvailable: Boolean,
    forceArchiveDirPrompt: Boolean,
    driveRestoreEligibilityLoaded: Boolean,
    showDriveRestoreDialog: Boolean,
    driveSyncInProgress: Boolean,
    archiveDirPickerOpen: Boolean,
    archiveDirPromptSuppressed: Boolean = false
): Boolean {
    if (archiveDirPickerOpen || archiveDirPromptSuppressed) return false
    if (forceArchiveDirPrompt) return true
    return archiveUriMissing &&
        !sessionArchiveDirAvailable &&
        driveRestoreEligibilityLoaded &&
        !showDriveRestoreDialog &&
        !driveSyncInProgress
}

internal fun archiveDirPromptMessage(
    permissionMissing: Boolean,
    previousArchivePath: String? = null
): String =
    if (permissionMissing) {
        buildString {
            append("Android removed RID2Caltopo's access")
            previousArchivePath?.let { append(" to $it") }
            append(" when the app was uninstalled. Your existing files are still there. ")
            append("Reauthorize the folder to save drone tracks, logs, and map data.")
        }
    } else {
        previousArchivePath?.let {
            "Archive files are currently saved in $it. You can choose a different folder for drone tracks, logs, and map data."
        } ?: "Select an archive folder for drone tracks, logs, and map data."
    }

internal fun archiveDirDisplayPath(uriString: String?): String? {
    val encodedDocumentId = uriString
        ?.substringAfter("/tree/", "")
        ?.substringBefore("/document/")
        ?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val documentId = runCatching {
        URLDecoder.decode(encodedDocumentId, StandardCharsets.UTF_8.name())
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return if (documentId.startsWith("primary:")) {
        "sdcard:/" + documentId.removePrefix("primary:").trimStart('/')
    } else {
        documentId.replaceFirst(":", ":/")
    }
}

internal data class ArchiveDirPromptActions(
    val continueLabel: String,
    val chooseDifferentLabel: String?
)

internal fun archiveDirPromptActions(
    permissionMissing: Boolean,
    previousArchivePath: String?
): ArchiveDirPromptActions = ArchiveDirPromptActions(
    continueLabel = when {
        permissionMissing && previousArchivePath != null -> "Reauthorize existing folder"
        previousArchivePath != null -> "Choose another folder"
        else -> "Choose archive folder"
    },
    chooseDifferentLabel = when {
        permissionMissing && previousArchivePath != null -> "Choose another folder"
        previousArchivePath != null -> "Keep current folder"
        else -> "Set up later"
    }
)

internal sealed interface ArchiveDirSelectionResult {
    data object Selected : ArchiveDirSelectionResult
    data object Cancelled : ArchiveDirSelectionResult
    data class Failed(val detail: String) : ArchiveDirSelectionResult
}

internal fun <T> persistArchiveDirSelection(
    selection: T?,
    persistPermission: (T) -> Unit,
    activateSelection: (T) -> Unit,
    selectionIsUsable: (T) -> Boolean
): ArchiveDirSelectionResult {
    if (selection == null) return ArchiveDirSelectionResult.Cancelled
    return try {
        persistPermission(selection)
        activateSelection(selection)
        if (selectionIsUsable(selection)) {
            ArchiveDirSelectionResult.Selected
        } else {
            ArchiveDirSelectionResult.Failed("Android did not retain access to the selected folder.")
        }
    } catch (error: Exception) {
        ArchiveDirSelectionResult.Failed(
            error.message?.takeIf { it.isNotBlank() }
                ?: "Android did not grant access to the selected folder."
        )
    }
}

internal suspend fun <T> readMutualAidPackagePreviewOffMain(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    readPreview: () -> T
): T = withContext(dispatcher) {
    readPreview()
}

internal enum class ImportConfigFileKind {
    SURFACE_PACKAGE,
    JSON_CONFIG,
    MUTUAL_AID_PACKAGE,
    QR_IMAGE,
    UNSUPPORTED
}

internal data class TrackerQrSvgRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

internal data class TrackerQrSvgDocument(
    val minX: Double,
    val minY: Double,
    val width: Double,
    val height: Double,
    val rectangles: List<TrackerQrSvgRect>
)

internal data class RasterizedQrImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

private const val MAX_QR_SVG_BYTES = 2 * 1024 * 1024
private const val MAX_QR_SVG_DIMENSION = 2048
private const val QR_SVG_PIXELS_PER_MODULE = 12.0

private val svgNumberPattern = "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?"
private val svgViewBoxRegex = Regex(
    """\bviewBox\s*=\s*["']\s*($svgNumberPattern)[,\s]+($svgNumberPattern)[,\s]+($svgNumberPattern)[,\s]+($svgNumberPattern)\s*["']"""
)
private val svgPathDataRegex = Regex(
    """<path\b[^>]*\bd\s*=\s*["']([^"']+)["'][^>]*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val svgQrRectangleRegex = Regex(
    """M\s*($svgNumberPattern)\s*[,\s]\s*($svgNumberPattern)\s*H\s*($svgNumberPattern)\s*V\s*($svgNumberPattern)\s*H\s*($svgNumberPattern)\s*[zZ]"""
)

internal fun parseTrackerQrSvg(svg: String): TrackerQrSvgDocument? {
    if (svg.toByteArray(Charsets.UTF_8).size > MAX_QR_SVG_BYTES) return null
    val viewBox = svgViewBoxRegex.find(svg)?.groupValues ?: return null
    val minX = viewBox[1].toDoubleOrNull() ?: return null
    val minY = viewBox[2].toDoubleOrNull() ?: return null
    val width = viewBox[3].toDoubleOrNull() ?: return null
    val height = viewBox[4].toDoubleOrNull() ?: return null
    if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null

    val rectangles = mutableListOf<TrackerQrSvgRect>()
    val paths = svgPathDataRegex.findAll(svg).map { it.groupValues[1] }.toList()
    if (paths.isEmpty()) return null
    for (path in paths) {
        var consumedThrough = 0
        for (match in svgQrRectangleRegex.findAll(path)) {
            if (path.substring(consumedThrough, match.range.first).isNotBlank()) return null
            consumedThrough = match.range.last + 1
            val values = match.groupValues.drop(1).map { it.toDoubleOrNull() ?: return null }
            val x1 = values[0]
            val y1 = values[1]
            val x2 = values[2]
            val y2 = values[3]
            val closingX = values[4]
            if (kotlin.math.abs(closingX - x1) > 0.000_001) return null
            val left = minOf(x1, x2)
            val top = minOf(y1, y2)
            val rectWidth = kotlin.math.abs(x2 - x1)
            val rectHeight = kotlin.math.abs(y2 - y1)
            if (
                !left.isFinite() || !top.isFinite() ||
                !rectWidth.isFinite() || !rectHeight.isFinite() ||
                rectWidth <= 0.0 || rectHeight <= 0.0 ||
                left < minX || top < minY ||
                left + rectWidth > minX + width + 0.000_001 ||
                top + rectHeight > minY + height + 0.000_001
            ) return null
            rectangles += TrackerQrSvgRect(left, top, rectWidth, rectHeight)
        }
        if (path.substring(consumedThrough).isNotBlank()) return null
    }
    return rectangles.takeIf { it.isNotEmpty() }?.let {
        TrackerQrSvgDocument(minX, minY, width, height, it)
    }
}

internal fun rasterizeTrackerQrSvg(svg: String): RasterizedQrImage? {
    val document = parseTrackerQrSvg(svg) ?: return null
    val moduleSize = document.rectangles.minOf { minOf(it.width, it.height) }
    val preferredScale = QR_SVG_PIXELS_PER_MODULE / moduleSize
    val maximumScale = MAX_QR_SVG_DIMENSION / maxOf(document.width, document.height)
    val scale = minOf(preferredScale, maximumScale)
    if (!scale.isFinite() || scale <= 0.0) return null
    val width = kotlin.math.ceil(document.width * scale).toInt().coerceAtLeast(1)
    val height = kotlin.math.ceil(document.height * scale).toInt().coerceAtLeast(1)
    val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
    for (rect in document.rectangles) {
        val left = kotlin.math.floor((rect.x - document.minX) * scale).toInt().coerceIn(0, width)
        val right = kotlin.math.ceil((rect.x + rect.width - document.minX) * scale).toInt().coerceIn(0, width)
        val top = kotlin.math.floor((rect.y - document.minY) * scale).toInt().coerceIn(0, height)
        val bottom = kotlin.math.ceil((rect.y + rect.height - document.minY) * scale).toInt().coerceIn(0, height)
        for (y in top until bottom) {
            java.util.Arrays.fill(pixels, y * width + left, y * width + right, 0xFF000000.toInt())
        }
    }
    return RasterizedQrImage(width, height, pixels)
}

private fun java.io.InputStream.readAtMost(maximumByteCount: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maximumByteCount + 1, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (output.size() <= maximumByteCount) {
        val remaining = maximumByteCount + 1 - output.size()
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count <= 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun classifyImportConfigFile(
    displayName: String?,
    mimeType: String?
): ImportConfigFileKind {
    val normalizedName = displayName?.trim()?.lowercase().orEmpty()
    if (normalizedName.endsWith(".aol")) return ImportConfigFileKind.SURFACE_PACKAGE
    if (normalizedName.endsWith(".json")) return ImportConfigFileKind.JSON_CONFIG
    if (normalizedName.endsWith(".zip")) return ImportConfigFileKind.MUTUAL_AID_PACKAGE
    if (
        listOf(".png", ".jpg", ".jpeg", ".webp", ".heic", ".heif", ".svg")
            .any(normalizedName::endsWith)
    ) return ImportConfigFileKind.QR_IMAGE

    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        normalizedMimeType in setOf("application/json", "text/json", "text/plain") ->
            ImportConfigFileKind.JSON_CONFIG
        normalizedMimeType in setOf("application/zip", "application/x-zip-compressed") ->
            ImportConfigFileKind.MUTUAL_AID_PACKAGE
        normalizedMimeType?.startsWith("image/") == true -> ImportConfigFileKind.QR_IMAGE
        else -> ImportConfigFileKind.UNSUPPORTED
    }
}

internal fun decodeQrCodePixels(width: Int, height: Int, pixels: IntArray): String? {
    if (width <= 0 || height <= 0 || pixels.size != width * height) return null
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    return runCatching {
        reader.decodeWithState(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        ).text
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal suspend fun decodeQrCodeImage(
    context: Context,
    uri: Uri,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): String? = withContext(dispatcher) {
    val mimeType = context.contentResolver.getType(uri)?.substringBefore(';')?.lowercase()
    val isSvg = mimeType == "image/svg+xml" ||
        uri.lastPathSegment?.substringAfterLast('/')?.lowercase()?.endsWith(".svg") == true
    if (isSvg) {
        val svgBytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readAtMost(MAX_QR_SVG_BYTES)
        } ?: return@withContext null
        if (svgBytes.size > MAX_QR_SVG_BYTES) return@withContext null
        val raster = rasterizeTrackerQrSvg(svgBytes.toString(Charsets.UTF_8))
            ?: return@withContext null
        return@withContext decodeQrCodePixels(raster.width, raster.height, raster.pixels)
    }
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestEdge = maxOf(info.size.width, info.size.height)
            if (longestEdge > 4096) {
                val scale = 4096.0 / longestEdge.toDouble()
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    } ?: return@withContext null

    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        decodeQrCodePixels(bitmap.width, bitmap.height, pixels)
    } finally {
        bitmap.recycle()
    }
}

private fun resolveImportConfigDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

// Try to bust thru Google Drive's cache to get the latest version of requested
// document.  N.B. If battery saver is on, Google Drive will always use the
// cached version regardless.
class FreshOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).apply {
            // Tells providers (like Drive) to check the server
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.NO_CACHE", true)
            // Ensures we aren't restricted to files already on the device
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
    }
}

class OpenArchiveDir() : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            val downloadsUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Downloads"
            )
            val initialUri = input ?: downloadsUri
            putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks")
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            putExtra("android.content.extra.NO_CACHE", true)
        }
    }
}

private fun restartMediaMtxServer(context: android.content.Context) {
    val appContext = context.applicationContext
    MediaMTXService.requestRestart(appContext)
    CaltopoClient.ShowToast("Streams server restarted. Connected publishers will reconnect if supported.")
    CTDebug("MainMenu", "User requested MediaMTXService restart from menu.")
}

private fun isInstalledFromGooglePlay(context: Context): Boolean {
    return try {
        val installerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installerPackage == "com.android.vending"
    } catch (_: Exception) {
        false
    }
}

private fun openAppUpgradeLocation(context: Context, updateUrl: String) {
    val targetUrl = updateUrl.trim()
    if (targetUrl.isNotEmpty()) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
        } catch (_: ActivityNotFoundException) {
            CaltopoClient.ShowToast("Update location unavailable.")
        }
        return
    }

    if (!isInstalledFromGooglePlay(context)) {
        CaltopoClient.ShowToast("Update location unavailable.")
        return
    }

    val packageId = BuildConfig.APPLICATION_ID
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageId"))
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    streamsViewModel: StreamsViewModel,
    availableLogArchiveDaysProvider: suspend () -> List<LogArchiveDayOption>,
    onEmailLog: suspend (List<String>, Boolean) -> Unit,
    availableArchiveCleanupDirectoriesProvider: suspend () -> List<ArchiveCleanupDirectoryOption>,
    onDeleteArchiveDirectories: suspend (List<String>) -> ArchiveCleanupDeleteResult,
    externalDisplayConnected: Boolean = false,
    externalDisplayContentMode: ExternalDisplayContentMode? = null,
    onSetExternalDisplayContent: ((ExternalDisplayContentMode) -> Unit)? = null,
    openDeveloperToolsOnStart: Boolean = false,
    onDeveloperToolsOpened: () -> Unit = {},
    onArchiveDirPickerStarted: () -> Boolean,
    onArchiveDirPickerFinished: () -> Unit,
    onConfigQrScannerStarted: () -> Boolean,
    onConfigQrScannerFinished: () -> Unit,
    onRequestExit: () -> Unit,
) {
    val tag = "MainScreen"
    val mainHorizontalScrollState = rememberSaveable(saver = ScrollState.Saver) {
        ScrollState(0)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var credentialMenuExpanded by remember { mutableStateOf(false) }
    var pendingProfileRemoval by remember { mutableStateOf<OperationalProfileUiOption?>(null) }
    pendingProfileRemoval?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingProfileRemoval = null },
            title = { Text("Remove mutual-aid profile?") },
            text = { Text("Remove ${profile.credentialLabel} from this device? If selected, the app will disconnect from its map and return to Home. Cached maps and the Home configuration are preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    localViewModel.removeMutualAidProfile(profile.profileId)
                    pendingProfileRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfileRemoval = null }) { Text("Cancel") }
            }
        )
    }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var addRidMapRemoteId by remember { mutableStateOf<String?>(null) }
    var showAboutPrivacyDialog by remember { mutableStateOf(false) }
    var showDebugTagDialog by remember { mutableStateOf(false) }
    var knownDebugTags by remember { mutableStateOf(listOf<String>()) }
    var selectedKnownTags by remember { mutableStateOf(setOf<String>()) }
    var customDebugTagsText by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)) }
    var djiSeiHexDumpEnabled by remember { mutableStateOf(FfmpegBridge.isDjiSeiHexDumpEnabled()) }
    val context =  LocalContext.current
    var bluetoothRidTestVariant by remember {
        mutableStateOf(BluetoothRidTestPrefs.getVariant(context))
    }
    var bluetoothRidPeriodicRestart by remember {
        mutableStateOf(BluetoothRidTestPrefs.isPeriodicRestartEnabled(context))
    }
    var pendingDriveAction by remember { mutableStateOf<DriveSyncAction?>(null) }
    var pendingOrgExport by remember { mutableStateOf(false) }
    var pendingFaaExport by remember { mutableStateOf(false) }
    var driveSyncInProgress by remember { mutableStateOf(false) }
    var driveRestoreEligibilityLoaded by remember { mutableStateOf(false) }
    var showDriveRestoreDialog by remember { mutableStateOf(false) }
    var linkedDriveEmail by remember { mutableStateOf("") }
    var showOrgExportDialog by remember { mutableStateOf(false) }
    var showFaaExportDialog by remember { mutableStateOf(false) }
    var showImportConfigDialog by remember { mutableStateOf(false) }
    var pendingImportToken by rememberSaveable { mutableStateOf("") }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var releaseNoteEntries by remember { mutableStateOf(parseReleaseNotes(null)) }
    var pendingMutualAidImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMutualAidImportPreview by remember { mutableStateOf<MutualAidPackageManager.PackagePreview?>(null) }
    var mutualAidPreviewRequestId by remember { mutableLongStateOf(0L) }
    var showMutualAidImportPreviewDialog by remember { mutableStateOf(false) }
    var importingMutualAidConfig by remember { mutableStateOf(false) }
    var showNotamPanel by remember { mutableStateOf(false) }
    var showLandRestrictionPanel by remember { mutableStateOf(false) }
    var showProximityDebugDialog by remember { mutableStateOf(false) }
    var showLogArchiveDialog by remember { mutableStateOf(false) }
    var showArchiveCleanupDialog by remember { mutableStateOf(false) }
    var showStorageManagement by remember { mutableStateOf(false) }
    var browseFlightFolder by remember { mutableStateOf<String?>(null) }
    var showArchiveDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTestingToolsDialog by remember { mutableStateOf(false) }
    var showResetPersistentStateDialog by remember { mutableStateOf(false) }
    var showResubmitRecentTracksDialog by remember { mutableStateOf(false) }
    var resubmitRecentTracksDaysText by remember { mutableStateOf("2") }
    var resubmitRecentTracksInProgress by remember { mutableStateOf(false) }
    var forceArchiveDirPrompt by remember { mutableStateOf(false) }
    var mqttDisabled by remember { mutableStateOf(!CaltopoClient.GetUsePeersFlag()) }
    var loadingLogArchiveDays by remember { mutableStateOf(false) }
    var sendingLogArchive by remember { mutableStateOf(false) }
    var logArchiveDays by remember { mutableStateOf(emptyList<LogArchiveDayOption>()) }
    var selectedLogArchiveDays by remember { mutableStateOf(emptySet<String>()) }
    var includeTracksInDiagnostics by remember { mutableStateOf(false) }
    var loadingArchiveCleanupDirs by remember { mutableStateOf(false) }
    var deletingArchiveCleanupDirs by remember { mutableStateOf(false) }
    var archiveCleanupDirs by remember { mutableStateOf(emptyList<ArchiveCleanupDirectoryOption>()) }
    var selectedArchiveCleanupDirs by remember { mutableStateOf(defaultSelectedArchiveCleanupDirectories()) }
    var archiveCleanupDeleteMessage by remember { mutableStateOf<String?>(null) }
    var showLocationOverrideDialog by remember { mutableStateOf(false) }
    var locationOverrideText by remember { mutableStateOf("") }
    var showCompliancePanel by remember { mutableStateOf(false) }
    var showSignalLossPanel by remember { mutableStateOf(false) }
    var locationOverrideError by remember { mutableStateOf<String?>(null) }
    var locationOverrideLabel by remember { mutableStateOf(formatLocationOverride(CaltopoMap.GetMyLocationOverride())) }
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val airspaceUiState by AirspaceCenter.uiState.collectAsStateWithLifecycle()
    val landRestrictionUiState by LandRestrictionCenter.uiState.collectAsStateWithLifecycle()
    val overLimitDrones by streamsViewModel.overLimitDrones.collectAsStateWithLifecycle()
    val signalLossFlights by DroneSignalLossAlertCenter.flights.collectAsStateWithLifecycle()
    val bridgeSignal by DroneScoutBridgeMonitor.signal.collectAsStateWithLifecycle()
    var bridgeSignalClockMs by remember {
        mutableLongStateOf(System.nanoTime() / 1_000_000L)
    }
    LaunchedEffect(Unit) {
        while (true) {
            bridgeSignalClockMs = System.nanoTime() / 1_000_000L
            delay(1_000L)
        }
    }
    val bridgeRssi = DroneScoutBridgeMonitor.currentRssi(
        signal = bridgeSignal,
        nowMonotonicMs = bridgeSignalClockMs,
    )
    val bridgeStatusLogGate = remember { DroneScoutBridgeStatusLogGate() }
    LaunchedEffect(
        bridgeRssi,
        bluetoothRidTestVariant.diagnosticsEnabled,
    ) {
        if (!bluetoothRidTestVariant.diagnosticsEnabled) {
            bridgeStatusLogGate.reset()
            return@LaunchedEffect
        }
        bridgeStatusLogGate.transitionMessage(
            surface = "main",
            signal = bridgeSignal,
            nowMonotonicMs = bridgeSignalClockMs,
        )?.let { CTDebug("BridgeStatus", it) }
    }
    val proximityDebugPairs by ProximityAlertCenter.debugPairs.collectAsState()
    val appUpdateAdvisory by AppUpdateAdvisory.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val operationalProfiles = localViewModel.operationalProfiles
    val selectedOperationalProfile = operationalProfiles.firstOrNull {
        it.profileId == localViewModel.selectedOperationalProfileId
    }
    val nextProfileExpiry = operationalProfiles
        .mapNotNull { it.expiresAtEpochMs.takeIf { expiry -> expiry > 0L } }
        .minOrNull()

    LaunchedEffect(openDeveloperToolsOnStart) {
        if (openDeveloperToolsOnStart) {
            showTestingToolsDialog = true
            onDeveloperToolsOpened()
        }
    }

    LaunchedEffect(nextProfileExpiry) {
        CaltopoClient.ScheduleCaltopoProfileExpiry()
        val expiry = nextProfileExpiry ?: return@LaunchedEffect
        delay((expiry - System.currentTimeMillis()).coerceAtLeast(1L) + 50L)
        CaltopoClient.RemoveExpiredCaltopoProfiles(System.currentTimeMillis(), true)
        localViewModel.refreshOperationalProfiles()
    }

    fun refreshDriveState() {
        coroutineScope.launch {
            val email = withContext(Dispatchers.IO) {
                GoogleDriveConfigSync.getLinkedAccountEmail(context)
            }
            val offerRestore = withContext(Dispatchers.IO) {
                shouldOfferDriveRestore(context)
            }
            linkedDriveEmail = email
            showDriveRestoreDialog = offerRestore
            driveRestoreEligibilityLoaded = true
        }
    }

    LaunchedEffect(context) {
        refreshDriveState()
    }

    fun runDriveAction(accountResult: ActivityResult? = null, requestedAction: DriveSyncAction) {
        val account = if (accountResult != null) {
            try {
                GoogleSignIn.getSignedInAccountFromIntent(accountResult.data)
                    .getResult(ApiException::class.java)
            } catch (e: ApiException) {
                CaltopoClient.ShowToast("Google sign-in failed: ${e.statusCode}")
                CTError(tag, "Google sign-in failed.", e)
                null
            }
        } else {
            GoogleDriveConfigSync.getAuthorizedAccount(context)
        }

        if (account == null) {
            pendingDriveAction = requestedAction
            return
        }

        driveSyncInProgress = true
        GoogleDriveConfigSync.performAction(context, account, requestedAction) { success, message ->
            driveSyncInProgress = false
            CaltopoClient.ShowToast(message)
            refreshDriveState()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val requestedAction = pendingDriveAction
            val orgExport = pendingOrgExport
            val faaExport = pendingFaaExport
            pendingDriveAction = null
            pendingOrgExport = false
            pendingFaaExport = false

            val account = if (result.data != null) {
                try {
                    GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        .getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    null
                }
            } else null

            when {
                requestedAction != null && account != null ->
                    runDriveAction(result, requestedAction)
                requestedAction != null ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
                orgExport && account != null -> {
                    showOrgExportDialog = true
                    CaltopoClient.ShowToast("Signed in to Google Drive.")
                    refreshDriveState()
                }
                orgExport ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
                faaExport && account != null -> {
                    showFaaExportDialog = true
                    CaltopoClient.ShowToast("Signed in to Google Drive.")
                    refreshDriveState()
                }
                faaExport ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
            }
        }
    )

    var isPickerOpen by remember { mutableStateOf(false) }
    val loadConfigFileLauncher = rememberLauncherForActivityResult(
        contract = FreshOpenDocument(),
        onResult = { uri ->
            isPickerOpen = false
            if (uri != null) {
                CTDebug(tag, "loadConfigFileLauncher() returned '${uri}'")
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    CTDebug(tag, "loadConfigFileLauncher() secured URI read permission.")
                    if (CaltopoClient.LoadConfigFile(uri)) {
                        localViewModel.onUIEvent(UIEvent.ConfigFileLoaded)
                    } else {
                        localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                    }
                } catch (e: Exception) {
                    CTError(tag, "loadConfigFileLauncher(): read failed: ", e)
                    localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                }
            } else {
                CTDebug(tag, "loadConfigFileLauncher() picker closed w/o selection.")
                if (localViewModel.overlay == OverlayState.RequestConfigFile) {
                    localViewModel.onUIEvent(UIEvent.DismissRequested)
                }
            }
        }
    )

    val importConfigFileLauncher = rememberLauncherForActivityResult(
        contract = FreshOpenDocument(),
        onResult = { uri ->
            mutualAidPreviewRequestId += 1L
            val requestId = mutualAidPreviewRequestId
            if (uri == null) return@rememberLauncherForActivityResult
            val displayName = resolveImportConfigDisplayName(context, uri)
            val mimeType = context.contentResolver.getType(uri)
            when (classifyImportConfigFile(displayName, mimeType)) {
                ImportConfigFileKind.SURFACE_PACKAGE -> {
                    coroutineScope.launch {
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val bytes = context.contentResolver.openInputStream(uri)!!.use { org.ncssar.rid2caltopo.video.surface.SurfacePackage.readBounded(it, org.ncssar.rid2caltopo.video.surface.SurfacePackage.MAX_BYTES + 1) }
                                org.ncssar.rid2caltopo.video.surface.SurfaceStore.install(context, bytes)
                            }
                        }
                        CaltopoClient.ShowToast(outcome.fold({ "Surface prepared and pinned: ${it.id}" }, { "Surface import failed: ${it.message}" }))
                    }
                    return@rememberLauncherForActivityResult
                }
                ImportConfigFileKind.JSON_CONFIG -> {
                    CTDebug(tag, "importConfigFileLauncher(): routing '$displayName' ($mimeType) to JSON loader")
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        if (CaltopoClient.LoadConfigFile(uri)) {
                            localViewModel.onUIEvent(UIEvent.ConfigFileLoaded)
                            CaltopoClient.ShowToast("JSON config imported.")
                        } else {
                            localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                        }
                    } catch (e: Exception) {
                        CTError(tag, "importConfigFileLauncher(): JSON import failed: ", e)
                        localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                    }
                    return@rememberLauncherForActivityResult
                }
                ImportConfigFileKind.MUTUAL_AID_PACKAGE -> {
                    CTDebug(tag, "importConfigFileLauncher(): routing '$displayName' ($mimeType) to MA package importer")
                }
                ImportConfigFileKind.QR_IMAGE -> {
                    CTDebug(tag, "importConfigFileLauncher(): routing '$displayName' ($mimeType) to QR decoder")
                    coroutineScope.launch {
                        val payload = runCatching { decodeQrCodeImage(context, uri) }
                            .onFailure { error ->
                                CTError(
                                    tag,
                                    "importConfigFileLauncher(): QR image decode failed: ",
                                    error as? Exception ?: Exception(error)
                                )
                            }
                            .getOrNull()
                        if (payload.isNullOrBlank()) {
                            CaltopoClient.ShowToast("No readable QR code was found in that image.")
                        } else {
                            pendingImportToken = payload
                            showImportConfigDialog = true
                        }
                    }
                    return@rememberLauncherForActivityResult
                }
                ImportConfigFileKind.UNSUPPORTED -> {
                    CTError(tag, "importConfigFileLauncher(): unsupported file '$displayName' ($mimeType)")
                    CaltopoClient.ShowToast("Choose a QR image, .json config, or .zip MA package.")
                    return@rememberLauncherForActivityResult
                }
            }
            pendingMutualAidImportUri = null
            pendingMutualAidImportPreview = null
            coroutineScope.launch {
                val preview = readMutualAidPackagePreviewOffMain {
                    MutualAidPackageManager.readPackagePreview(context, uri)
                }
                if (requestId != mutualAidPreviewRequestId) return@launch
                if (!preview.first || preview.second == null) {
                    CaltopoClient.ShowToast("Could not read MA package preview.")
                    return@launch
                }
                pendingMutualAidImportUri = uri
                pendingMutualAidImportPreview = preview.second
                showMutualAidImportPreviewDialog = true
            }
        }
    )

    fun startDriveAction(requestedAction: DriveSyncAction) {
        val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
        if (account != null) {
            runDriveAction(requestedAction = requestedAction)
        } else {
            pendingDriveAction = requestedAction
            driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
        }
    }

    fun disconnectDrive() {
        driveSyncInProgress = true
        GoogleDriveConfigSync.disconnect(context) { _, message ->
            driveSyncInProgress = false
            CaltopoClient.ShowToast(message)
            refreshDriveState()
        }
    }

    if (showOrgExportDialog) {
        OrgConfigExportDialog(
            sourceOrgName = CaltopoClient.GetHomeOrgName(),
            onDismiss = { showOrgExportDialog = false },
            onUploadRequested = { callback ->
                val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                if (account != null) {
                    OrgConfigManager.uploadOrgConfig(context, account) { success, message, token ->
                        refreshDriveState()
                        callback(success, message, token)
                    }
                } else {
                    showOrgExportDialog = false
                    pendingOrgExport = true
                    driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                    callback(false, "Signing in to Google Drive…", null)
                }
            }
        )
    }

    if (showFaaExportDialog) {
        FaaConfigExportDialog(
            onDismiss = { showFaaExportDialog = false },
            onUploadRequested = { callback ->
                val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                if (account != null) {
                    FaaConfigManager.uploadFaaConfig(context, account) { success, message, token ->
                        refreshDriveState()
                        callback(success, message, token)
                    }
                } else {
                    showFaaExportDialog = false
                    pendingFaaExport = true
                    driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                    callback(false, "Signing in to Google Drive…", null)
                }
            }
        )
    }

    if (appUpdateAdvisory.updateRequired) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Update available") },
            text = {
                Text(
                    "Check Google Play for Update. If Play only offers Open or Uninstall, " +
                        "return here and continue. Do not uninstall RID2Caltopo."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openAppUpgradeLocation(context, appUpdateAdvisory.updateUrl)
                }) {
                    Text("Check Play")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppUpdateAdvisory.dismissForSession()
                }) {
                    Text("Continue")
                }
            }
        )
    }

    if (showLocationOverrideDialog) {
        AlertDialog(
            onDismissRequest = {
                showLocationOverrideDialog = false
                locationOverrideError = null
            },
            title = { Text("Simulate MyLocation") },
            text = {
                Column {
                    Text("Enter `lat,lng`. Leave empty to return to device GPS. This override lasts only until the app closes.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = locationOverrideText,
                        onValueChange = {
                            locationOverrideText = it
                            locationOverrideError = null
                        },
                        label = { Text("Latitude,Longitude") },
                        singleLine = true,
                        isError = locationOverrideError != null,
                        supportingText = {
                            Text(locationOverrideError ?: "Current: $locationOverrideLabel")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val overrideLocation = parseLocationOverride(locationOverrideText)
                        CaltopoMap.SetMyLocationOverride(overrideLocation)
                        locationOverrideLabel = formatLocationOverride(CaltopoMap.GetMyLocationOverride())
                        AirspaceCenter.requestImmediateRefresh()
                        NotamCenter.requestImmediateRefresh()
                        LandRestrictionCenter.requestImmediateRefresh()
                        CaltopoClient.ShowToast(
                            if (overrideLocation == null) "Returned to device GPS location."
                            else "Using temporary location ${formatLocationOverride(overrideLocation)}"
                        )
                        showLocationOverrideDialog = false
                        locationOverrideError = null
                    } catch (e: IllegalArgumentException) {
                        locationOverrideError = e.message
                    }
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationOverrideDialog = false
                    locationOverrideError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportConfigDialog) {
        ImportConfigDialog(
            initialToken = pendingImportToken,
            onDismiss = { showImportConfigDialog = false },
            onJoin = { token ->
                showImportConfigDialog = false
                OrgConfigManager.joinFromToken(context, token) { success, message ->
                    showConfigImportToast(context, message)
                    if (success) {
                        R2CActivity.getR2CActivity()?.lockOrganizationAccessAndAuthenticate()
                        NotamCenter.requestImmediateRefresh()
                        AirspaceCenter.requestImmediateRefresh()
                    }
                }
            },
            onFaaJoin = { token ->
                showImportConfigDialog = false
                FaaConfigManager.importToken(context, token) { _, message ->
                    showConfigImportToast(context, message)
                    NotamCenter.requestImmediateRefresh()
                }
            },
            onPackageJoin = { token ->
                showImportConfigDialog = false
                org.ncssar.rid2caltopo.data.MutualAidPackageTransferManager.importFromToken(context, token)
            },
            onMutualAidJoin = { token ->
                showImportConfigDialog = false
                MutualAidProfileManager.joinFromToken(context, token) { _, message ->
                    showConfigImportToast(context, message)
                }
            },
            onTrackerJoin = { enrollmentUrl ->
                showImportConfigDialog = false
                coroutineScope.launch {
                    runCatching {
                        TrackerEnrollmentClient.redeem(context, enrollmentUrl).also {
                            applyTrackerEnrollmentAndRefreshNotams(it)
                        }
                    }.onSuccess { result ->
                        R2CActivity.getR2CActivity()?.lockOrganizationAccessAndAuthenticate()
                        result.reauthenticationUrl?.let { url ->
                            R2CActivity.getR2CActivity()?.beginTrackerReauthentication(url)
                        }
                        showConfigImportToast(
                            context,
                            "Organization '${result.organization}' imported; tracker enrollment installed."
                        )
                    }.onFailure { error ->
                        showConfigImportToast(
                            context,
                            error.message ?: "Tracker enrollment failed."
                        )
                    }
                }
            },
            onScannerStarted = onConfigQrScannerStarted,
            onScannerFinished = onConfigQrScannerFinished,
            onPickFile = {
                showImportConfigDialog = false
                importConfigFileLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "application/zip",
                        "application/octet-stream",
                        "image/*"
                    )
                )
            }
        )
    }

    if (showMutualAidImportPreviewDialog) {
        val preview = pendingMutualAidImportPreview
        val expiryText = preview?.expiresAtEpochMs?.takeIf { it > 0L }?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        } ?: "No expiry"
        AlertDialog(
            onDismissRequest = {
                showMutualAidImportPreviewDialog = false
                pendingMutualAidImportUri = null
                pendingMutualAidImportPreview = null
            },
            title = { Text("Import Config") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (preview != null) {
                        Text("Package: ${preview.packageName.ifBlank { "MA Package" }}")
                        if (preview.includesMapAccess) {
                            Text("Source org: ${preview.sourceOrg.ifBlank { "Unknown" }}")
                            Text("Display name: ${preview.displayName.ifBlank { "Mutual Aid" }}")
                            Text("Incident: ${preview.incident.ifBlank { "Unknown" }}")
                            Text("Op period: ${preview.opPeriod.ifBlank { "Unknown" }}")
                            Text("Map: ${preview.targetMapTitle.ifBlank { preview.targetMapId.ifBlank { "Unknown" } }}")
                            Text("Expires: $expiryText")
                        } else {
                            Text("Tiles only — your account and selected map will stay unchanged.")
                        }
                        Text("Offline cache: ${preview.tileCount} tile(s), ${preview.demCount} DEM tile(s), ${preview.aolCount} AOL tile(s)")
                    } else {
                        Text("Could not read MA package preview.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingMutualAidImportUri != null && preview != null,
                    onClick = {
                        val uri = pendingMutualAidImportUri
                        showMutualAidImportPreviewDialog = false
                        pendingMutualAidImportUri = null
                        pendingMutualAidImportPreview = null
                        if (uri != null) {
                            importingMutualAidConfig = true
                            MutualAidPackageManager.importPackageAsync(context, uri) { _, message ->
                                importingMutualAidConfig = false
                                CaltopoClient.ShowToast(message)
                            }
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMutualAidImportPreviewDialog = false
                        pendingMutualAidImportUri = null
                        pendingMutualAidImportPreview = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (importingMutualAidConfig) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing MA Package") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Importing offline tiles and mutual-aid settings. This may take a while for larger packages.")
                }
            },
            confirmButton = { }
        )
    }

    if (showDriveRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showDriveRestoreDialog = false },
            title = { Text("Restore From Google Drive") },
            text = {
                Text("This install looks unconfigured. Restore your saved RID2Caltopo settings from Google Drive before setting the app up again?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDriveRestoreDialog = false
                        startDriveAction(DriveSyncAction.RESTORE)
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDriveRestoreDialog = false }
                ) {
                    Text("Skip")
                }
            }
        )
    }

    if (showNotamPanel) {
        NotamPanel(
            state = notamUiState,
            airspaceState = airspaceUiState,
            onDismiss = { showNotamPanel = false }
        )
    }
    if (showLandRestrictionPanel) {
        LandRestrictionPanel(
            state = landRestrictionUiState,
            onRefresh = LandRestrictionCenter::requestImmediateRefresh,
            onDismiss = { showLandRestrictionPanel = false }
        )
    }
    ComplianceAlertDialog(
        visible = showCompliancePanel,
        overLimitDrones = overLimitDrones,
        onDismiss = { showCompliancePanel = false },
        onToggleMuted = { mappedId, muted ->
            streamsViewModel.setComplianceAlertMuted(mappedId, muted)
        }
    )
    SignalLossAlertDialog(
        visible = showSignalLossPanel,
        flights = signalLossFlights,
        onDismiss = { showSignalLossPanel = false },
        onToggleMuted = { flightKey, muted ->
            DroneSignalLossAlertCenter.setMuted(flightKey, muted)
        }
    )
    if (showProximityDebugDialog) {
        AlertDialog(
            onDismissRequest = { showProximityDebugDialog = false },
            confirmButton = {
                TextButton(onClick = { showProximityDebugDialog = false }) {
                    Text("Close")
                }
            },
            title = { Text("Proximity Pairs") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (proximityDebugPairs.isEmpty()) {
                        Text("No active drone pairs.")
                    } else {
                        proximityDebugPairs.forEach { pair ->
                            Text(
                                "${pair.firstMappedId} <-> ${pair.secondMappedId}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Horizontal ${"%.1f".format(pair.horizontalSeparationFt)} ft  •  " +
                                    "Vertical ${"%.1f".format(pair.verticalSeparationFt)} ft  •  " +
                                    "3D ${"%.1f".format(pair.threeDSeparationFt)} ft" +
                                    if (pair.alerting) "  •  alerting" else ""
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            },
            dismissButton = {}
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Ludicrous Debugging") },
            text = { Text("This will generate a large amount of debugging information. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        // Reset to base level
                        CaltopoClient.SetLoggingLevel(CaltopoClient.DebugLevelError)
                        level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDebugTagDialog) {
        AlertDialog(
            onDismissRequest = { showDebugTagDialog = false },
            title = { Text("Debug Tag Filter") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (knownDebugTags.isNotEmpty()) {
                        Text("Known tags")
                        Spacer(Modifier.height(8.dp))
                        knownDebugTags.forEach { knownTag ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = selectedKnownTags.contains(knownTag),
                                    onCheckedChange = { checked ->
                                        selectedKnownTags = if (checked) {
                                            selectedKnownTags + knownTag
                                        } else {
                                            selectedKnownTags - knownTag
                                        }
                                    }
                                )
                                Text(knownTag, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = customDebugTagsText,
                        onValueChange = { customDebugTagsText = it },
                        label = { Text("Custom tags (CSV)") },
                        placeholder = { Text("StreamTile,StreamPlayer,StreamsViewModel") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val csv = buildTagCsv(selectedKnownTags, customDebugTagsText)
                        parseCsvTags(customDebugTagsText).forEach { tag ->
                            CaltopoClient.RegisterDebugTag(tag)
                        }
                        CaltopoClient.SetDebugTagFilter(csv)
                        val msg = if (csv.isBlank()) {
                            "Debug tag filter disabled."
                        } else {
                            "Debug tag filter: $csv"
                        }
                        CaltopoClient.ShowToast(msg)
                        CTDebug(tag, msg)
                        showDebugTagDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        CaltopoClient.ClearDebugTagFilter()
                        selectedKnownTags = emptySet()
                        customDebugTagsText = ""
                        CaltopoClient.ShowToast("Debug tag filter cleared.")
                        showDebugTagDialog = false
                    }
                ) { Text("Clear") }
            }
        )
    }
    LaunchedEffect(localViewModel.overlay) {
        if (localViewModel.overlay == OverlayState.RequestConfigFile && !isPickerOpen) {
            CTDebug(tag, "LaunchedEffect(): requesting config file...")
            isPickerOpen = true
            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }


    // Launcher for selecting an archive directory
    var archiveDirPickerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingArchiveDirPromptMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingArchiveDirInitialUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingArchiveDirPermissionMissing by rememberSaveable { mutableStateOf(false) }
    var archiveDirPromptSuppressed by rememberSaveable { mutableStateOf(false) }
    var archiveDirSelectionFailure by rememberSaveable { mutableStateOf<String?>(null) }
    val queryArchiveDirLauncher = rememberLauncherForActivityResult(
        contract = OpenArchiveDir(),
        onResult = { uri ->
            val result = persistArchiveDirSelection(
                selection = uri,
                persistPermission = { selectedUri ->
                    CTDebug(tag, "queryArchiveDirLauncher() returned: '$selectedUri'")
                    context.contentResolver.takePersistableUriPermission(
                        selectedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                },
                activateSelection = CaltopoClient::SetArchiveUri,
                selectionIsUsable = { selectedUri ->
                    CaltopoClient.GetArchiveUri()?.toString() == selectedUri.toString() &&
                        CaltopoClient.HasArchiveDirForCurrentSession()
                }
            )
            when (result) {
                ArchiveDirSelectionResult.Selected -> {
                    archiveDirPromptSuppressed = false
                    archiveDirSelectionFailure = null
                }
                ArchiveDirSelectionResult.Cancelled -> {
                    archiveDirPromptSuppressed = true
                    archiveDirSelectionFailure =
                        "No folder was selected. You can try again or start RID2Caltopo now using temporary storage."
                    CTWarn(tag, "Archive directory picker was cancelled; automatic relaunch suppressed.")
                }
                is ArchiveDirSelectionResult.Failed -> {
                    archiveDirPromptSuppressed = true
                    archiveDirSelectionFailure =
                        "RID2Caltopo could not keep access to that folder. ${result.detail}"
                    CTError(tag, "Archive directory selection failed: ${result.detail}")
                }
            }
            // Keep the picker trusted and marked open until its result is fully processed.
            archiveDirPickerOpen = false
            onArchiveDirPickerFinished()
        }
    )
    LaunchedEffect(
        showDriveRestoreDialog,
        driveSyncInProgress,
        forceArchiveDirPrompt,
        driveRestoreEligibilityLoaded,
        archiveDirPickerOpen,
        archiveDirPromptSuppressed
    ) {
        val archiveUriMissing = withContext(Dispatchers.IO) {
            null == CaltopoClient.GetArchiveUri()
        }
        val sessionArchiveDirAvailable = withContext(Dispatchers.IO) {
            CaltopoClient.HasArchiveDirForCurrentSession()
        }
        val shouldPromptArchiveDir = shouldLaunchArchiveDirPicker(
            archiveUriMissing = archiveUriMissing,
            sessionArchiveDirAvailable = sessionArchiveDirAvailable,
            forceArchiveDirPrompt = forceArchiveDirPrompt,
            driveRestoreEligibilityLoaded = driveRestoreEligibilityLoaded,
            showDriveRestoreDialog = showDriveRestoreDialog,
            driveSyncInProgress = driveSyncInProgress,
            archiveDirPickerOpen = archiveDirPickerOpen,
            archiveDirPromptSuppressed = archiveDirPromptSuppressed
        )
        if (shouldPromptArchiveDir) {
            val initialUri = withContext(Dispatchers.IO) {
                CaltopoClient.GetArchiveUriSelectionHint()
            }
            val permissionMissing = CaltopoClient.WasArchiveUriPermissionMissing()
            forceArchiveDirPrompt = false
            pendingArchiveDirInitialUri = initialUri?.toString()
            pendingArchiveDirPermissionMissing = permissionMissing
            pendingArchiveDirPromptMessage = archiveDirPromptMessage(
                permissionMissing,
                archiveDirDisplayPath(initialUri?.toString())
            )
            CTDebug(tag, "LaunchedEffect() prepared archiveDir prompt initialUri='${initialUri ?: "<none>"}'")
        }
    }
    pendingArchiveDirPromptMessage?.let { message ->
        val previousArchivePath = archiveDirDisplayPath(pendingArchiveDirInitialUri)
        val promptActions = archiveDirPromptActions(
            pendingArchiveDirPermissionMissing,
            previousArchivePath
        )
        AlertDialog(
            // Archive setup can only continue through a persistent folder grant.
            onDismissRequest = {},
            title = {
                Text(
                    if (pendingArchiveDirPermissionMissing) {
                        "Archive access required again"
                    } else {
                        "Archive folder"
                    }
                )
            },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val initialUri = pendingArchiveDirInitialUri?.let(Uri::parse)
                        pendingArchiveDirPromptMessage = null
                        pendingArchiveDirInitialUri = null
                        pendingArchiveDirPermissionMissing = false
                        archiveDirPickerOpen = true
                        CTDebug(tag, "Archive directory prompt confirmed initialUri='${initialUri ?: "<none>"}'")
                        if (onArchiveDirPickerStarted()) {
                            try {
                                queryArchiveDirLauncher.launch(initialUri)
                            } catch (error: Exception) {
                                onArchiveDirPickerFinished()
                                archiveDirPickerOpen = false
                                archiveDirPromptSuppressed = true
                                archiveDirSelectionFailure =
                                    "RID2Caltopo could not open Android's folder picker. Try again or continue using temporary storage."
                                CTError(tag, "Unable to open archive directory picker:", error)
                            }
                        } else {
                            archiveDirPickerOpen = false
                            archiveDirPromptSuppressed = true
                            archiveDirSelectionFailure =
                                "Unlock protected access, then try again. You can also continue using temporary storage."
                        }
                    }
                ) {
                    Text(promptActions.continueLabel)
                }
            },
            dismissButton = {
                promptActions.chooseDifferentLabel?.let { chooseDifferentLabel ->
                    TextButton(
                        onClick = {
                            if (!pendingArchiveDirPermissionMissing) {
                                pendingArchiveDirPromptMessage = null
                                pendingArchiveDirInitialUri = null
                                pendingArchiveDirPermissionMissing = false
                                if (previousArchivePath != null) {
                                    archiveDirPromptSuppressed = false
                                    return@TextButton
                                }
                                archiveDirPromptSuppressed = true
                                if (CaltopoClient.UseTemporaryArchiveDirForSession()) {
                                    CaltopoClient.ShowToast(
                                        "Using temporary storage. Choose Archive Storage from the menu when ready."
                                    )
                                } else {
                                    CaltopoClient.ShowToast(
                                        "Archive storage is unavailable. RID2Caltopo will continue with limited file storage."
                                    )
                                }
                                return@TextButton
                            }
                            pendingArchiveDirPromptMessage = null
                            pendingArchiveDirInitialUri = null
                            pendingArchiveDirPermissionMissing = false
                            archiveDirPickerOpen = true
                            CTDebug(tag, "Archive directory prompt requested a different folder")
                            if (onArchiveDirPickerStarted()) {
                                try {
                                    queryArchiveDirLauncher.launch(null)
                                } catch (error: Exception) {
                                    onArchiveDirPickerFinished()
                                    archiveDirPickerOpen = false
                                    archiveDirPromptSuppressed = true
                                    archiveDirSelectionFailure =
                                        "RID2Caltopo could not open Android's folder picker. Try again or continue using temporary storage."
                                    CTError(tag, "Unable to open archive directory picker:", error)
                                }
                            } else {
                                archiveDirPickerOpen = false
                                archiveDirPromptSuppressed = true
                                archiveDirSelectionFailure =
                                    "Unlock protected access, then try again. You can also continue using temporary storage."
                            }
                        }
                    ) {
                        Text(chooseDifferentLabel)
                    }
                }
            }
        )
    }
    archiveDirSelectionFailure?.let { failureMessage ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Archive setup not completed") },
            text = { Text(failureMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        archiveDirSelectionFailure = null
                        archiveDirPromptSuppressed = false
                        forceArchiveDirPrompt = true
                    }
                ) { Text("Try again") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        archiveDirSelectionFailure = null
                        archiveDirPromptSuppressed = true
                        if (CaltopoClient.UseTemporaryArchiveDirForSession()) {
                            CaltopoClient.ShowToast(
                                "Using temporary storage. Choose Archive Storage from the menu when ready."
                            )
                        } else {
                            CaltopoClient.ShowToast(
                                "Archive storage is unavailable. RID2Caltopo will continue with limited file storage."
                            )
                        }
                    }
                ) { Text("Continue temporarily") }
            }
        )
    }
    
    // 2. Build the unified list of display items.
    val screenItems: List<MainScreenItem> = listOf(MainScreenItem.LocalView(localViewModel))

    val openDebugTagDialog = {
        val currentFilterTags = parseCsvTags(CaltopoClient.GetDebugTagFilterCsv())
        val knownTags = (CaltopoClient.GetRegisteredDebugTags() + REQUIRED_MAP_CACHE_TAGS)
            .distinct()
            .sorted()
        val knownSet = knownTags.toSet()
        knownDebugTags = knownTags
        selectedKnownTags = currentFilterTags.filter { knownSet.contains(it) }.toSet()
        customDebugTagsText = currentFilterTags.filter { !knownSet.contains(it) }
            .joinToString(",")
        showDebugTagDialog = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.pageNavigationSwipe(toLiveView = true, enabled = !menuExpanded && !credentialMenuExpanded && !showNotamPanel && !showLandRestrictionPanel) { localViewModel.showStreams() }.pointerInput(localViewModel) {
                    detectTapGestures(
                        onDoubleTap = {
                            localViewModel.showStreams()
                        }
                    )
                },
                title = {
                    Box {
                        TextButton(onClick = { credentialMenuExpanded = true }) {
                            Column {
                                Text("RID-2-Caltopo", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Teams: ${selectedOperationalProfile?.credentialLabel ?: "None"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (
                                        selectedOperationalProfile?.expiresAtEpochMs?.let {
                                            it - System.currentTimeMillis() in 1..3_600_000L
                                        } == true
                                    ) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Teams credentials")
                        }
                        DropdownMenu(
                            expanded = credentialMenuExpanded,
                            onDismissRequest = { credentialMenuExpanded = false }
                        ) {
                            operationalProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.credentialLabel)
                                            Text(
                                                buildString {
                                                    append(profile.description)
                                                    if (profile.expiresAtEpochMs > 0L) {
                                                        append(" • expires ")
                                                        append(formatOperationalProfileExpiry(profile.expiresAtEpochMs))
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (profile.profileId == localViewModel.selectedOperationalProfileId) {
                                                Icon(Icons.Default.Check, contentDescription = "Selected")
                                            }
                                            if (profile.isMutualAid) {
                                                TextButton(onClick = {
                                                    credentialMenuExpanded = false
                                                    pendingProfileRemoval = profile
                                                }) { Text("Remove") }
                                            }
                                        }
                                    },
                                    onClick = {
                                        credentialMenuExpanded = false
                                        localViewModel.selectOperationalProfile(profile.profileId)
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }
                    val allSignalLossMuted = signalLossFlights.isNotEmpty() && signalLossFlights.all { it.muted }
                    ComplianceAlertBell(
                        overLimitDrones = overLimitDrones,
                        allOverLimitMuted = allOverLimitMuted,
                        onClick = { showCompliancePanel = true }
                    )
                    SignalLossAlertButton(
                        flights = signalLossFlights,
                        allMuted = allSignalLossMuted,
                        onClick = { showSignalLossPanel = true }
                    )
                    ResumeProximityAlertButton()
                    MainBridgeSignalIndicator(rssi = bridgeRssi)
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Live View")}, onClick = {
                            localViewModel.showStreams()
                            CaltopoClient.CTEvent(tag,"Stream Service Activated", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Send diagnostics to developer...") }, onClick = {
                            showLogArchiveDialog = true
                            includeTracksInDiagnostics = false
                            loadingLogArchiveDays = true
                            logArchiveDays = emptyList()
                            selectedLogArchiveDays = emptySet()
                            menuExpanded = false
                            coroutineScope.launch {
                                val loadedDays = availableLogArchiveDaysProvider()
                                logArchiveDays = loadedDays
                                selectedLogArchiveDays = loadedDays
                                    .filter { it.isToday }
                                    .mapTo(linkedSetOf()) { it.directoryName }
                                    .ifEmpty { loadedDays.firstOrNull()?.let { linkedSetOf(it.directoryName) } ?: linkedSetOf() }
                                loadingLogArchiveDays = false
                            }
                        })
                        if (externalDisplayConnected && onSetExternalDisplayContent != null && externalDisplayContentMode != null) {
                            DropdownMenuItem(
                                text = { Text("External: Streams View") },
                                onClick = {
                                    onSetExternalDisplayContent(ExternalDisplayContentMode.StreamsGrid)
                                    menuExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(text = { Text("Status")}, onClick = {
                            localViewModel.showScanner()
                            CaltopoClient.CTEvent(tag,"ScannersDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Release Notes") }, onClick = {
                            releaseNoteEntries = loadReleaseNotes(context)
                            showReleaseNotesDialog = true
                            CaltopoClient.CTEvent(tag,"ReleaseNotesDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(
                            text = { Text("Import Config") },
                            onClick = {
                                menuExpanded = false
                                pendingImportToken = ""
                                showImportConfigDialog = true
                            }
                        )
                        DropdownMenuItem(text = { Text("Manage Storage...") }, onClick = {
                            showStorageManagement = true
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Archive Storage...") }, onClick = {
                            archiveDirPromptSuppressed = false
                            archiveDirSelectionFailure = null
                            forceArchiveDirPrompt = true
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            localViewModel.showSettings()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("About & Privacy") }, onClick = {
                            showAboutPrivacyDialog = true
                            CaltopoClient.CTEvent(tag,"AboutPrivacyDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Quit") }, onClick = {
                            menuExpanded = false
                            onRequestExit()
                        })
                    }
                }
            )
        }
    ) { paddingValues ->
        // 3. Use a single LazyColumn with the robust `items` DSL and a stable key.
        Box(
            modifier = Modifier.horizontalScroll(mainHorizontalScrollState)
        ) {
            LazyColumn(modifier = Modifier.padding(paddingValues)) {
                item(key = "notam_chip") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NotamStatusChip(
                            state = notamUiState,
                            airspaceState = airspaceUiState,
                            onClick = { showNotamPanel = true }
                        )
                        LandRestrictionStatusChip(
                            state = landRestrictionUiState,
                            onClick = { showLandRestrictionPanel = true }
                        )
                        OrganizationUserLabel()
                    }
                }
                itemsIndexed(
                    items = screenItems,
                    key = { _, item ->
                        // This key is now guaranteed to be unique and stable
                        when (item) {
                            is MainScreenItem.LocalView -> "local_view" // A constant key for the single local view
                        }
                    }
                ) { _, item ->
                    // 4. Use a `when` statement to render the correct composable.
                    when (item) {
                        is MainScreenItem.LocalView -> {
                            val localDrones by item.viewModel.drones.collectAsState()
                            val appUptime by item.viewModel.appUpTime.collectAsState()
                            val hostname by item.viewModel.hostname.collectAsState()

                            R2CView(
                                hostName = hostname,
                                drones = localDrones,
                                appUptime = appUptime,
                                viewModel = item.viewModel,
                                onConfirmDrone = { drone ->
                                    if (drone.mappedId == drone.remoteId) {
                                        addRidMapRemoteId = drone.remoteId
                                    } else {
                                        item.viewModel.requestDroneConfirmation(drone)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAboutPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showAboutPrivacyDialog = false },
            title = { Text("About & Privacy") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("RID2Caltopo", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${BuildConfig.BUILD_VERSION} (${BuildConfig.VERSION_CODE})")
                    Text(
                        "Incident-support software for receiving Remote ID observations, mapping aircraft, publishing operator-authorized tracks, and analyzing live drone video.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Text("RID2Caltopo contains no advertising, does not track people across apps or websites, and does not sell personal data.")
                    Text("The Android app uses Firebase Analytics for operational app events and configuration state. Some events include map or configuration details and mapped Remote ID identifiers; advertising-identifier collection is disabled.")
                    Text("Remote ID observations, track archives, and diagnostic logs remain on this device unless you enable CalTopo publishing, load a configuration that enables eligible team-track upload or tracker peer coordination, enable configuration backup, or explicitly share a file.")
                    Text("CalTopo credentials are stored in the app's private configuration and are not intentionally written to diagnostic logs or shared log bundles.")

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Permissions and network use", style = MaterialTheme.typography.titleMedium)
                    Text("Bluetooth receives nearby ASTM Remote ID broadcasts.")
                    Text("Location places the operator relative to aircraft on the map.")
                    Text("Nearby Wi-Fi and local-network access receive controller video and optional external Remote ID observations.")
                    Text("When enabled by the operator, CalTopo receives aircraft positions and telemetry for the selected map. Configured tracker peer coordination receives the app-install zone identifier, device zone name, operator position, confirmed drone identity, and aircraft sightings needed to coordinate ownership. Nearby NOTAM monitoring sends the operator location and selected radius to the configured tracker, which queries FAA without exposing FAA credentials to this app.")
                    Text("When protected-land checks are enabled, public NPS, USFWS, USFS, and Colorado Parks and Wildlife services receive a small geographic search area around the operator location. Returned boundaries are cached locally; these requests do not include aircraft tracks or an operator identity.")

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Logs and deletion", style = MaterialTheme.typography.titleMedium)
                    Text("You choose which log days to package. Diagnostic log messages omit location details. JSON track files contain aircraft positions and are excluded unless you explicitly include them.")
                    Text("Nothing is transmitted until you choose a destination in the Android share panel. Local logs and track archives can be removed in the app. Android or Google Drive configuration backups remain until removed from the corresponding backup service; CalTopo and the configured tracker control retention of data sent to them.")

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Additional information", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://rid2caltopo.com/"))
                                )
                            } catch (_: ActivityNotFoundException) {
                                CaltopoClient.ShowToast("Unable to open rid2caltopo.com.")
                            }
                        }
                    ) {
                        Text("RID2Caltopo website")
                    }
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:help@uas4sar.com"))
                                )
                            } catch (_: ActivityNotFoundException) {
                                CaltopoClient.ShowToast("No email app is available.")
                            }
                        }
                    ) {
                        Text("help@uas4sar.com")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutPrivacyDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showLogArchiveDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!loadingLogArchiveDays && !sendingLogArchive) {
                    showLogArchiveDialog = false
                }
            },
            title = { Text("Select Log Days") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Each selected day includes its text logs and any captured Android ANR traces. Track files are optional and off by default because they contain aircraft locations.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (loadingLogArchiveDays) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Scanning archived log folders…")
                        }
                    } else if (sendingLogArchive) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Preparing log archive…")
                        }
                    } else if (logArchiveDays.isEmpty()) {
                        Text("No archived log folders with text logs were found.")
                    } else {
                        logArchiveDays.forEach { option ->
                            val checked = selectedLogArchiveDays.contains(option.directoryName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedLogArchiveDays = selectedLogArchiveDays.toMutableSet().apply {
                                            if (isChecked) add(option.directoryName) else remove(option.directoryName)
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(option.directoryName)
                                    Text(
                                        text = "${option.logFileCount} log file${if (option.logFileCount == 1) "" else "s"}${if (option.isToday) " • today" else ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = includeTracksInDiagnostics,
                                onCheckedChange = { includeTracksInDiagnostics = it },
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("Include JSON track files")
                                Text(
                                    "Off by default. Track files contain aircraft positions.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sendingLogArchive = true
                        coroutineScope.launch {
                            onEmailLog(
                                logArchiveDays.filter { selectedLogArchiveDays.contains(it.directoryName) }.map { it.directoryName },
                                includeTracksInDiagnostics,
                            )
                            CaltopoClient.CTEvent(tag,"LogEmailed", null)
                            sendingLogArchive = false
                            showLogArchiveDialog = false
                        }
                    },
                    enabled = !loadingLogArchiveDays && !sendingLogArchive && selectedLogArchiveDays.isNotEmpty() && logArchiveDays.isNotEmpty()
                ) {
                    Text("Package Logs")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogArchiveDialog = false },
                    enabled = !loadingLogArchiveDays && !sendingLogArchive
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReleaseNotesDialog) {
        AlertDialog(
            onDismissRequest = { showReleaseNotesDialog = false },
            title = { Text("Release Notes") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    releaseNoteEntries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                        val title = listOf(entry.title, entry.date)
                            .filter { it.isNotBlank() }
                            .joinToString(" \u00b7 ")
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        entry.changeLines.forEach { change ->
                            Row(
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "\u2022",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    text = change,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        val metadata = entry.hash.takeIf { it.isNotBlank() }?.let { "commit $it" }.orEmpty()
                        if (metadata.isNotBlank()) {
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReleaseNotesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    val flightPressure by org.ncssar.rid2caltopo.app.FlightStorage.pressure.collectAsStateWithLifecycle()
    flightPressure?.let { message ->
        AlertDialog(
            onDismissRequest = { org.ncssar.rid2caltopo.app.FlightStorage.pressure.value = null },
            title = { Text("Flight Storage") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    org.ncssar.rid2caltopo.app.FlightStorage.pressure.value = null
                    if (message.startsWith("Cleanup")) {
                        showStorageManagement = false
                        showArchiveCleanupDialog = true
                        loadingArchiveCleanupDirs = true
                        selectedArchiveCleanupDirs = emptySet()
                        coroutineScope.launch {
                            archiveCleanupDirs = availableArchiveCleanupDirectoriesProvider()
                            loadingArchiveCleanupDirs = false
                        }
                    } else { showStorageManagement = true }
                }) { Text(if (message.startsWith("Cleanup")) "Increase Allowance" else "Manage Storage") }
            },
            dismissButton = { TextButton(onClick = { org.ncssar.rid2caltopo.app.FlightStorage.pressure.value = null }) { Text("Later") } }
        )
    }

    if (showStorageManagement) {
        StorageManagementDialog(onDismiss = { showStorageManagement = false }, onFlight = {
            showStorageManagement = false
            showArchiveCleanupDialog = true
            loadingArchiveCleanupDirs = true
            deletingArchiveCleanupDirs = false
            archiveCleanupDirs = emptyList()
            selectedArchiveCleanupDirs = defaultSelectedArchiveCleanupDirectories()
            archiveCleanupDeleteMessage = null
            menuExpanded = false
            coroutineScope.launch {
                archiveCleanupDirs = availableArchiveCleanupDirectoriesProvider()
                loadingArchiveCleanupDirs = false
            }
        })
    }
    browseFlightFolder?.let { folder -> FlightDirectoryBrowser(folder) { browseFlightFolder = null } }

    if (showArchiveCleanupDialog) {
        val deleteEnabled = canDeleteArchiveCleanupSelection(archiveCleanupDirs, selectedArchiveCleanupDirs)
        AlertDialog(
            onDismissRequest = {
                if (!loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs) {
                    showArchiveCleanupDialog = false
                    showArchiveDeleteConfirmDialog = false
                }
            },
            title = { Text("Delete Flight Storage") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    FlightStorageLimits()
                    archiveCleanupDeleteMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (loadingArchiveCleanupDirs) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Scanning archive folders...")
                        }
                    } else if (deletingArchiveCleanupDirs) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Deleting selected archive folders...")
                        }
                    } else if (archiveCleanupDirs.isEmpty()) {
                        Text("No dated archive folders were found.")
                    } else {
                        archiveCleanupDirs.forEach { option ->
                            val checked = selectedArchiveCleanupDirs.contains(option.directoryName)
                            val enabled = !option.isToday
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = enabled,
                                    onCheckedChange = { isChecked ->
                                        if (enabled) {
                                            selectedArchiveCleanupDirs = selectedArchiveCleanupDirs.toMutableSet().apply {
                                                if (isChecked) add(option.directoryName) else remove(option.directoryName)
                                            }
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    TextButton(onClick = {
                                        browseFlightFolder = option.directoryName
                                    }) { Text(option.directoryName) }
                                    Text(
                                        text = "Age ${option.ageLabel} • ${option.sizeLabel}${if (option.isToday) " • protected" else ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${option.logFileCount} log${if (option.logFileCount == 1) "" else "s"} • ${option.kmzCount} KMZ • ${option.videoCount} video${if (option.videoCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showArchiveDeleteConfirmDialog = true },
                    enabled = !loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs && deleteEnabled
                ) {
                    Text("Delete (${formatArchiveSize(archiveCleanupDirs.filter { !it.isToday && it.directoryName in selectedArchiveCleanupDirs }.sumOf { it.totalBytes })})")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showArchiveCleanupDialog = false
                        showArchiveDeleteConfirmDialog = false
                    },
                    enabled = !loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showArchiveDeleteConfirmDialog) {
        val selectedOptions = archiveCleanupDirs
            .filter { !it.isToday && selectedArchiveCleanupDirs.contains(it.directoryName) }
        val selectedTotalBytes = selectedOptions.sumOf { it.totalBytes }
        val selectedSizeLabel = selectedOptions.firstOrNull()?.let {
            formatArchiveSize(selectedTotalBytes)
        } ?: "0 B"
        AlertDialog(
            onDismissRequest = {
                if (!deletingArchiveCleanupDirs) {
                    showArchiveDeleteConfirmDialog = false
                }
            },
            title = { Text("Confirm Archive Deletion") },
            text = {
                Column {
                    Text(
                        "Permanently delete ${selectedOptions.size} archive folder${if (selectedOptions.size == 1) "" else "s"} totaling $selectedSizeLabel?"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    selectedOptions.forEach { option ->
                        Text(
                            text = option.directoryName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val namesToDelete = selectedOptions.map { it.directoryName }
                        showArchiveDeleteConfirmDialog = false
                        deletingArchiveCleanupDirs = true
                        archiveCleanupDeleteMessage = null
                        coroutineScope.launch {
                            val result = onDeleteArchiveDirectories(namesToDelete)
                            archiveCleanupDirs = availableArchiveCleanupDirectoriesProvider()
                            selectedArchiveCleanupDirs = defaultSelectedArchiveCleanupDirectories()
                            archiveCleanupDeleteMessage = buildString {
                                append("Deleted ${result.deletedCount} archive folder")
                                append(if (result.deletedCount == 1) "." else "s.")
                                if (result.failedDirectoryNames.isNotEmpty()) {
                                    append(" Failed: ")
                                    append(result.failedDirectoryNames.joinToString(", "))
                                }
                            }
                            CaltopoClient.ShowToast(archiveCleanupDeleteMessage ?: "Archive cleanup complete.")
                            CaltopoClient.CTEvent(tag, "ArchiveFoldersDeleted", null)
                            deletingArchiveCleanupDirs = false
                        }
                    },
                    enabled = !deletingArchiveCleanupDirs && selectedOptions.isNotEmpty()
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showArchiveDeleteConfirmDialog = false },
                    enabled = !deletingArchiveCleanupDirs
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResubmitRecentTracksDialog) {
        val days = resubmitRecentTracksDaysText.trim().toIntOrNull()
        val daysInvalid = days == null || days < 1
        AlertDialog(
            onDismissRequest = {
                if (!resubmitRecentTracksInProgress) showResubmitRecentTracksDialog = false
            },
            title = { Text("Resubmit Recent Tracks") },
            text = {
                Column {
                    OutlinedTextField(
                        value = resubmitRecentTracksDaysText,
                        onValueChange = { resubmitRecentTracksDaysText = it },
                        label = { Text("Days") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = daysInvalid,
                        enabled = !resubmitRecentTracksInProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (daysInvalid) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Enter 1 or more days.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Only matching org/team-drone tracks are uploaded.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val requestedDays = days ?: return@TextButton
                        resubmitRecentTracksInProgress = true
                        coroutineScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    WaypointTrack.ResubmitRecentTrackStatsToTracker(requestedDays)
                                }
                                CaltopoClient.ShowToast(result.summary())
                                showResubmitRecentTracksDialog = false
                            } catch (e: Exception) {
                                CTError(tag, "Resubmit recent tracks failed.", e)
                                CaltopoClient.ShowToast("Resubmit recent tracks failed.")
                            } finally {
                                resubmitRecentTracksInProgress = false
                            }
                        }
                    },
                    enabled = !resubmitRecentTracksInProgress && !daysInvalid
                ) {
                    Text(if (resubmitRecentTracksInProgress) "Resubmitting..." else "Resubmit")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResubmitRecentTracksDialog = false },
                    enabled = !resubmitRecentTracksInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTestingToolsDialog) {
        AlertDialog(
            onDismissRequest = { showTestingToolsDialog = false },
            title = { Text("Developer Tools") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            showProximityDebugDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Proximity Pairs")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            CaltopoClient.BumpLoggingLevel()
                            level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                            if (CaltopoClient.DebugLevel == CaltopoClient.DebugLevelInfo) {
                                showTestingToolsDialog = false
                                showConfirmDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("LogLevel:$level")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            djiSeiHexDumpEnabled = !djiSeiHexDumpEnabled
                            FfmpegBridge.setDjiSeiHexDumpEnabled(djiSeiHexDumpEnabled)
                            CTDebug(
                                "DjiSeiHex",
                                "DJI SEI hex capture ${if (djiSeiHexDumpEnabled) "enabled" else "disabled"}"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("DJI SEI Hex: ${if (djiSeiHexDumpEnabled) "On" else "Off"}")
                    }
                    Text(
                        "Research capture only. Logs every type-245 payload and can fill the diagnostic log quickly.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val variants = BluetoothRidTestPrefs.ScanVariant.values()
                                bluetoothRidTestVariant = variants[
                                    (bluetoothRidTestVariant.ordinal + 1) % variants.size
                                ]
                                BluetoothRidTestPrefs.setVariant(context, bluetoothRidTestVariant)
                                if (!bluetoothRidTestVariant.diagnosticsEnabled) {
                                    bluetoothRidPeriodicRestart = false
                                    BluetoothRidTestPrefs.setPeriodicRestartEnabled(context, false)
                                }
                                ScanningService.requestBluetoothRidTestRefresh(context)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Bluetooth RID Test: ${bluetoothRidTestVariant.label}")
                        }
                        Button(
                            onClick = {
                                bluetoothRidPeriodicRestart = !bluetoothRidPeriodicRestart
                                BluetoothRidTestPrefs.setPeriodicRestartEnabled(
                                    context,
                                    bluetoothRidPeriodicRestart
                                )
                                ScanningService.requestBluetoothRidTestRefresh(context)
                            },
                            enabled = bluetoothRidTestVariant.diagnosticsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "2-minute scan restart: " +
                                    if (bluetoothRidPeriodicRestart) "On" else "Off"
                            )
                        }
                        Text(
                            "Debug builds only. Each mode logs Bluetooth callback, message-type, PHY, and ingest counters every 5 seconds. Off restores the release scan.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load Config File")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            openDebugTagDialog()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val active = if (CaltopoClient.IsDebugTagFilterEnabled()) "on" else "off"
                        Text("Debug Tags ($active)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            resubmitRecentTracksDaysText = "2"
                            showResubmitRecentTracksDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Resubmit Recent Tracks To Tracker")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            if (CaltopoClient.GetHomeOrgName().isBlank()) {
                                CaltopoClient.ShowToast("Set the organization designator in Settings before exporting organization config.")
                            } else if (GoogleDriveConfigSync.getAuthorizedAccount(context) != null) {
                                showOrgExportDialog = true
                            } else {
                                pendingOrgExport = true
                                driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Org Config")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                            showTestingToolsDialog = false
                            if (account != null) {
                                showFaaExportDialog = true
                            } else {
                                pendingFaaExport = true
                                driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Publish FAA Config")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            locationOverrideText = CaltopoMap.GetMyLocationOverride()?.let {
                                "%.6f, %.6f".format(it.latitude, it.longitude)
                            }.orEmpty()
                            locationOverrideLabel = formatLocationOverride(CaltopoMap.GetMyLocationOverride())
                            locationOverrideError = null
                            showLocationOverrideDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate MyLocation...")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showResetPersistentStateDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Persisted App State")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            restartMediaMtxServer(context)
                            CaltopoClient.CTEvent(tag,"RestartMediaMtxServer", null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restart MediaMtx Server")
                    }
                    if (linkedDriveEmail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                showTestingToolsDialog = false
                                disconnectDrive()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect Google Drive")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Peer coordination runs automatically whenever a map is " +
                            "connected. Disable only for isolated testing — dual-write to CalTopo " +
                            "will occur if multiple instances are running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Disable Peer Coordination", modifier = Modifier.weight(1f))
                        Switch(
                            checked = mqttDisabled,
                            onCheckedChange = { disabled ->
                                mqttDisabled = disabled
                                CaltopoClient.SetUsePeers(!disabled)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTestingToolsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showResetPersistentStateDialog) {
        AlertDialog(
            onDismissRequest = { showResetPersistentStateDialog = false },
            title = { Text("Reset Persisted App State?") },
            text = {
                Text(
                    "This rewrites app_config.pb from a fresh default ClientClassState and updates " +
                        "future backup/restore from that clean baseline. It clears persistent rid " +
                        "mappings, credentials, profiles, archive path, loaded-config history, and " +
                        "other saved settings. Transient runtime activity may repopulate state after reset."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetPersistedStateAndRequestRequiredSetup(
                            requestArchiveSelection = { forceArchiveDirPrompt = true }
                        )
                        CaltopoClient.ShowToast(
                            "Persisted app state reset. Select an archive directory to continue setup."
                        )
                        showResetPersistentStateDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPersistentStateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    addRidMapRemoteId?.let { remoteId ->
        RidMappingAdminDialog(
            initialRemoteId = remoteId,
            onDismiss = { addRidMapRemoteId = null },
            onSaved = { savedRemoteId ->
                addRidMapRemoteId = null
                localViewModel.onRidMappingSaved(savedRemoteId)
            }
        )
    }
}

@Composable
private fun MainBridgeSignalIndicator(rssi: Int?) {
    AssistChip(
        onClick = DroneScoutBridgeMonitor::toggleAudioMuted,
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Bridge ${rssi ?: "—"}", maxLines = 1)
                SignalStrengthBars(
                    rssi = rssi ?: 0,
                    modifier = Modifier.width(26.dp).height(22.dp),
                    colorByStrength = true,
                )
            }
        },
    )
}
