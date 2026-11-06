package com.techwin.statushd

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object VideoProcessor {
    private const val TAG = "VideoProcessor"
    private const val AUDIO_BITRATE_KBPS = 128

    data class ProcessingResult(val success: Boolean, val outputFiles: List<File>, val errorMsg: String?)

    suspend fun processVideo(
        context: Context,
        inputUri: Uri,
        durationMs: Long,
        isPortrait: Boolean,
        modeName: String,
        bitrateKbps: Int,
        splitTimeSec: Int,
        preserveOriginal: Boolean,
        progressCallback: (Int) -> Unit
    ): ProcessingResult = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val inputFile = File(cacheDir, "input_video.mp4")

        // Clean up old outputs
        cacheDir.listFiles { _, name -> name.startsWith("output_") && name.endsWith(".mp4") }?.forEach { it.delete() }

        try {
            if (inputFile.exists()) inputFile.delete()
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                inputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ProcessingResult(false, emptyList(), "Failed to read input video.")

            val durationSeconds = durationMs / 1000.0
            if (durationSeconds <= 0) {
                return@withContext ProcessingResult(false, emptyList(), "Invalid video duration.")
            }

            val outPattern = File(cacheDir, "output_%03d.mp4").absolutePath
            val command = mutableListOf("-y", "-i", inputFile.absolutePath)

            if (preserveOriginal) {
                command.addAll(arrayOf("-c", "copy"))
            } else {
                val maxRate = (bitrateKbps * 1.1).toInt()
                val bufSize = bitrateKbps * 2
                val scaleFilter = if (modeName == "FULL_HD") {
                    if (isPortrait) "1080:1920" else "1920:1080"
                } else {
                    if (isPortrait) "720:1280" else "1280:720"
                }
                val filterChain = "scale=$scaleFilter:force_original_aspect_ratio=decrease,pad=$scaleFilter:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p"

                command.addAll(arrayOf(
                    "-map", "0:v:0",
                    "-map", "0:a:0?",
                    "-vf", filterChain,
                    "-c:v", "libx264",
                    "-preset", "medium",
                    "-profile:v", "high",
                    "-b:v", "${bitrateKbps}k",
                    "-maxrate", "${maxRate}k",
                    "-bufsize", "${bufSize}k",
                    "-g", "60",
                    "-c:a", "aac",
                    "-profile:a", "aac_low",
                    "-b:a", "${AUDIO_BITRATE_KBPS}k",
                    "-ar", "48000",
                    "-ac", "2"
                ))
            }

            command.addAll(arrayOf(
                "-f", "segment",
                "-segment_time", splitTimeSec.toString(),
                "-reset_timestamps", "1",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-movflags", "+faststart",
                "-avoid_negative_ts", "make_zero",
                "-threads", "2",
                outPattern
            ))

            var lastProgress = 0
            return@withContext suspendCancellableCoroutine<ProcessingResult> { continuation ->
                val session = FFmpegKit.executeWithArgumentsAsync(command.toTypedArray(), { session ->
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        val outputs = cacheDir.listFiles { _, name -> name.startsWith("output_") && name.endsWith(".mp4") }?.sorted()?.toList() ?: emptyList()
                        continuation.resume(ProcessingResult(true, outputs, null))
                    } else if (ReturnCode.isCancel(returnCode)) {
                        continuation.resume(ProcessingResult(false, emptyList(), "Cancelled"))
                    } else {
                        continuation.resume(ProcessingResult(false, emptyList(), "FFmpeg Error: ${session.failStackTrace}"))
                    }
                }, { log ->
                    Log.d(TAG, "FFmpeg Log: ${log.message}")
                }, { statistics ->
                    val timeInMilliseconds = statistics.time.toLong()
                    if (timeInMilliseconds > 0) {
                        val progress = ((timeInMilliseconds.toDouble() / durationMs.toDouble()) * 100).toInt()
                        val clampedProgress = progress.coerceIn(0, 99)
                        if (clampedProgress > lastProgress) {
                            lastProgress = clampedProgress
                            progressCallback(clampedProgress)
                        }
                    }
                })

                continuation.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext ProcessingResult(false, emptyList(), e.message ?: "Unknown error")
        } finally {
            if (inputFile.exists()) inputFile.delete()
        }
    }
}
