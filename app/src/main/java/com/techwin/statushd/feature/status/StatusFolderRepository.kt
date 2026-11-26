package com.techwin.statushd.feature.status

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StatusFolderRepository(private val context: Context) {

    suspend fun queryStatusFolder(treeUri: Uri): List<StatusMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StatusMediaItem>()
        val contentResolver = context.contentResolver

        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.e("StatusFolderRepo", "Failed to parse tree document ID", e)
            return@withContext emptyList()
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val cursor = try {
            contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            Log.e("StatusFolderRepo", "Failed to query status folder children", e)
            null
        }

        cursor?.use { c ->
            val idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = if (idIndex != -1) c.getString(idIndex) else continue
                val name = if (nameIndex != -1) c.getString(nameIndex) ?: "" else ""
                val mime = if (mimeIndex != -1) c.getString(mimeIndex) ?: "" else ""
                val size = if (sizeIndex != -1) c.getLong(sizeIndex) else 0L
                val lastModified = if (modifiedIndex != -1) c.getLong(modifiedIndex) else 0L

                // Skip directories, .nomedia, zero-byte files
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name.equals(".nomedia", ignoreCase = true)) continue
                if (size <= 0L) continue

                val itemUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                // Validate signature header without loading file into memory
                var mediaType: StatusMediaType? = null
                var detectedMime: String? = null
                try {
                    contentResolver.openInputStream(itemUri)?.use { stream ->
                        val buffer = ByteArray(32)
                        val bytesRead = stream.read(buffer, 0, 32)
                        if (bytesRead >= 8) {
                            mediaType = StatusSignatureValidator.validateStreamHeader(java.io.ByteArrayInputStream(buffer))
                            detectedMime = StatusSignatureValidator.detectMimeType(buffer, bytesRead)
                        }
                    }
                } catch (_: Exception) {
                    continue
                }

                if (mediaType == null) continue

                var durationMs = 0L
                var width = 0
                var height = 0

                if (mediaType == StatusMediaType.VIDEO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, itemUri)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

                        durationMs = durStr?.toLongOrNull() ?: 0L
                        width = wStr?.toIntOrNull() ?: 0
                        height = hStr?.toIntOrNull() ?: 0

                        if (durationMs <= 0L || width <= 0 || height <= 0) {
                            // Unreadable video track
                            continue
                        }
                    } catch (_: Exception) {
                        continue
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }

                items.add(
                    StatusMediaItem(
                        documentId = docId,
                        uri = itemUri,
                        displayName = name,
                        mimeType = detectedMime ?: mime,
                        size = size,
                        lastModified = lastModified,
                        mediaType = mediaType!!,
                        durationMs = durationMs,
                        width = width,
                        height = height
                    )
                )
            }
        }

        // Sort newest first when lastModified is available
        items.sortByDescending { it.lastModified }

        // Limit initial results to 200 items
        if (items.size > 200) {
            items.subList(0, 200)
        } else {
            items
        }
    }
}
