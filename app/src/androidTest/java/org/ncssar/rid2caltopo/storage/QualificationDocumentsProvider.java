package org.ncssar.rid2caltopo.storage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Framework-only test provider: its separate process does not load the target's Kotlin runtime. */
public final class QualificationDocumentsProvider extends DocumentsProvider {
    private static final String AUTHORITY = "org.ncssar.rid2caltopo.test.storage";
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS };
    private final java.util.Set<String> transformingRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private File base() { File root = new File(getContext().getCacheDir(), "document-qualification"); root.mkdirs(); return root; }
    @Override public boolean onCreate() { return "ranchu".equals(Build.HARDWARE); }
    private File file(String id) {
        if (!"ranchu".equals(Build.HARDWARE)) throw new SecurityException("Emulator only");
        try {
            File root = base().getCanonicalFile(), result = new File(root, id).getCanonicalFile();
            if (!result.toPath().startsWith(root.toPath()) || result.equals(root)) throw new SecurityException("Unsafe test path");
            return result;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private String id(File file) { try { return base().getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString(); } catch (IOException error) { throw new IllegalStateException(error); } }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"newQualificationRoot".equals(method)) return super.call(method, arg, extras);
        String id = arg == null ? UUID.randomUUID().toString() : UUID.fromString(arg).toString();
        if (!file(id).mkdirs()) throw new IllegalStateException("Cannot create test root");
        if (extras != null && extras.getBoolean("transformMediaReads")) transformingRoots.add(id);
        Bundle result = new Bundle(); result.putString("uri", DocumentsContract.buildTreeDocumentUri(AUTHORITY, id).toString()); return result;
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[] { DocumentsContract.Root.COLUMN_ROOT_ID } : projection); }
    private void row(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id(file)); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                case Document.COLUMN_MIME_TYPE: row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                case Document.COLUMN_SIZE: row.add(file.length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(file.lastModified()); break;
                case Document.COLUMN_FLAGS: row.add(Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0)); break;
                default: row.add(null);
            }
        }
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) { MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection); row(result, file(documentId)); return result; }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) { MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection); File[] children = file(parentId).listFiles(); if (children != null) for (File child : children) row(result, child); return result; }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        // Model Android's read-only media path separately from its stored document bytes.
        // A stage has no media extension; publication as .jpg changes read-only results.
        if ("r".equals(mode) && id.endsWith(".jpg") && transformingRoots.contains(id.split("/")[0])) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (java.io.OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        out.write("transformed media bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }, "test-media-read").start();
                return pipe[0];
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    private File child(String parent, String name) { if (name.contains("/") || name.equals(".") || name.equals("..")) throw new SecurityException("Unsafe name"); return new File(file(parent), name); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException { File target = child(parent, name); try { if (!(Document.MIME_TYPE_DIR.equals(mime) ? target.mkdir() : target.createNewFile())) throw new IOException("Cannot create test document"); return id(target); } catch (IOException error) { throw new FileNotFoundException(error.toString()); } }
    @Override public String renameDocument(String oldId, String name) { File old = file(oldId), target = child(id(old.getParentFile()), name); if (target.exists() || !old.renameTo(target)) throw new IllegalStateException("Rename failed"); return id(target); }
    private void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); if (!file.delete()) throw new IllegalStateException("Delete failed"); }
    @Override public void deleteDocument(String id) { remove(file(id)); }
    @Override public boolean isChildDocument(String parent, String child) { return file(child).toPath().startsWith(file(parent).toPath()); }
}
