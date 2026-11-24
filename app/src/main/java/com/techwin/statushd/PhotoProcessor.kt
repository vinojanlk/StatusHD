package com.techwin.statushd

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface PhotoInspectionResult {
    data class Success(
        val uri: Uri,
        val fileName: String,
        val width: Int,
        val height: Int,
        val size: Long
    ) : PhotoInspectionResult

    data class Failure(val failure: PhotoProcessingFailure) : PhotoInspectionResult
}

class PhotoProcessor(private val context: Context) {

    suspend fun inspectSource(uri: Uri): PhotoInspectionResult = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver

        val mimeType = contentResolver.getType(uri)
        if (mimeType != null && !mimeType.equals("image/jpeg", ignoreCase = true)) {
            return@withContext PhotoInspectionResult.Failure(PhotoProcessingFailure.UnsupportedFormat)
        }

        var fileName = "photo.jpg"
        var fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) cursor.getString(nameIndex)?.let { fileName = it }
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        if (fileSize > 50L * 1024L * 1024L) {
            return@withContext PhotoInspectionResult.Failure(PhotoProcessingFailure.SourceTooLarge)
        }

        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            var rawW = 0
            var rawH = 0
            var headerMime: String? = null

            // Bounded metadata probe via ImageDecoder with 1x1 target size to minimize the returned Bitmap allocation.
            // Note: setTargetSize(1, 1) bounds the pixel memory of the returned Bitmap object, but native decoders
            // may still allocate internal scanline buffers during header parsing.
            val dummyBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                headerMime = info.mimeType
                rawW = info.size.width
                rawH = info.size.height
                decoder.setTargetSize(1, 1)
            }
            dummyBitmap.recycle()

            if (headerMime != null && !headerMime.equals("image/jpeg", ignoreCase = true)) {
                return@withContext PhotoInspectionResult.Failure(PhotoProcessingFailure.UnsupportedFormat)
            }

            if (rawW <= 0 || rawH <= 0) {
                return@withContext PhotoInspectionResult.Failure(PhotoProcessingFailure.SourceUnavailable)
            }

            val sourcePixels = rawW.toLong() * rawH.toLong()
            if (rawW > 32768 || rawH > 32768 || sourcePixels > 120_000_000L) {
                return@withContext PhotoInspectionResult.Failure(PhotoProcessingFailure.SourceTooLarge)
            }

            PhotoInspectionResult.Success(
                uri = uri,
                fileName = fileName,
                width = rawW,
                height = rawH,
                size = fileSize
            )
        } catch (e: OutOfMemoryError) {
            Log.e("PhotoProcessor", "OOM during inspection", e)
            PhotoInspectionResult.Failure(PhotoProcessingFailure.OutOfMemory)
        } catch (e: Exception) {
            Log.e("PhotoProcessor", "Error inspecting source photo", e)
            val failure = if (e is IllegalArgumentException && e.message?.contains("JPEG") == true) {
                PhotoProcessingFailure.UnsupportedFormat
            } else {
                PhotoProcessingFailure.SourceUnavailable
            }
            PhotoInspectionResult.Failure(failure)
        }
    }

    suspend fun preparePhoto(
        sourceUri: Uri,
        mode: PhotoQualityMode,
        destination: PhotoDestination = PhotoDestination.Single,
        onProgress: (Int) -> Unit = {}
    ): PhotoProcessingResult = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        var decodedBitmap: Bitmap? = null
        val contentResolver = context.contentResolver

        try {
            onProgress(10)

            val source = ImageDecoder.createSource(contentResolver, sourceUri)

            var targetW = 0
            var targetH = 0

            onProgress(40)

            decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (!info.mimeType.equals("image/jpeg", ignoreCase = true)) {
                    throw IllegalArgumentException("This version currently supports JPEG photos only.")
                }
                val srcW = info.size.width
                val srcH = info.size.height

                val targetSize = TargetSizeCalculator.calculate(srcW, srcH)
                targetW = targetSize.width
                targetH = targetSize.height

                decoder.setTargetSize(targetW, targetH)
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                decodedBitmap.setGainmap(null)
            }

            onProgress(70)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val displayName = if (destination == PhotoDestination.Batch) {
                "StatusHD_Batch_${timeStamp}.jpg"
            } else {
                "StatusHD_${timeStamp}.jpg"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, destination.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            pendingUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext PhotoProcessingResult.Failure(PhotoProcessingFailure.SaveFailed)

            var savedSize = 0L
            val outputStream = contentResolver.openOutputStream(pendingUri)
                ?: throw IOException("Failed to open MediaStore output stream")

            outputStream.use { stream ->
                val success = decodedBitmap.compress(Bitmap.CompressFormat.JPEG, mode.quality, stream)
                stream.flush()
                if (!success) {
                    throw IOException("Failed to compress bitmap to JPEG format")
                }
            }

            contentResolver.openAssetFileDescriptor(pendingUri, "r")?.use { afd ->
                savedSize = afd.length
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            val updated = contentResolver.update(pendingUri, contentValues, null, null)

            if (updated != 1) {
                Log.e("PhotoProcessor", "MediaStore publication returned 0 rows updated")
                throw IOException("Failed to publish image in MediaStore (updated=$updated)")
            }

            val resultUri = pendingUri
            pendingUri = null

            PhotoProcessingResult.Success(
                outputUri = resultUri,
                outputWidth = targetW,
                outputHeight = targetH,
                outputSize = savedSize
            )
        } catch (e: CancellationException) {
            pendingUri?.let {
                try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("PhotoProcessor", "OOM in photo processing", e)
            pendingUri?.let {
                try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            PhotoProcessingResult.Failure(PhotoProcessingFailure.OutOfMemory)
        } catch (e: Exception) {
            Log.e("PhotoProcessor", "Photo processing failed", e)
            pendingUri?.let {
                try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            val failure = when {
                e is IllegalArgumentException && e.message?.contains("JPEG") == true ->
                    PhotoProcessingFailure.UnsupportedFormat
                e is IllegalArgumentException && (e.message?.contains("limit") == true || e.message?.contains("dimensions") == true) ->
                    PhotoProcessingFailure.SourceTooLarge
                e is java.io.FileNotFoundException ->
                    PhotoProcessingFailure.SourceUnavailable
                e is IOException ->
                    PhotoProcessingFailure.SaveFailed
                else ->
                    PhotoProcessingFailure.ProcessingFailed(e.message)
            }
            PhotoProcessingResult.Failure(failure)
        } finally {
            decodedBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
        }
    }
}
