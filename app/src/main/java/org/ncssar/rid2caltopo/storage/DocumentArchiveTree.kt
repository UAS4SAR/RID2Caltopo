package org.ncssar.rid2caltopo.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/** Worker-confined. One provider query per directory, rather than a full directory scan per file. */
internal class DocumentArchiveTree(private val context: Context, private val root: DocumentFile,
    private val requireKnownFileSizes: Boolean = false) : ArchiveTree {
    private data class Node(val uri: Uri, val directory: Boolean, val bytes: Long = 0, val modified: Long = 0,
                            val mimeType: String? = null)
    private val resolver = context.contentResolver
    private val rootNode = Node(root.uri, true)
    private val children = mutableMapOf<String, MutableMap<String, Node>>()
    private var rootCheckedAt = 0L
    override fun refresh() { children.clear(); rootCheckedAt = 0 }
    private fun isMissingDocument(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.io.FileNotFoundException) return true
            val message = current.message.orEmpty()
            if (message.contains("Missing file") || message.contains("is child of")) return true
            current = current.cause
        }
        return false
    }
    private fun forget(path: String) {
        if (path.isEmpty()) { children.clear(); return }
        children.remove(path)
        children.keys.filter { it == path || it.startsWith("$path/") }.forEach { children.remove(it) }
        val parent = path.substringBeforeLast('/', "")
        children[parent]?.remove(path.substringAfterLast('/'))
    }
    private fun checkRoot() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - rootCheckedAt >= 1000 || rootCheckedAt == 0L) {
            check(root.exists() && root.isDirectory && root.canRead()) { "Archive Dir is unavailable; reconnect its storage." }
            rootCheckedAt = now
        }
    }
    private fun find(path: String): Node? {
        checkRoot()
        if (path.isEmpty()) return rootNode
        var node = rootNode
        var parent = ""
        for (part in VerifiedArchiveTree.checked(path).split('/')) {
            val listing = try {
                entries(parent, node)
            } catch (error: Exception) {
                if (!isMissingDocument(error)) throw error
                forget(parent)
                return null
            }
            node = listing[part] ?: return null
            parent = if (parent.isEmpty()) part else "$parent/$part"
        }
        return node
    }
    private fun entries(path: String, parent: Node): MutableMap<String, Node> = children.getOrPut(path) {
        if (parent.uri.scheme == "file") {
            File(requireNotNull(parent.uri.path)).listFiles()?.associate { it.name to Node(Uri.fromFile(it), it.isDirectory, it.length(), it.lastModified()) }?.toMutableMap()
                ?: error("Cannot list archive directory: $path")
        } else {
            val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, DocumentsContract.getDocumentId(parent.uri))
            val result = mutableMapOf<String, Node>()
            val cursor = resolver.query(uri, projection, null, null, null) ?: error("Cannot list archive directory: $path")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: error("Unnamed archive entry")
                    check(name !in setOf(".", "..") && '/' !in name && '\u0000' !in name) { "Unsafe archive entry name" }
                    check(!result.containsKey(name)) { "Duplicate archive names cannot be migrated safely." }
                    check(!requireKnownFileSizes || it.getString(2) == Document.MIME_TYPE_DIR || (!it.isNull(3) && it.getLong(3) >= 0)) {
                        "Storage provider did not report a file size: $name"
                    }
                    result[name] = Node(DocumentsContract.buildDocumentUriUsingTree(parent.uri, it.getString(0)),
                        it.getString(2) == Document.MIME_TYPE_DIR, if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4), it.getString(2))
                }
            }
            result
        }
    }
    override fun mkdirs(path: String) {
        if (path.isEmpty()) return
        var parent = ""
        var node = rootNode
        for (part in VerifiedArchiveTree.checked(path).split('/')) {
            val listing = entries(parent, node)
            val next = listing[part] ?: create(node, part, true).also { listing[part] = it }
            check(next.directory) { "Archive path is not a directory: $path" }
            node = next; parent = if (parent.isEmpty()) part else "$parent/$part"
        }
    }
    private fun create(parent: Node, name: String, directory: Boolean): Node {
        if (parent.uri.scheme == "file") {
            val file = File(requireNotNull(parent.uri.path), name)
            if (directory) check(file.isDirectory || file.mkdir()) else check(file.exists() || file.createNewFile())
            return Node(Uri.fromFile(file), directory)
        }
        val uri = DocumentsContract.createDocument(resolver, parent.uri,
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)
            ?: error("Cannot create archive entry: $name")
        val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        val actual = cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
        val exact = if (actual == name) uri else DocumentsContract.renameDocument(resolver, uri, name)
            ?: error("Provider cannot preserve the archive filename: $name")
        return Node(exact, directory)
    }
    override fun exists(path: String): Boolean = find(path) != null
    fun uri(path: String): Uri = find(path)?.uri ?: error("Archive entry is unavailable")
    override fun list(path: String): List<ArchiveTree.Entry> {
        val folder = find(path) ?: return emptyList()
        check(folder.directory)
        return entries(path, folder).map { (name, node) -> ArchiveTree.Entry(name, node.directory, node.bytes, node.modified, node.mimeType) }
    }
    override fun read(path: String): InputStream {
        val node = find(path) ?: error("Archive file is unavailable: $path")
        return try {
            if (node.uri.scheme == "file") File(requireNotNull(node.uri.path)).inputStream()
            else {
                // Archive Dir is granted read/write access. Android's external-storage provider
                // can route a read-only media open through MediaStore, changing the returned
                // bytes (for example, EXIF redaction) after a staging file becomes a JPEG.
                // Use a non-truncating document descriptor for the bytes we retain and verify.
                // A provider that cannot supply it must fail before originals are removed.
                val descriptor = resolver.openFileDescriptor(node.uri, "rw")
                    ?: error("Cannot read original archive bytes: $path")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            }
        } catch (error: Exception) {
            if (!isMissingDocument(error)) throw error
            forget(path)
            error("Archive file is unavailable: $path")
        }
    }
    override fun write(path: String, input: InputStream) {
        VerifiedArchiveTree.checked(path)
        val parent = path.substringBeforeLast('/', ""); val name = path.substringAfterLast('/')
        mkdirs(parent)
        val directory = find(parent) ?: error("Archive directory is unavailable")
        val listing = entries(parent, directory)
        val node = listing[name] ?: create(directory, name, false).also { listing[name] = it }
        var bytes = 0L
        if (node.uri.scheme == "file") {
            java.io.FileOutputStream(File(requireNotNull(node.uri.path))).use { out -> bytes = input.copyTo(out, 256 * 1024); out.fd.sync() }
        } else {
            val descriptor = resolver.openFileDescriptor(node.uri, "rwt") ?: error("Cannot write archive file: $path")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                bytes = input.copyTo(out, 256 * 1024); out.flush()
                if (OsConstants.S_ISREG(Os.fstat(out.fd).st_mode)) out.fd.sync()
            }
        }
        listing[name] = node.copy(bytes = bytes, modified = System.currentTimeMillis())
    }
    override fun rename(from: String, to: String): Boolean {
        val parent = from.substringBeforeLast('/', "")
        require(parent == to.substringBeforeLast('/', ""))
        if (exists(to)) return false
        val node = find(from) ?: return false
        val renamed = runCatching {
            if (node.uri.scheme == "file") {
                val target = File(requireNotNull(find(parent)?.uri?.path), to.substringAfterLast('/'))
                if (File(requireNotNull(node.uri.path)).renameTo(target)) Uri.fromFile(target) else null
            } else DocumentsContract.renameDocument(resolver, node.uri, to.substringAfterLast('/'))
        }.getOrNull() ?: return false
        children[parent]?.remove(from.substringAfterLast('/'))
        children[parent]?.set(to.substringAfterLast('/'), node.copy(uri = renamed))
        children.keys.filter { it == from || it.startsWith("$from/") }.forEach { children.remove(it) }
        return true
    }
    override fun delete(path: String) {
        VerifiedArchiveTree.checked(path)
        val node = find(path) ?: return
        val removed = if (node.uri.scheme == "file") File(requireNotNull(node.uri.path)).deleteRecursively()
            else DocumentsContract.deleteDocument(resolver, node.uri)
        check(removed) { "Could not remove archive entry: $path" }
        children[path.substringBeforeLast('/', "")]?.remove(path.substringAfterLast('/'))
        children.keys.filter { it == path || it.startsWith("$path/") }.forEach { children.remove(it) }
    }
}
