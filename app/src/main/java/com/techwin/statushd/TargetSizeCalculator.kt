package com.techwin.statushd

import kotlin.math.floor

object TargetSizeCalculator {
    const val MAX_SOURCE_DIMENSION = 32768
    const val MAX_SOURCE_PIXELS = 120_000_000L
    const val MAX_TARGET_PIXELS = 2_073_600L // 1920 * 1080 = 2073600

    data class TargetSize(val width: Int, val height: Int)

    fun calculate(sourceWidth: Int, sourceHeight: Int): TargetSize {
        require(sourceWidth > 0 && sourceHeight > 0) { "Dimensions must be positive" }
        require(sourceWidth <= MAX_SOURCE_DIMENSION && sourceHeight <= MAX_SOURCE_DIMENSION) { "Image exceeds maximum dimension limit" }

        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        require(sourcePixels <= MAX_SOURCE_PIXELS) { "Image exceeds maximum pixel limit" }

        val boxWidth = if (sourceWidth > sourceHeight) 1920 else 1080
        val boxHeight = if (sourceWidth > sourceHeight) 1080 else 1920

        val scale = minOf(
            1.0,
            boxWidth.toDouble() / sourceWidth.toDouble(),
            boxHeight.toDouble() / sourceHeight.toDouble()
        )

        val targetWidth = maxOf(1, floor(sourceWidth * scale).toInt())
        val targetHeight = maxOf(1, floor(sourceHeight * scale).toInt())

        val targetPixels = targetWidth.toLong() * targetHeight.toLong()
        require(targetPixels <= MAX_TARGET_PIXELS) { "Target exceeds maximum pixel limit" }

        return TargetSize(targetWidth, targetHeight)
    }
}
