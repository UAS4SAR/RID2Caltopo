package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MappingRegionStorageTest {
    @Test fun missionKeepsMetadataInternalAndBulkFilesOnMicroSd() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val card=MappingVolume.card(context)?:error("Test requires microSD")
        val id=UUID.randomUUID().toString();val root=MappingVolume.directory(context,"mapping-missions",id,true)
        try {
            assertFalse(java.nio.file.Files.isSymbolicLink(root.toPath()))
            assertTrue(root.canonicalPath.startsWith(context.filesDir.canonicalPath+"/"))
            assertTrue(File(root,"originals").canonicalPath.startsWith(card.canonicalPath+"/"))
            assertTrue(File(root,"cloud/storage").canonicalPath.startsWith(card.canonicalPath+"/"))
            File(root,"inventory").mkdirs();File(root,"inventory/metadata.json").writeText("{}")
            assertTrue(File(root,"inventory").canonicalPath.startsWith(context.filesDir.canonicalPath+"/"))
            File(root,"mission.json").writeText(org.json.JSONObject().put("missionId",id).toString())
            File(root,"originals/test.jpg").writeBytes(ByteArray(1048576))
            assertTrue(MappingMissionStorage.index(context.filesDir).mission(id).originals>=1048576)
        } finally {root.deleteRecursively();File(card,"mapping-missions/$id").deleteRecursively()}
    }
    @Test fun publishedMicroSdPackagesCanBeSharedReadOnly() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString();val root=MappingVolume.directory(context,"mapping-layers",id,true)
        val physical=root.canonicalFile
        try {
            File(root,"layer.json").writeText("{}")
            val bytes=ByteArray(1024){(it%127).toByte()};File(root,"share.zip").writeBytes(bytes)
            val uri=android.net.Uri.parse("content://${context.packageName}.mapping-share/$id/share.zip")
            assertArrayEquals(bytes,context.contentResolver.openInputStream(uri)!!.use{it.readBytes()})
            assertThrows(Exception::class.java){context.contentResolver.openOutputStream(uri)!!.close()}
        } finally {root.deleteRecursively();physical.deleteRecursively()}
    }
    @Test fun removableStorageAliasAndPublicationSurviveReopen() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val card=MappingVolume.card(context)?:error("Test requires a mounted microSD")
        val id=UUID.randomUUID().toString()
        val scratch=MappingVolume.directory(context,"mapping-processing",id,true)
        val target=MappingVolume.directory(context,"mapping-layers",id,true)
        val physical=scratch.canonicalFile
        try {
            assertTrue(physical.path.startsWith(card.canonicalPath+"/"))
            val sample=File(scratch,"sample.bin");sample.writeBytes(ByteArray(1024*1024){(it%251).toByte()})
            assertEquals(MappingRegionProcessor.sha(sample),MappingRegionProcessor.sha(File(physical,"sample.bin")))
            assertEquals(scratch.canonicalPath,MappingVolume.directory(context,"mapping-processing",id,true).canonicalPath)
            val dest=target.canonicalFile;assertTrue(dest.delete());assertTrue(physical.renameTo(dest))
            assertTrue(File(target,"sample.bin").isFile)
            android.util.Log.i("MappingRegionTest","microSD ${card.path}, free ${card.usableSpace}, alias/publication verified")
        } finally {target.deleteRecursively();scratch.delete();physical.deleteRecursively()}
    }
}
