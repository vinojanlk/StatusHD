package com.techwin.statushd.storage

import com.techwin.statushd.PhotoQualityMode
import com.techwin.statushd.QualityMode
import com.techwin.statushd.TargetSizeCalculator
import com.techwin.statushd.VideoSharingHelper

data class PhotoEstimateInput(
    val sourceSizeBytes: Long?,
    val width: Int = 0,
    val height: Int = 0
)

data class VideoEstimateInput(
    val sourceSizeBytes: Long?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sourceBitrateKbps: Int,
    val isPortrait: Boolean,
    val mode: QualityMode
)

data class StorageEstimateBreakdown(
    val estimatedOutputBytes: Long,
    val temporaryBytes: Long,
    val fixedReserveBytes: Long,
    val totalRequiredBytes: Long
)

data class VolumeRequiredStorage(
    val internalCacheRequiredBytes: Long,
    val sharedMediaRequiredBytes: Long
)

object StorageEstimatePolicy {

    const val MIB = 1024L * 1024L
    const val KIB = 1024L

    /**
     * Fixed safety reserve added once per relevant volume to protect internal cache
     * and shared media storage against journal overhead and low-disk instability.
     */
    const val FIXED_RESERVE_BYTES: Long = 64L * MIB

    /**
     * Margin above required bytes within which storage is considered close to the limit
     * and triggers a non-blocking low-storage warning banner.
     */
    const val TIGHT_STORAGE_MARGIN_BYTES: Long = 64L * MIB

    // Photo policy constants
    const val PHOTO_QUALITY_90_MULTIPLIER_PERCENT: Long = 110L
    const val PHOTO_QUALITY_80_MULTIPLIER_PERCENT: Long = 75L
    const val PHOTO_FALLBACK_MAX_PIXELS: Long = 1080L * 1920L
    const val PHOTO_TRANSACTION_HEADROOM_BYTES: Long = 512L * KIB
    const val BATCH_HEADROOM_BYTES: Long = 2L * MIB
    const val MAX_BATCH_ITEMS: Int = 10

    // Video policy constants (aligned with VideoProcessor.AUDIO_BITRATE_KBPS)
    const val VIDEO_AUDIO_BITRATE_KBPS: Long = 128L
    const val VIDEO_PUBLICATION_HEADROOM_PER_SEGMENT_BYTES: Long = 2L * MIB
    const val VIDEO_ACTIVE_WRITE_HEADROOM_BYTES: Long = 4L * MIB

    fun estimateSinglePhoto(
        input: PhotoEstimateInput,
        mode: PhotoQualityMode
    ): VolumeRequiredStorage {
        val estimatedOutputBytes = estimatePhotoItemBytes(input, mode)
        val sharedRequired = safeAdd(estimatedOutputBytes, FIXED_RESERVE_BYTES)
        return VolumeRequiredStorage(
            internalCacheRequiredBytes = 0L,
            sharedMediaRequiredBytes = sharedRequired
        )
    }

    fun estimateBatchPhotos(
        items: List<PhotoEstimateInput>,
        mode: PhotoQualityMode
    ): VolumeRequiredStorage {
        val cappedItems = items.take(MAX_BATCH_ITEMS)
        var itemSumBytes = 0L
        for (item in cappedItems) {
            itemSumBytes = safeAdd(itemSumBytes, estimatePhotoItemBytes(item, mode))
        }
        val estimatedOutputBytes = if (cappedItems.isNotEmpty()) {
            safeAdd(itemSumBytes, BATCH_HEADROOM_BYTES)
        } else {
            0L
        }
        val sharedRequired = if (cappedItems.isNotEmpty()) {
            safeAdd(estimatedOutputBytes, FIXED_RESERVE_BYTES)
        } else {
            0L
        }
        return VolumeRequiredStorage(
            internalCacheRequiredBytes = 0L,
            sharedMediaRequiredBytes = sharedRequired
        )
    }

    fun isPreserveOriginal(input: VideoEstimateInput): Boolean {
        val maxTargetWidth = if (input.isPortrait) input.mode.maxW else input.mode.maxH
        val maxTargetHeight = if (input.isPortrait) input.mode.maxH else input.mode.maxW
        return input.durationMs <= input.mode.splitTime * 1000L &&
            input.sourceBitrateKbps > 0 &&
            input.sourceBitrateKbps <= input.mode.bitRateKbps &&
            input.width <= maxTargetWidth &&
            input.height <= maxTargetHeight
    }

    fun estimateVideo(input: VideoEstimateInput): VolumeRequiredStorage {
        val preserveOriginal = isPreserveOriginal(input)
        val durationMs = input.durationMs.coerceAtLeast(0L)

        if (preserveOriginal) {
            val sourceBytes = resolveVideoSourceBytes(
                sourceSizeBytes = input.sourceSizeBytes,
                sourceBitrateKbps = input.sourceBitrateKbps,
                durationMs = durationMs,
                fallbackBytes = 0L
            )
            // Preserve Original pipeline copies source video to cacheDir/input_video.mp4
            // and FFmpeg streams/copies to cacheDir/output_%03d.mp4 simultaneously.
            val internalPeakBytes = safeAdd(
                safeAdd(sourceBytes, sourceBytes),
                safeAdd(VIDEO_ACTIVE_WRITE_HEADROOM_BYTES, FIXED_RESERVE_BYTES)
            )

            // MediaStore destination receives the final published output segments plus publication headroom
            val sharedRequiredBytes = safeAdd(
                safeAdd(sourceBytes, VIDEO_PUBLICATION_HEADROOM_PER_SEGMENT_BYTES),
                FIXED_RESERVE_BYTES
            )

            return VolumeRequiredStorage(
                internalCacheRequiredBytes = internalPeakBytes,
                sharedMediaRequiredBytes = sharedRequiredBytes
            )
        }

        val segmentCount = VideoSharingHelper.calculatePartCount(durationMs, input.mode.splitTime)
            .coerceAtLeast(1)
        val totalBitrateKbps = safeAdd(input.mode.bitRateKbps.toLong(), VIDEO_AUDIO_BITRATE_KBPS)
        val encodedMediaBytes = safeRateBytesForDurationMs(totalBitrateKbps, durationMs)

        val publicationHeadroom = safeMultiply(
            segmentCount.toLong(),
            VIDEO_PUBLICATION_HEADROOM_PER_SEGMENT_BYTES
        )
        val sharedOutputBytes = safeAdd(encodedMediaBytes, publicationHeadroom)
        val sharedRequiredBytes = safeAdd(sharedOutputBytes, FIXED_RESERVE_BYTES)

        val sourceCacheBytes = resolveVideoSourceBytes(
            sourceSizeBytes = input.sourceSizeBytes,
            sourceBitrateKbps = input.sourceBitrateKbps,
            durationMs = durationMs,
            fallbackBytes = encodedMediaBytes
        )

        // Internal peak: VideoProcessor retains input_video.mp4 while ALL output_%03d.mp4 segments
        // are written and coexist in context.cacheDir until FFmpeg finishes.
        val internalPeakBytes = safeAdd(
            safeAdd(sourceCacheBytes, encodedMediaBytes),
            safeAdd(VIDEO_ACTIVE_WRITE_HEADROOM_BYTES, FIXED_RESERVE_BYTES)
        )

        return VolumeRequiredStorage(
            internalCacheRequiredBytes = internalPeakBytes,
            sharedMediaRequiredBytes = sharedRequiredBytes
        )
    }

    internal fun estimatePhotoItemBytes(
        input: PhotoEstimateInput,
        mode: PhotoQualityMode
    ): Long {
        val multiplierPercent = when (mode) {
            PhotoQualityMode.STATUS_HD -> PHOTO_QUALITY_90_MULTIPLIER_PERCENT
            PhotoQualityMode.SMALLER_FILE -> PHOTO_QUALITY_80_MULTIPLIER_PERCENT
        }

        val sourceSize = input.sourceSizeBytes
        val estimatedCompressedBytes = when {
            sourceSize != null && sourceSize == 0L -> 0L
            sourceSize != null && sourceSize > 0L -> safeScalePercent(sourceSize, multiplierPercent)
            else -> {
                val targetPixels = resolveBoundedTargetPixels(input.width, input.height)
                val fallbackBaseBytes = (targetPixels * 3L) / 2L
                safeScalePercent(fallbackBaseBytes, multiplierPercent)
            }
        }

        return safeAdd(estimatedCompressedBytes, PHOTO_TRANSACTION_HEADROOM_BYTES)
    }

    private fun resolveBoundedTargetPixels(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) {
            return PHOTO_FALLBACK_MAX_PIXELS
        }
        return try {
            val target = TargetSizeCalculator.calculate(width, height)
            (target.width.toLong() * target.height.toLong()).coerceIn(1L, PHOTO_FALLBACK_MAX_PIXELS)
        } catch (_: Exception) {
            PHOTO_FALLBACK_MAX_PIXELS
        }
    }

    private fun resolveVideoSourceBytes(
        sourceSizeBytes: Long?,
        sourceBitrateKbps: Int,
        durationMs: Long,
        fallbackBytes: Long
    ): Long {
        if (sourceSizeBytes != null && sourceSizeBytes >= 0L) {
            return sourceSizeBytes
        }
        if (sourceBitrateKbps > 0 && durationMs > 0L) {
            return safeRateBytesForDurationMs(sourceBitrateKbps.toLong(), durationMs)
        }
        return fallbackBytes
    }

    fun safeAdd(a: Long, b: Long): Long {
        if (a <= 0L) return b.coerceAtLeast(0L)
        if (b <= 0L) return a
        return if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }

    fun safeMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }

    fun safeScalePercent(value: Long, percent: Long): Long {
        if (value <= 0L || percent <= 0L) return 0L
        if (value <= Long.MAX_VALUE / percent) {
            return (value * percent) / 100L
        }
        val quotient = value / 100L
        val remainder = value % 100L
        val mainPart = safeMultiply(quotient, percent)
        val remainderPart = safeMultiply(remainder, percent) / 100L
        return safeAdd(mainPart, remainderPart)
    }

    fun safeRateBytesForDurationMs(totalKbps: Long, durationMs: Long): Long {
        if (totalKbps <= 0L || durationMs <= 0L) return 0L
        val quotient = durationMs / 8L
        val remainder = durationMs % 8L
        val mainBytes = safeMultiply(totalKbps, quotient)
        val remainderBytes = safeMultiply(totalKbps, remainder) / 8L
        return safeAdd(mainBytes, remainderBytes)
    }
}
