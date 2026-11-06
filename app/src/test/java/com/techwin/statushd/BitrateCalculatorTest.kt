package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Test

class BitrateCalculatorTest {

    @Test
    fun `test bitrate clamping for short videos`() {
        val bitrate = BitrateCalculator.calculateVideoBitrateKbps(1.0)
        assertEquals(BitrateCalculator.MAX_VIDEO_BITRATE_KBPS, bitrate)
    }

    @Test
    fun `test bitrate clamping for long videos`() {
        val bitrate = BitrateCalculator.calculateVideoBitrateKbps(300.0)
        assertEquals(BitrateCalculator.MIN_VIDEO_BITRATE_KBPS, bitrate)
    }

    @Test
    fun `test normal bitrate calculation`() {
        val bitrate = BitrateCalculator.calculateVideoBitrateKbps(30.0)
        assertEquals(BitrateCalculator.MAX_VIDEO_BITRATE_KBPS, bitrate)

        val bitrate60 = BitrateCalculator.calculateVideoBitrateKbps(60.0)
        assertEquals(1740, bitrate60)
    }
}
