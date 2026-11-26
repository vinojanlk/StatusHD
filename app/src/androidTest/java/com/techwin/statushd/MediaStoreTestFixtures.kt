package com.techwin.statushd

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID

data class MediaStoreRowMetadata(
    val uri: Uri,
    val displayName: String?,
    val mimeType: String?,
    val relativePath: String?,
    val isPending: Int?,
    val size: Long?
)

class MediaStoreTestFixtures(
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
) {
    val testRunPrefix: String = "test_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

    private val trackedUris = mutableSetOf<Uri>()

    /**
     * Copies an asset from the androidTest APK into a test-owned MediaStore image row.
     */
    fun seedAsset(
        assetName: String,
        mimeType: String = if (assetName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg",
        relativePath: String = "Pictures/TestSources/"
    ): Uri {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val displayName = "${testRunPrefix}_$assetName"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to insert MediaStore row for asset: $assetName")
        trackUri(uri)

        testContext.assets.open(assetName).use { input ->
            val outputStream = targetContext.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Failed to open OutputStream for seeded Uri: $uri")
            outputStream.use { output ->
                input.copyTo(output)
                output.flush()
            }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        val updated = targetContext.contentResolver.update(uri, values, null, null)
        if (updated != 1) {
            throw IllegalStateException("Failed to publish seeded MediaStore row: $uri")
        }

        return uri
    }

    /**
     * Seeds corrupt bytes into a test-owned MediaStore image row.
     */
    fun seedCorruptBytes(
        displayNameSuffix: String = "corrupt.jpg",
        bytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x12, 0x34, 0x56)
    ): Uri {
        val displayName = "${testRunPrefix}_$displayNameSuffix"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TestSources/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to insert MediaStore row for corrupt bytes")
        trackUri(uri)

        val outputStream = targetContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Failed to open OutputStream for corrupt Uri: $uri")
        outputStream.use { output ->
            output.write(bytes)
            output.flush()
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        targetContext.contentResolver.update(uri, values, null, null)
        return uri
    }

    fun trackUri(uri: Uri) {
        trackedUris.add(uri)
    }

    fun queryRowMetadata(uri: Uri): MediaStoreRowMetadata? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.SIZE
        )
        return targetContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                val isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                MediaStoreRowMetadata(
                    uri = uri,
                    displayName = displayName,
                    mimeType = mimeType,
                    relativePath = relativePath,
                    isPending = isPending,
                    size = size
                )
            } else null
        }
    }

    fun queryRowsByRelativePath(relativePath: String): List<MediaStoreRowMetadata> {
        val normalized = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(normalized, "$normalized%")

        val results = mutableListOf<MediaStoreRowMetadata>()
        targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                results.add(
                    MediaStoreRowMetadata(
                        uri = uri,
                        displayName = cursor.getString(nameCol),
                        mimeType = cursor.getString(mimeCol),
                        relativePath = cursor.getString(pathCol),
                        isPending = cursor.getInt(pendingCol),
                        size = cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return results
    }

    fun queryPublishedOutputsIn(relativePath: String): List<MediaStoreRowMetadata> {
        return queryRowsByRelativePath(relativePath).filter {
            it.isPending == 0 && (it.displayName?.startsWith("StatusHD_") == true)
        }
    }

    fun countPendingRows(selection: String? = null, selectionArgs: Array<String>? = null): Int {
        val baseSelection = "${MediaStore.Images.Media.IS_PENDING} = 1"
        val fullSelection = if (selection.isNullOrBlank()) baseSelection else "$baseSelection AND ($selection)"
        val projection = arrayOf(MediaStore.Images.Media._ID)
        return targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            fullSelection,
            selectionArgs,
            null
        )?.use { cursor -> cursor.count } ?: 0
    }

    fun reopenOutput(uri: Uri): Boolean {
        return try {
            targetContext.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                options.outWidth > 0 && options.outHeight > 0
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun decodeOutputBounds(uri: Uri): Pair<Int, Int>? {
        return try {
            targetContext.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    Pair(options.outWidth, options.outHeight)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cleanup() {
        val toDelete = trackedUris.toSet()
        for (uri in toDelete) {
            try {
                targetContext.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
        trackedUris.clear()

        // Also clean up any lingering row matching our unique test prefix
        try {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("${testRunPrefix}%")
            targetContext.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection,
                selectionArgs
            )
        } catch (_: Exception) {}
    }

    fun countLingeringTestPrefixRows(): Int {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("${testRunPrefix}%")
        val projection = arrayOf(MediaStore.Images.Media._ID)
        return targetContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor -> cursor.count } ?: 0
    }
}
