package org.ncssar.rid2caltopo.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.File

/** Data callbacks are binder workers; onCreate does no I/O. */
class AppConfigBackupAgent : BackupAgent() {
    /**
     * Settings to back up, or null when nothing has been saved yet. Never backs up the blank
     * default instance: restoring it would turn Capture Streams (and other ON defaults) off.
     */
    private fun snapshot(): ByteArray? = snapshotOf(File(filesDir, "datastore/app_config.pb"))

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor?) {
        val bytes = snapshot() ?: return logSkippedBackup("key/value")
        data.writeEntityHeader("app_config:datastore/app_config.pb", -1)
        data.writeEntityHeader(KEY, bytes.size)
        data.writeEntityData(bytes, bytes.size)
    }

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        while (data.readNextHeader()) {
            if (data.key != KEY || data.dataSize !in 0..MAX_BYTES) {
                CaltopoClient.CTInfo(TAG, "System restore skipped entry key=${data.key} size=${data.dataSize}")
                data.skipEntityData(); continue
            }
            CaltopoClient.CTInfo(TAG, "System restore (key/value) of settings, ${data.dataSize} bytes, from app version $appVersionCode")
            val bytes = ByteArray(data.dataSize)
            var offset = 0
            while (offset < bytes.size) {
                val count = data.readEntityData(bytes, offset, bytes.size-offset)
                require(count > 0) { "Incomplete settings backup" }; offset += count
            }
            AppConfigStore.restoreSystemBackup(this, bytes)
        }
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        val bytes = snapshot() ?: return logSkippedBackup("full")
        val file = File(filesDir, SETTINGS_PATH)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        fullBackupFile(file, data)
    }

    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File, type: Int, mode: Long, mtime: Long) {
        ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
            if (type != TYPE_FILE || destination.canonicalFile != File(filesDir, SETTINGS_PATH).canonicalFile || size !in 0..MAX_BYTES.toLong()) {
                CaltopoClient.CTInfo(TAG, "System restore skipped file ${destination.name} size=$size")
                return
            }
            CaltopoClient.CTInfo(TAG, "System restore (full) of settings, $size bytes")
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size-offset)
                require(count > 0) { "Incomplete settings backup" }; offset += count
            }
            AppConfigStore.restoreSystemBackup(this, bytes)
        }
    }

    private fun logSkippedBackup(kind: String) {
        CaltopoClient.CTInfo(TAG, "System backup ($kind) skipped: no saved settings yet")
    }

    companion object {
        private const val TAG = "AppConfigBackupAgent"
        /** Null when [file] is missing or empty, so the default instance is never backed up. */
        @JvmStatic
        internal fun snapshotOf(file: File): ByteArray? {
            if (!file.isFile || file.length() == 0L) return null
            val config = file.inputStream().use { AppConfig.parseFrom(it) }
            if (config == AppConfig.getDefaultInstance()) return null
            return SettingsBackupPolicy.settingsOnly(config).toByteArray()
        }

        private const val KEY = "settings-v2"
        private const val MAX_BYTES = 16 * 1024 * 1024
        const val SETTINGS_PATH = "datastore/app_config_settings_backup.pb"
    }
}
