package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Byte-level activity for slow/removable storage; callbacks also make large files cancellable. */
internal object MappingFileProgress {
    fun label(done:Long,total:Long):String = String.format(Locale.US,"%.1f / %.1f MB",done/1048576.0,total/1048576.0)
    fun transfer(input:InputStream,output:OutputStream,check:()->Unit,progress:(Long)->Unit) {
        val buffer=ByteArray(256*1024);var done=0L
        while(true){check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n);done+=n;progress(done)}
    }
    fun sha(file:File,check:()->Unit={},progress:(Long)->Unit={}):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024);var done=0L
        file.inputStream().buffered().use { input->while(true){check();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);done+=n;progress(done)} }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun copyTree(source:File,target:File,stage:String,check:()->Unit,progress:(String)->Unit) {
        val files=source.walkTopDown().filter{it.isFile}.toList();val total=files.sumOf{it.length()};var completed=0L
        files.forEachIndexed { i,file->check();val destination=File(target,file.relativeTo(source).path)
            require(destination.parentFile!!.isDirectory||destination.parentFile!!.mkdirs()){"Cannot create map output directory."}
            file.inputStream().buffered().use { input->destination.outputStream().buffered().use { output->
                transfer(input,output,check){bytes->progress("$stage · file ${i+1}/${files.size} · ${label(completed+bytes,total)}")}
            }};completed+=file.length()
        }
    }
}
