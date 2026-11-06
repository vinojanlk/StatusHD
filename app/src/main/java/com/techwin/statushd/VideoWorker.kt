package com.techwin.statushd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.io.File

class VideoWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildProcessingNotification(0)
        return createForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        return try {
            createNotificationChannel()
            val initialNotification = buildProcessingNotification(0)
            try {
                setForeground(createForegroundInfo(NOTIFICATION_ID, initialNotification))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set foreground state on startup", e)
            }

            val inputUriString = inputData.getString("inputUri")
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing input URI"))
            val durationMs = inputData.getLong("durationMs", 0L)
            val isPortrait = inputData.getBoolean("isPortrait", true)
            val modeName = inputData.getString("modeName") ?: "FULL_HD"
            val bitrateKbps = inputData.getInt("bitrateKbps", 3600)
            val splitTimeSec = inputData.getInt("splitTimeSec", 30)
            val preserveOriginal = inputData.getBoolean("preserveOriginal", false)

            val displayName = inputData.getString("displayName")
                ?: "HD_Status_${System.currentTimeMillis()}.mp4"

            val uri = Uri.parse(inputUriString)

            var lastNotificationTime = 0L
            var lastProgress = -1

            val result = VideoProcessor.processVideo(
                context, uri, durationMs, isPortrait, modeName, bitrateKbps, splitTimeSec, preserveOriginal
            ) { progress ->
                val now = System.currentTimeMillis()
                if (progress != lastProgress && (now - lastNotificationTime >= 300 || progress == 100)) {
                    lastProgress = progress
                    lastNotificationTime = now
                    setProgressAsync(workDataOf("progress" to progress))
                    try {
                        val notification = buildProcessingNotification(progress)
                        setForegroundAsync(createForegroundInfo(NOTIFICATION_ID, notification))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update notification", e)
                    }
                }
            }

            if (result.success && result.outputFiles.isNotEmpty()) {
                val savedUris = mutableListOf<String>()
                val isSplit = result.outputFiles.size > 1

                result.outputFiles.forEachIndexed { index, file ->
                    val baseName = displayName.substringBeforeLast(".")
                    val partName = if (isSplit) "${baseName}_part${index + 1}.mp4" else displayName

                    val savedUri = MediaStoreHelper.saveVideoToGallery(context, file, partName)
                    if (savedUri != null) {
                        savedUris.add(savedUri.toString())
                    }
                    if (file.exists()) file.delete()
                }

                if (savedUris.isNotEmpty()) {
                    Result.success(workDataOf("savedUris" to savedUris.toTypedArray()))
                } else {
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Failed to save to Gallery"))
                }
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (result.errorMsg ?: "Video processing failed")))
            }
        } catch (e: CancellationException) {
            cleanupTemporaryFiles()
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Video processing failed", t)
            cleanupTemporaryFiles()

            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to (t.message ?: "Video processing failed")
                )
            )
        }
    }

    private fun foregroundServiceType(): Int =
        when {
            Build.VERSION.SDK_INT >= 35 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING

            Build.VERSION.SDK_INT >= 29 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

            else -> 0
        }

    private fun createForegroundInfo(
        notificationId: Int,
        notification: Notification
    ): ForegroundInfo {
        require(notificationId > 0)

        val type = foregroundServiceType()

        Log.i(
            "StatusHD-FGS",
            "work=$id attempt=$runAttemptCount " +
                "sdk=${Build.VERSION.SDK_INT} " +
                "target=${applicationContext.applicationInfo.targetSdkVersion} " +
                "type=0x${Integer.toHexString(type)}"
        )

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                notificationId,
                notification,
                type
            )
        } else {
            ForegroundInfo(
                notificationId,
                notification
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Video Processing",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildProcessingNotification(progress: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Optimizing for WhatsApp")
            .setContentText("Processing... $progress%")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

    private fun cleanupTemporaryFiles() {
        try {
            val cacheDir = context.cacheDir
            File(cacheDir, "input_video.mp4").takeIf { it.exists() }?.delete()
            cacheDir.listFiles { _, name -> name.startsWith("output_") && name.endsWith(".mp4") }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup temporary files", e)
        }
    }

    companion object {
        private const val TAG = "VideoWorker"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "video_processing_channel"
        private const val KEY_ERROR_MESSAGE = "error"
    }
}
