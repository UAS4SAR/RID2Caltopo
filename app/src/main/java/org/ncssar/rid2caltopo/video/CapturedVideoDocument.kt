package org.ncssar.rid2caltopo.video

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.documentfile.provider.DocumentFile

internal class OpenCapturedVideoDocument : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra("android.content.extra.NO_CACHE", true)
            if (input != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}

internal fun resolveCapturedVideoDisplayName(
    context: android.content.Context,
    uri: Uri,
): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                if (name.isNotEmpty()) return name
            }
        }
    } catch (_: Exception) {
    }
    return DocumentFile.fromSingleUri(context, uri)?.name?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Captured Video"
}

