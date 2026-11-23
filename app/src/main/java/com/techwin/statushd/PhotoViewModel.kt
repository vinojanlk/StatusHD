package com.techwin.statushd

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface PhotoUiState {
    object Idle : PhotoUiState
    data class Selected(
        val uri: Uri,
        val fileName: String,
        val inputWidth: Int,
        val inputHeight: Int,
        val inputSize: Long,
        val mode: PhotoQualityMode = PhotoQualityMode.STATUS_HD
    ) : PhotoUiState
    data class Processing(val progress: Int) : PhotoUiState
    object Saving : PhotoUiState
    object Canceling : PhotoUiState
    data class Saved(
        val outputUri: Uri,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputSize: Long
    ) : PhotoUiState
    data class Error(val message: String) : PhotoUiState
}

enum class PhotoQualityMode(val title: String, val desc: String, val quality: Int) {
    STATUS_HD("Status HD", "Fit within 1080x1920 (Portrait/Square) or 1920x1080 (Landscape), Quality 90", 90),
    SMALLER_FILE("Smaller File", "Same dimensions, Quality 80", 80)
}

class PhotoViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PhotoUiState>(PhotoUiState.Idle)
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null
    private var lastSelectedState: PhotoUiState.Selected? = null

    fun selectPhoto(uri: Uri) {
        if (currentJob?.isActive == true) return

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver

                // Validate MIME type via ContentResolver
                val mimeType = contentResolver.getType(uri)
                if (mimeType != null && !mimeType.equals("image/jpeg", ignoreCase = true)) {
                    _uiState.value = PhotoUiState.Error("This version currently supports JPEG photos only.")
                    return@launch
                }

                // Query file name and size metadata
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

                // File size limit check (<= 50 MiB)
                if (fileSize > 50L * 1024L * 1024L) {
                    _uiState.value = PhotoUiState.Error("Image file size exceeds 50 MiB limit.")
                    return@launch
                }

                // Bounded metadata inspection via ImageDecoder with 1x1 target size to prevent full bitmap allocation
                val source = ImageDecoder.createSource(contentResolver, uri)
                var rawW = 0
                var rawH = 0
                var headerMime: String? = null

                val dummyBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    headerMime = info.mimeType
                    rawW = info.size.width
                    rawH = info.size.height
                    decoder.setTargetSize(1, 1) // Decodes only 1x1 pixel (4 bytes)
                }
                dummyBitmap.recycle()

                if (headerMime != null && !headerMime.equals("image/jpeg", ignoreCase = true)) {
                    _uiState.value = PhotoUiState.Error("This version currently supports JPEG photos only.")
                    return@launch
                }

                if (rawW <= 0 || rawH <= 0) {
                    _uiState.value = PhotoUiState.Error("Source file unavailable or unreadable.")
                    return@launch
                }

                val sourcePixels = rawW.toLong() * rawH.toLong()
                if (rawW > 32768 || rawH > 32768 || sourcePixels > 120_000_000L) {
                    _uiState.value = PhotoUiState.Error("Image dimensions exceed supported limit.")
                    return@launch
                }

                val selectedState = PhotoUiState.Selected(
                    uri = uri,
                    fileName = fileName,
                    inputWidth = rawW,
                    inputHeight = rawH,
                    inputSize = fileSize
                )
                lastSelectedState = selectedState
                _uiState.value = selectedState
            } catch (e: OutOfMemoryError) {
                Log.e("PhotoViewModel", "OOM during selectPhoto", e)
                _uiState.value = PhotoUiState.Error("Not enough memory to process image.")
            } catch (e: Exception) {
                Log.e("PhotoViewModel", "Error in selectPhoto", e)
                val userMsg = if (e is IllegalArgumentException && e.message?.contains("JPEG") == true) {
                    "This version currently supports JPEG photos only."
                } else {
                    "Source file unavailable or unreadable."
                }
                _uiState.value = PhotoUiState.Error(userMsg)
            }
        }
    }

    fun setMode(mode: PhotoQualityMode) {
        if (currentJob?.isActive == true) return
        val currentState = _uiState.value
        if (currentState is PhotoUiState.Selected) {
            val updated = currentState.copy(mode = mode)
            lastSelectedState = updated
            _uiState.value = updated
        }
    }

    fun startProcessing() {
        val currentState = _uiState.value
        if (currentState !is PhotoUiState.Selected) return

        if (currentJob?.isActive == true) return

        val uri = currentState.uri
        val mode = currentState.mode
        val context = getApplication<Application>()

        val job = viewModelScope.launch(Dispatchers.IO) {
            var pendingUri: Uri? = null
            var decodedBitmap: Bitmap? = null
            try {
                _uiState.value = PhotoUiState.Processing(10)

                val contentResolver = context.contentResolver
                val source = ImageDecoder.createSource(contentResolver, uri)

                var targetW = 0
                var targetH = 0

                _uiState.value = PhotoUiState.Processing(40)

                decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    if (info.mimeType != null && !info.mimeType.equals("image/jpeg", ignoreCase = true)) {
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

                _uiState.value = PhotoUiState.Saving

                // Insert into MediaStore
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val displayName = "StatusHD_${timeStamp}.jpg"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StatusHD")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                pendingUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("MediaStore insert returned null")

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

                // Publish MediaStore entry
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                val updated = contentResolver.update(pendingUri, contentValues, null, null)

                if (updated != 1) {
                    Log.e("PhotoViewModel", "MediaStore publication returned 0 rows updated")
                    throw IOException("Failed to publish image in MediaStore (updated=$updated)")
                }

                _uiState.value = PhotoUiState.Saved(
                    outputUri = pendingUri,
                    outputWidth = targetW,
                    outputHeight = targetH,
                    outputSize = savedSize
                )
                pendingUri = null // Successfully published
            } catch (e: CancellationException) {
                pendingUri?.let {
                    try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e("PhotoViewModel", "OOM in photo processing", e)
                pendingUri?.let {
                    try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                _uiState.value = PhotoUiState.Error("Not enough memory to process image.")
            } catch (e: Exception) {
                Log.e("PhotoViewModel", "Photo processing failed", e)
                pendingUri?.let {
                    try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {}
                }
                val userMsg = when {
                    e is IllegalArgumentException && e.message?.contains("JPEG") == true ->
                        "This version currently supports JPEG photos only."
                    e is IllegalArgumentException && (e.message?.contains("limit") == true || e.message?.contains("dimensions") == true) ->
                        "Image dimensions exceed supported limit."
                    e is java.io.FileNotFoundException ->
                        "Source file unavailable or unreadable."
                    e is IOException ->
                        "Failed to save photo to MediaStore."
                    else ->
                        "Photo processing failed."
                }
                _uiState.value = PhotoUiState.Error(userMsg)
            } finally {
                decodedBitmap?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }
                if (currentJob == coroutineContext[Job]) {
                    currentJob = null
                }
            }
        }
        currentJob = job
    }

    fun cancelProcessing() {
        val jobToCancel = currentJob ?: return
        if (!jobToCancel.isActive) return

        val currentState = _uiState.value
        _uiState.value = PhotoStateReducer.onCancelRequested(currentState)

        val previousSelected = lastSelectedState

        viewModelScope.launch {
            try {
                jobToCancel.cancelAndJoin()
            } finally {
                if (currentJob == jobToCancel) {
                    currentJob = null
                }
                _uiState.value = PhotoStateReducer.onCancelCompleted(previousSelected)
            }
        }
    }

    fun reset() {
        if (currentJob?.isActive == true) return
        _uiState.value = PhotoUiState.Idle
        lastSelectedState = null
    }

    fun clearError() {
        val currentState = _uiState.value
        if (currentState is PhotoUiState.Error) {
            _uiState.value = PhotoUiState.Idle
        }
    }
}
