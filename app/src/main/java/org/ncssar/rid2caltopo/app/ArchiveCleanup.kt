/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app

import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ARCHIVE_DIR_PREFIX = "tracks-"
private val archiveDirDateFormat get() = SimpleDateFormat("ddMMMyyyy", Locale.US).apply {
    isLenient = false
}

data class ArchiveCleanupDirectoryOption(
    val directoryName: String,
    val ageMs: Long,
    val ageLabel: String,
    val totalBytes: Long,
    val sizeLabel: String,
    val logFileCount: Int,
    val kmzCount: Int,
    val videoCount: Int,
    val lastModifiedMs: Long,
    val isToday: Boolean,
)

data class ArchiveCleanupDeleteResult(
    val deletedCount: Int,
    val failedDirectoryNames: List<String>,
)

internal data class ArchiveCleanupFileEntry(
    val name: String?,
    val type: String?,
    val isDirectory: Boolean,
    val length: Long,
    val children: List<ArchiveCleanupFileEntry> = emptyList(),
)

internal data class ArchiveCleanupSummary(
    val totalBytes: Long,
    val logFileCount: Int,
    val kmzCount: Int,
    val videoCount: Int,
)

internal fun isDatedArchiveDirectoryName(name: String?): Boolean =
    parseArchiveDirectoryDateMs(name) != null

internal fun parseArchiveDirectoryDateMs(name: String?): Long? {
    val trimmed = name?.trim() ?: return null
    if (!trimmed.startsWith(ARCHIVE_DIR_PREFIX)) return null
    val datePart = trimmed.removePrefix(ARCHIVE_DIR_PREFIX)
    if (datePart.length != "ddMMMyyyy".length) return null
    return runCatching {
        archiveDirDateFormat.parse(datePart)?.time
    }.getOrNull()
}

internal fun formatArchiveAge(ageMs: Long): String {
    val safeAgeMs = ageMs.coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    val monthMs = 30L * dayMs
    val yearMs = 365L * dayMs
    return when {
        safeAgeMs < minuteMs -> "<1 minute"
        safeAgeMs < hourMs -> formatArchiveAgeUnit(safeAgeMs / minuteMs, "minute")
        safeAgeMs < dayMs -> formatArchiveAgeUnit(safeAgeMs / hourMs, "hour")
        safeAgeMs < monthMs -> formatArchiveAgeUnit(safeAgeMs / dayMs, "day")
        safeAgeMs < yearMs -> formatArchiveAgeUnit(safeAgeMs / monthMs, "month")
        else -> formatArchiveAgeUnit(safeAgeMs / yearMs, "year")
    }
}

private fun formatArchiveAgeUnit(value: Long, unit: String): String =
    "$value $unit${if (value == 1L) "" else "s"}"

internal fun formatArchiveSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024L) return "$safeBytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (value >= 10.0) {
        String.format(Locale.US, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

internal fun summarizeArchiveEntries(entries: List<ArchiveCleanupFileEntry>): ArchiveCleanupSummary {
    var totalBytes = 0L
    var logFileCount = 0
    var kmzCount = 0
    var videoCount = 0

    fun visit(entry: ArchiveCleanupFileEntry) {
        if (entry.isDirectory) {
            entry.children.forEach(::visit)
            return
        }
        totalBytes += entry.length.coerceAtLeast(0L)
        val lowerName = entry.name?.lowercase(Locale.US).orEmpty()
        val lowerType = entry.type?.lowercase(Locale.US).orEmpty()
        if (lowerType == "text/plain" || lowerName.endsWith(".txt")) logFileCount++
        if (lowerName.endsWith(".kmz")) kmzCount++
        if (
            lowerType.startsWith("video/") ||
            lowerName.endsWith(".mp4") ||
            lowerName.endsWith(".fmp4") ||
            lowerName.endsWith(".ts")
        ) {
            videoCount++
        }
    }

    entries.forEach(::visit)
    return ArchiveCleanupSummary(totalBytes, logFileCount, kmzCount, videoCount)
}

internal fun defaultSelectedArchiveCleanupDirectories(): Set<String> = emptySet()

internal fun canDeleteArchiveCleanupSelection(
    options: List<ArchiveCleanupDirectoryOption>,
    selectedDirectoryNames: Set<String>,
): Boolean =
    options.any { option -> !option.isToday && selectedDirectoryNames.contains(option.directoryName) }

internal fun buildArchiveCleanupOption(
    directoryName: String,
    lastModifiedMs: Long,
    entries: List<ArchiveCleanupFileEntry>,
    nowMs: Long = System.currentTimeMillis(),
    todayName: String = todayArchiveDirectoryName(),
): ArchiveCleanupDirectoryOption? {
    val parsedDateMs = parseArchiveDirectoryDateMs(directoryName) ?: return null
    val ageBaseMs = parsedDateMs
    val summary = summarizeArchiveEntries(entries)
    val ageMs = (nowMs - ageBaseMs).coerceAtLeast(0L)
    return ArchiveCleanupDirectoryOption(
        directoryName = directoryName,
        ageMs = ageMs,
        ageLabel = formatArchiveAge(ageMs),
        totalBytes = summary.totalBytes,
        sizeLabel = formatArchiveSize(summary.totalBytes),
        logFileCount = summary.logFileCount,
        kmzCount = summary.kmzCount,
        videoCount = summary.videoCount,
        lastModifiedMs = lastModifiedMs,
        isToday = directoryName == todayName || FlightStorage.isProtected(directoryName),
    )
}

internal fun todayArchiveDirectoryName(date: Date = Date()): String =
    ARCHIVE_DIR_PREFIX + archiveDirDateFormat.format(date)

internal fun documentFileToArchiveEntry(file: DocumentFile): ArchiveCleanupFileEntry =
    ArchiveCleanupFileEntry(
        name = file.name,
        type = file.type,
        isDirectory = file.isDirectory,
        length = if (file.isDirectory) 0L else file.length(),
        children = if (file.isDirectory) file.listFiles().map(::documentFileToArchiveEntry) else emptyList(),
    )
