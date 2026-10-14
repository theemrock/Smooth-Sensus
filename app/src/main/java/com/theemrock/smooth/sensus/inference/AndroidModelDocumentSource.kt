package com.theemrock.smooth.sensus.inference

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

class AndroidModelDocumentSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri
) : ModelDocumentSource {
    override val document: ModelDocument by lazy {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
        }
        ModelDocument(displayName, sizeBytes)
    }

    override fun openInputStream(): InputStream =
        contentResolver.openInputStream(uri)
            ?: throw IOException("Selected model document could not be opened")
}

fun takePersistableModelReadPermission(
    contentResolver: ContentResolver,
    uri: Uri
): Boolean = try {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    true
} catch (_: SecurityException) {
    false
}
