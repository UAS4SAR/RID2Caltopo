package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Sandbox fixtures only; never selects or migrates the installed app's Archive Dir. */
@RunWith(AndroidJUnit4::class)
class OperationalStorageTest {
    private fun fixture(body: (Context, File) -> Unit) {
        assumeTrue(Build.HARDWARE == "ranchu" && InstrumentationRegistry.getArguments().getString("storageQualification") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getExternalFilesDirs(type: String?) = arrayOf(File(root, "external").apply { mkdirs() })
            override fun getExternalCacheDirs() = arrayOf(File(root, "external-cache").apply { mkdirs() })
        }
        OperationalStorage.resetForTesting()
        try { body(context, File(root, "archive").apply { mkdirs() }) }
        finally { OperationalStorage.resetForTesting(); root.deleteRecursively() }
    }
    private fun write(root: File, name: String, text: String) = File(root, name).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun movesVerifiedOperationalFilesAndKeepsSettingsPrivate() = fixture { context, archive ->
        val mission = write(context.filesDir, "mapping-missions/mission/originals/photo.jpg", "original bytes")
        mission.setLastModified(1_750_000_000_000)
        write(context.filesDir, "clues/picture.jpg", "clue bytes")
        write(context.filesDir, "datastore/access.preferences_pb", "private credentials")
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertEquals("original bytes", File(archive, "cache/local-maps/mapping-missions/mission/originals/photo.jpg").readText())
        assertEquals("original bytes", mission.readText())
        assertEquals(1_750_000_000_000, mission.lastModified())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(context.filesDir, "mapping-missions").toPath()))
        assertEquals("clue bytes", File(archive, "application-logs/operational/clues/picture.jpg").readText())
        assertFalse(archive.walkTopDown().filter { it.isFile }.any { it.readText().contains("private credentials") })
        assertEquals("private credentials", File(context.filesDir, "datastore/access.preferences_pb").readText())
    }

    @Test fun conflictDoesNotRemoveEitherOriginal() = fixture { context, archive ->
        val original = write(context.filesDir, "clues/picture.jpg", "local original")
        val retained = write(archive, "application-logs/operational/clues/picture.jpg", "different retained original")
        assertThrows(IllegalStateException::class.java) { OperationalStorage.prepare(context, DocumentFile.fromFile(archive)) }
        assertEquals("local original", original.readText())
        assertEquals("different retained original", retained.readText())
        assertFalse(OperationalStorage.ready())
    }
    @Test fun missingDisposableWorkspaceIsRebuiltFromArchive() = fixture { context, archive ->
        val file = write(context.filesDir, "clues/picture.jpg", "durable clue")
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        val workspace = File(context.filesDir, "clues").canonicalFile
        workspace.deleteRecursively()
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertEquals("durable clue", file.readText())
    }
    @Test fun documentProviderMigrationAndRestartPreserveRetainedData() = fixture { context, _ ->
        val id = UUID.randomUUID().toString()
        context.startActivity(android.content.Intent().setClassName("org.ncssar.rid2caltopo.test", "org.ncssar.rid2caltopo.storage.QualificationArchiveGrantActivity")
            .putExtra("root", id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val archive = DocumentFile.fromTreeUri(context, android.provider.DocumentsContract.buildTreeDocumentUri("org.ncssar.rid2caltopo.test.storage", id))!!
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (!runCatching { archive.canWrite() }.getOrDefault(false) && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue("The test provider's tree grant was not delivered", archive.canWrite())
        try {
            val clue = write(context.filesDir, "clues/picture.jpg", "provider clue")
            OperationalStorage.prepare(context, archive)
            val tree = DocumentArchiveTree(context, archive)
            assertEquals("provider clue", tree.read("application-logs/operational/clues/picture.jpg").reader().readText())
            clue.writeText("updated provider clue"); OperationalStorage.retain(clue)
            OperationalStorage.resetForTesting(); OperationalStorage.prepare(context, archive)
            assertEquals("updated provider clue", clue.readText())
            OperationalStorage.remove(clue)
            OperationalStorage.resetForTesting(); OperationalStorage.prepare(context, archive)
            assertFalse(clue.exists())
        } finally { archive.delete() }
    }
    @Test fun unfinishedRecordingSurvivesWorkspaceRemovalAndHeaderUpdate() = fixture { context, archive ->
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        val bytes = ByteArray(1024 * 1024 + 512) { (it % 199).toByte() }
        val file = File(context.filesDir, "mediamtx-recordings/controller/raw.mp4").apply { parentFile!!.mkdirs(); writeBytes(bytes.copyOfRange(0, 700_000)) }
        OperationalStorage.retainGrowingRecording(file)
        file.appendBytes(bytes.copyOfRange(700_000, bytes.size))
        // MediaMTX fills the movie duration into the initial header on close.
        bytes[40] = 91
        java.io.RandomAccessFile(file, "rw").use { it.seek(40); it.write(91) }
        OperationalStorage.retainGrowingRecording(file)
        File(context.filesDir, "mediamtx-recordings").canonicalFile.deleteRecursively()
        OperationalStorage.resetForTesting(); OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertArrayEquals(bytes, file.readBytes())
        OperationalStorage.remove(file)
        OperationalStorage.resetForTesting(); OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertFalse(file.exists())
    }

    @Test fun lastPublishedMapGenerationSurvivesUncommittedWorkspaceChanges() = fixture { context, archive ->
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        val layer = File(context.filesDir, "mapping-layers/map-id")
        write(layer, "layer.json", "saved metadata")
        val data = write(layer, "tiles/data", "saved tile")
        OperationalStorage.retainTree(layer)
        data.writeText("unfinished new tile")
        write(layer, "tiles/unfinished", "unpublished")
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertEquals("saved tile", data.readText())
        assertFalse(File(layer, "tiles/unfinished").exists())
        assertEquals("saved metadata", File(layer, "layer.json").readText())
    }

    @Test fun retainedChunksResumeAndDeletionDoesNotReappearAfterRestart() = fixture { context, archive ->
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        val pending = File(context.filesDir, "mapping-missions/mission/pending/photo.part").apply { parentFile!!.mkdirs() }
        OperationalStorage.retainChunk(pending, 0, "first ".toByteArray())
        OperationalStorage.retainChunk(pending, 6, "second".toByteArray())
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertEquals("first second", pending.readText())
        val clue = write(context.filesDir, "clues/picture.jpg", "clue")
        OperationalStorage.retain(clue)
        OperationalStorage.remove(clue)
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertFalse(clue.exists())
        assertFalse(File(archive, "application-logs/operational/clues/picture.jpg").exists())
    }
    @Test fun movesNestedSdAliasesAndPreservesAResumablePhotoTransfer() = fixture { context, archive ->
        val mission = File(context.filesDir, "mapping-missions/one").apply { mkdirs() }
        val originals = File(context.getExternalFilesDirs(null).first(), "local-mapping/mapping-missions/one/originals").apply { mkdirs() }
        write(originals, "photo.jpg", "saved SD original")
        android.system.Os.symlink(originals.path, File(mission, "originals").path)
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertEquals("saved SD original", File(mission, "originals/photo.jpg").readText())
        assertEquals("saved SD original", File(archive, "cache/local-maps/mapping-missions/one/originals/photo.jpg").readText())
        assertFalse(originals.exists())
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 4)
        val hash = VerifiedArchiveTree.digest(bytes.inputStream())
        var photos = org.ncssar.rid2caltopo.mapping.MappingPhotoStore(mission)
        photos.register("new.jpg", bytes.size.toLong(), hash)
        photos.append(hash, 0, bytes.copyOfRange(0, 3))
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        photos = org.ncssar.rid2caltopo.mapping.MappingPhotoStore(mission)
        assertEquals(3L, photos.progress(hash).getLong("offset"))
        photos.append(hash, 3, bytes.copyOfRange(3, bytes.size)); photos.commit(hash)
        OperationalStorage.resetForTesting()
        OperationalStorage.prepare(context, DocumentFile.fromFile(archive))
        assertArrayEquals(bytes, File(mission, "originals/$hash.jpg").readBytes())
        assertTrue(org.ncssar.rid2caltopo.mapping.MappingPhotoStore(mission).progress(hash).getBoolean("complete"))
    }
}
