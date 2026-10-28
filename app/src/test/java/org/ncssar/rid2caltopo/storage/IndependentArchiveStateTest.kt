package org.ncssar.rid2caltopo.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IndependentArchiveStateTest {
    private open class Tree(private val root:File):ArchiveTree {
        fun file(path:String)=File(root,VerifiedArchiveTree.checked(path))
        override fun list(path:String)=file(path).listFiles().orEmpty().map{ArchiveTree.Entry(it.name,it.isDirectory,it.length(),it.lastModified())}
        override fun exists(path:String)=file(path).exists()
        override fun read(path:String):InputStream=file(path).inputStream()
        override fun write(path:String,input:InputStream){val f=file(path);f.parentFile!!.mkdirs();f.outputStream().use{input.copyTo(it)}}
        override fun mkdirs(path:String){file(path).mkdirs()}
        override fun rename(from:String,to:String):Boolean{file(to).parentFile!!.mkdirs();return file(from).renameTo(file(to))}
        override fun delete(path:String){check(file(path).deleteRecursively())}
    }
    private val queueJournal="${VerifiedArchiveTree.JOURNAL}/queue-state"
    private val queueTarget="application-logs/operational/queue-state/device-marker-cleanup.json"

    @Test fun changedArchiveSelectionRejectsQueueWriteAndKeepsPreviousDurableBytes() {
        val root=Files.createTempDirectory("independent-selection").toFile()
        try {
            val tree=Tree(File(root,"archive"))
            var generation=1
            val writer=VerifiedArchiveTree(tree,journalDirectory=queueJournal,cancelled={check(generation==1){"Archive changed"}})
            val source=File(root,"state").apply{writeText("previous ownership")}
            writer.put(source,queueTarget)
            generation=2
            source.writeText("new ownership")
            assertThrows(IllegalStateException::class.java){writer.put(source,queueTarget)}
            assertEquals("previous ownership",tree.read(queueTarget).reader().readText())
            assertEquals("new ownership",source.readText())
        } finally {root.deleteRecursively()}
    }

    @Test fun durableQueueWriteFinishesWhileBulkVerificationIsBlocked() {
        val root=Files.createTempDirectory("independent-state").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val archive=File(root,"archive")
            val bulkTree=object:Tree(archive){override fun read(path:String):InputStream {
                if(path.startsWith("cache/bulk.r2c-stage-")){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                return super.read(path)
            }}
            val bulk=VerifiedArchiveTree(bulkTree)
            val queueTree=Tree(archive)
            val queue=VerifiedArchiveTree(queueTree,journalDirectory=queueJournal)
            val bulkSource=File(root,"bulk").apply{writeText("bulk verification")}
            val stateSource=File(root,"state").apply{writeText("durable marker ownership")}
            val copying=executor.submit{bulk.put(bulkSource,"cache/bulk")}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            executor.submit{queue.put(stateSource,queueTarget)}.get(1,TimeUnit.SECONDS)
            assertEquals("durable marker ownership",queueTree.read(queueTarget).reader().readText())
            assertFalse(copying.isDone)
            release.countDown();copying.get(2,TimeUnit.SECONDS)
        } finally {release.countDown();executor.shutdownNow();root.deleteRecursively()}
    }

    @Test fun bulkRecoveryCannotReplayAnIndependentQueuesPendingTransaction() {
        val root=Files.createTempDirectory("independent-recovery").toFile()
        try {
            val archive=File(root,"archive")
            val broken=object:Tree(archive){
                var interrupt=true
                override fun write(path:String,input:InputStream){
                    if(path=="$queueTarget.r2c-info" && interrupt){interrupt=false;throw IOException("interrupted metadata")}
                    super.write(path,input)
                }
            }
            val source=File(root,"state").apply{writeText("new ownership")}
            val queue=VerifiedArchiveTree(broken,journalDirectory=queueJournal)
            assertThrows(IOException::class.java){queue.put(source,queueTarget,modified=100)}
            assertEquals(1,broken.list(queueJournal).count{it.name.endsWith(".json")})
            VerifiedArchiveTree(Tree(archive)).recover()
            assertEquals(1,broken.list(queueJournal).count{it.name.endsWith(".json")})
            VerifiedArchiveTree(Tree(archive),journalDirectory=queueJournal).recover()
            assertTrue(broken.list(queueJournal).isEmpty())
            assertEquals("new ownership",broken.read(queueTarget).reader().readText())
        } finally {root.deleteRecursively()}
    }
}
