package com.techwin.statushd.storage

import android.content.Context
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidAvailableStorageProvider(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AvailableStorageProvider {

    override suspend fun getAvailability(): StorageAvailability = withContext(ioDispatcher) {
        val internalBytes = queryStatFsAvailableBytes(context.cacheDir)

        // For shared media on Android 10+ (API 29+), MediaStore destinations (Pictures/HDStatus, Movies/HDStatus)
        // reside on the primary external storage volume.
        // The non-deprecated path representing this volume is context.getExternalFilesDir(null).
        // If unavailable, return null without falling back to deprecated Environment or internal filesDir.
        val sharedDir = context.getExternalFilesDir(null)
        val sharedBytes = queryStatFsAvailableBytes(sharedDir)

        StorageAvailability(
            internalCacheAvailableBytes = internalBytes,
            sharedMediaAvailableBytes = sharedBytes
        )
    }

    private fun queryStatFsAvailableBytes(directory: File?): Long? {
        if (directory == null) return null
        return try {
            val statFs = StatFs(directory.absolutePath)
            val bytes = statFs.availableBytes
            if (bytes >= 0L) bytes else null
        } catch (_: Exception) {
            null
        }
    }
}

class AndroidSourceSizeProvider(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SourceSizeProvider {

    override suspend fun querySizeBytes(uriString: String): Long? = withContext(ioDispatcher) {
        if (uriString.isBlank()) return@withContext null
        val uri = try {
            uriString.toUri()
        } catch (_: Exception) {
            return@withContext null
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "content") {
            return@withContext null
        }

        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex == -1 || cursor.isNull(sizeIndex)) {
                    null
                } else {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
