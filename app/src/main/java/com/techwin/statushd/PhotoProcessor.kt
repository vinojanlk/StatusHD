package com.techwin.statushd

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object PhotoProcessor {

    sealed class Result {
        data class Success(val uri: Uri) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun processPhoto(
        context: Context,
        inputUri: Uri,
        quality: Int
    ): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val mimeType = resolver.getType(inputUri)
        if (mimeType != "image/jpeg" && mimeType != "image/jpg") {
            return@withContext Result.Error("This version currently supports JPEG photos only.")
        }

        resolver.query(inputUri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIndex != -1) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > 50 * 1024 * 1024L) {
                        return@withContext Result.Error("Image exceeds 50 MiB limit.")
                    }
                }
            }
        }

        var decodedBitmap: Bitmap? = null
        try {
            val source = ImageDecoder.createSource(resolver, inputUri)
            decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (info.mimeType != "image/jpeg" && info.mimeType != "image/jpg") {
                    throw IllegalArgumentException("This version currently supports JPEG photos only.")
                }

                val targetSize = TargetSizeCalculator.calculate(info.size.width, info.size.height)
                decoder.setTargetSize(targetSize.width, targetSize.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.Error(mapExceptionToMessage(e))
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            return@withContext Result.Error("Not enough memory to process this image.")
        }

        if (decodedBitmap == null) {
            return@withContext Result.Error("The selected photo could not be read.")
        }

        if (Build.VERSION.SDK_INT >= 34) {
            if (decodedBitmap.hasGainmap()) {
                decodedBitmap.gainmap = null
            }
        }

        val finalBitmap = if (decodedBitmap.hasAlpha()) {
            val opaqueBitmap = Bitmap.createBitmap(decodedBitmap.width, decodedBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(opaqueBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(decodedBitmap, 0f, 0f, null)
            decodedBitmap.recycle()
            opaqueBitmap
        } else {
            decodedBitmap
        }

        val displayName = "StatusHD_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StatusHD")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext Result.Error("Saving to Gallery failed.")

        try {
            var compressed = false
            resolver.openOutputStream(outputUri)?.use { outStream ->
                compressed = finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            }

            if (!compressed) {
                resolver.delete(outputUri, null, null)
                return@withContext Result.Error("Photo processing failed.")
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(outputUri, values, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(outputUri, null, null)
            return@withContext Result.Error("Saving to Gallery failed.")
        } finally {
            finalBitmap.recycle()
        }

        Result.Success(outputUri)
    }

    private fun mapExceptionToMessage(e: Exception): String {
        return when (e) {
            is ImageDecoder.DecodeException -> "The selected photo could not be read."
            is SecurityException -> "The selected photo could not be read."
            is IOException -> "The selected photo could not be read."
            is IllegalArgumentException -> e.message ?: "The selected photo could not be read."
            else -> "Photo processing failed."
        }
    }
}
