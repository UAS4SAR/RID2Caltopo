/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound store for one personal account signing key.
 * Separate alias and file from Teams credentials and from DJI cloud credentials.
 * Auto Backup does not include [Context.getNoBackupFilesDir].
 * Reads and writes must stay off the main thread; the Android Keystore can block.
 */
class CaltopoPersonalAccountKeystore(private val context: Context) : CaltopoPersonalAccountStore {
    override fun load(): CaltopoPersonalAccountRecord? {
        requireWorker()
        val file = file()
        if (!file.isFile) return null
        return CaltopoPersonalAccountRecord.fromJson(loadProtected().toString(Charsets.UTF_8))
    }

    override fun save(record: CaltopoPersonalAccountRecord) {
        requireWorker()
        saveProtected(record.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    override fun clear() {
        requireWorker()
        file().delete()
    }

    private fun file() = File(context.noBackupFilesDir, FILE_NAME)

    private fun requireWorker() {
        val main = Looper.getMainLooper()
        check(main == null || Looper.myLooper() != main) {
            "Personal CalTopo credential storage runs off the main thread."
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true)
                .build())
        }.generateKey()
    }

    private fun saveProtected(value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(FILE_NAME.toByteArray())
        val encrypted = cipher.doFinal(value)
        val temporary = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
        }
        check(temporary.renameTo(file())) { "Could not save the personal CalTopo credential." }
    }

    private fun loadProtected(): ByteArray {
        val bytes = file().readBytes()
        val size = bytes[0].toInt() and 255
        require(size == 12 && bytes.size > 29) { "Personal CalTopo credential needs to be connected again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
        }
        cipher.updateAAD(FILE_NAME.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size))
    }

    private companion object {
        const val ALIAS = "r2c.caltopo.personal-account"
        const val FILE_NAME = "caltopo-personal-account.enc"
    }
}
