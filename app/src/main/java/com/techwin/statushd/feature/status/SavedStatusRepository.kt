package com.techwin.statushd.feature.status

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlreadySavedException(val savedUri: Uri) : Exception("Already saved to Gallery")

class SavedStatusRepository(
    private val context: Context,
    private val digestStore: SavedStatusDigestStore
) {

    suspend fun saveStatusItem(item: StatusMediaItem): Uri = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver

        // Step 1: Compute SHA-256 digest of source file first using 64 KiB buffer
        val digest = MessageDigest.getInstance("SHA-256")
        var sourceByteCount = 0L

        contentResolver.openInputStream(item.uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                sourceByteCount += bytesRead
            }
        } ?: throw IOException("Cannot open input stream for source status file")

        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }

        // Step 2: Check for existing duplicate record
        val existingRecord = digestStore.getDigestRecord(sha256Hex)
        if (existingRecord != null) {
            val existingUri = Uri.parse(existingRecord.outputUriString)
            var isReadable = false
            try {
                contentResolver.openAssetFileDescriptor(existingUri, "r")?.use { afd ->
                    if (afd.length == existingRecord.size) {
                        isReadable = true
                    }
                }
            } catch (_: Exception) {}

            if (isReadable) {
                throw AlreadySavedException(existingUri)
            }
        }

        // Step 3: Determine output target and display name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val extension = when {
            item.mimeType.contains("jpeg", true) || item.mimeType.contains("jpg", true) -> "jpg"
            item.mimeType.contains("png", true) -> "png"
            item.mimeType.contains("webp", true) -> "webp"
            else -> "mp4"
        }
        val displayName = "StatusHD_Saved_${timeStamp}.${extension}"

        val isVideo = item.mediaType == StatusMediaType.VIDEO
        val tableUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relativePath = if (isVideo) "Movies/StatusHD/Saved Status" else "Pictures/StatusHD/Saved Status"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var pendingUri: Uri? = null
        try {
            pendingUri = contentResolver.insert(tableUri, contentValues)
                ?: throw IOException("MediaStore insert returned null")

            var copiedBytes = 0L
            val outputStream = contentResolver.openOutputStream(pendingUri)
                ?: throw IOException("Failed to open MediaStore output stream")

            outputStream.use { out ->
                contentResolver.openInputStream(item.uri)?.use { inStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                    }
                    out.flush()
                } ?: throw IOException("Failed to reopen source input stream")
            }

            if (copiedBytes != sourceByteCount) {
                throw IOException("Copied bytes ($copiedBytes) does not match source byte count ($sourceByteCount)")
            }

            // Publish entry
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            val updated = contentResolver.update(pendingUri, contentValues, null, null)

            if (updated != 1) {
                Log.e("SavedStatusRepo", "MediaStore publication returned 0 rows updated")
                throw IOException("Failed to publish MediaStore entry (updated=$updated)")
            }

            // Save digest record ONLY after successful publication
            digestStore.saveDigestRecord(
                DigestRecord(
                    sha256 = sha256Hex,
                    outputUriString = pendingUri.toString(),
                    mimeType = item.mimeType,
                    size = copiedBytes
                )
            )

            val finalUri = pendingUri
            pendingUri = null
            finalUri
        } catch (e: CancellationException) {
            pendingUri?.let {
                try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            throw e
        } catch (e: Exception) {
            pendingUri?.let {
                try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            throw e
        }
    }
}
