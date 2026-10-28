package org.ncssar.rid2caltopo.storage

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/** Worker-only restoration of a disposable projection after retained originals have verified. */
internal object VerifiedProjectionFile {
    fun restore(
        target: File,
        verifiedExistingHash: String?,
        scratch: File,
        openRetained: () -> InputStream,
        modifiedForHash: (String) -> Long,
        checkCurrent: () -> Unit = {},
    ): Boolean {
        checkCurrent()
        if (target.exists()) {
            check(verifiedExistingHash != null && target.isFile &&
                target.inputStream().use { VerifiedArchiveTree.digest(it, checkCurrent) } == verifiedExistingHash) {
                "Existing operational projection changed during preparation; originals were retained."
            }
            // The caller already verified these bytes in Archive Dir. Keep the
            // original working copy instead of copying it back from Archive Dir.
            return true
        }
        val temporary = File.createTempFile("hydrate-", ".part", scratch)
        try {
            FileOutputStream(temporary).use { out ->
                openRetained().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
                out.fd.sync()
            }
            val hash = temporary.inputStream().use { VerifiedArchiveTree.digest(it, checkCurrent) }
            check(hash == openRetained().use { VerifiedArchiveTree.digest(it, checkCurrent) }) {
                "Operational projection verification failed; retained originals were kept."
            }
            temporary.setLastModified(modifiedForHash(hash))
            checkCurrent()
            // No replacement: a concurrent or unverified original must never be overwritten.
            Files.move(temporary.toPath(), target.toPath())
            return false
        } finally { temporary.delete() }
    }
}
