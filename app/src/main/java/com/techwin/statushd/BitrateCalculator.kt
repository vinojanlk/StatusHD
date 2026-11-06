package com.techwin.statushd

object BitrateCalculator {
    const val TARGET_BYTES = 14_500_000L
    const val AUDIO_BITRATE_KBPS = 96
    const val MIN_VIDEO_BITRATE_KBPS = 700
    const val MAX_VIDEO_BITRATE_KBPS = 2200

    fun calculateVideoBitrateKbps(durationSeconds: Double): Int {
        if (durationSeconds <= 0) return MAX_VIDEO_BITRATE_KBPS

        val safeTargetBytes = TARGET_BYTES * 0.95
        val videoBitrateKbps = ((safeTargetBytes * 8) / durationSeconds / 1000).toInt() - AUDIO_BITRATE_KBPS

        return videoBitrateKbps.coerceIn(MIN_VIDEO_BITRATE_KBPS, MAX_VIDEO_BITRATE_KBPS)
    }
}
