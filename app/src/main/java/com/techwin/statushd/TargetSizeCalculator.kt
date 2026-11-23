package com.techwin.statushd

import kotlin.math.floor
import kotlin.math.min

data class TargetSize(val width: Int, val height: Int)

object TargetSizeCalculator {
    fun calculate(sourceWidth: Int, sourceHeight: Int): TargetSize {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IllegalArgumentException("Source dimensions must be positive: ${sourceWidth}x${sourceHeight}")
        }
        if (sourceWidth > 32768 || sourceHeight > 32768) {
            throw IllegalArgumentException("Source edge exceeds 32,768 limit: ${sourceWidth}x${sourceHeight}")
        }
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (sourcePixels > 120_000_000L) {
            throw IllegalArgumentException("Source pixels exceed 120,000,000 limit: $sourcePixels")
        }

        val boxWidth = if (sourceWidth > sourceHeight) 1920 else 1080
        val boxHeight = if (sourceWidth > sourceHeight) 1080 else 1920

        val scale = min(
            1.0,
            min(
                boxWidth.toDouble() / sourceWidth,
                boxHeight.toDouble() / sourceHeight
            )
        )

        val targetWidth = maxOf(1, floor(sourceWidth * scale).toInt())
        val targetHeight = maxOf(1, floor(sourceHeight * scale).toInt())

        val outputPixels = targetWidth.toLong() * targetHeight.toLong()
        if (outputPixels > 2_073_600L) {
            throw IllegalStateException("Output pixels exceed 2,073,600 limit: $outputPixels")
        }

        return TargetSize(targetWidth, targetHeight)
    }
}
