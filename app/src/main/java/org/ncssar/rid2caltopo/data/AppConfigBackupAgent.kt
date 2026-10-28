package org.ncssar.rid2caltopo.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.File

/** Data callbacks are binder workers; onCreate does no I/O. */
class AppConfigBackupAgent : BackupAgent() {
    private fun snapshot(): ByteArray {
        val file = File(filesDir, "datastore/app_config.pb")
        val config = if (file.isFile) file.inputStream().use { AppConfig.parseFrom(it) }
            else AppConfig.getDefaultInstance()
        return SettingsBackupPolicy.settingsOnly(config).toByteArray()
    }

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor?) {
        val bytes = snapshot()
        data.writeEntityHeader("app_config:datastore/app_config.pb", -1)
        data.writeEntityHeader(KEY, bytes.size)
        data.writeEntityData(bytes, bytes.size)
    }

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor?) {
        while (data.readNextHeader()) {
            if (data.key != KEY || data.dataSize !in 0..MAX_BYTES) { data.skipEntityData(); continue }
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
        val file = File(filesDir, SETTINGS_PATH)
        file.parentFile?.mkdirs()
        file.writeBytes(snapshot())
        fullBackupFile(file, data)
    }

    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File, type: Int, mode: Long, mtime: Long) {
        ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
            if (type != TYPE_FILE || destination.canonicalFile != File(filesDir, SETTINGS_PATH).canonicalFile || size !in 0..MAX_BYTES.toLong()) return
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size-offset)
                require(count > 0) { "Incomplete settings backup" }; offset += count
            }
            AppConfigStore.restoreSystemBackup(this, bytes)
        }
    }

    companion object {
        private const val KEY = "settings-v2"
        private const val MAX_BYTES = 16 * 1024 * 1024
        const val SETTINGS_PATH = "datastore/app_config_settings_backup.pb"
    }
}
