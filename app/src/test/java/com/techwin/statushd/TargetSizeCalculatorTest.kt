package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeCalculatorTest {

    @Test
    fun testLandscape4000x3000() {
        val result = TargetSizeCalculator.calculate(4000, 3000)
        assertEquals(TargetSize(1440, 1080), result)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testPortrait3000x4000() {
        val result = TargetSizeCalculator.calculate(3000, 4000)
        assertEquals(TargetSize(1080, 1440), result)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testSquare3000x3000() {
        val result = TargetSizeCalculator.calculate(3000, 3000)
        assertEquals(TargetSize(1080, 1080), result)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testWideLandscape12000x3000() {
        val result = TargetSizeCalculator.calculate(12000, 3000)
        assertEquals(TargetSize(1920, 480), result)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testSmallNoUpscale800x600() {
        val result = TargetSizeCalculator.calculate(800, 600)
        assertEquals(TargetSize(800, 600), result)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testOddDimensions() {
        val result = TargetSizeCalculator.calculate(1921, 1081)
        assertTrue(result.width <= 1920)
        assertTrue(result.height <= 1080)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testLargeDimensionsWithoutOverflow() {
        val result = TargetSizeCalculator.calculate(10000, 10000)
        assertTrue(result.width <= 1920)
        assertTrue(result.height <= 1080)
        assertTrue(result.width.toLong() * result.height.toLong() <= 2_073_600L)
    }

    @Test
    fun testMaxEdgeLimit32768() {
        val result = TargetSizeCalculator.calculate(32768, 3000)
        assertTrue(result.width <= 1920)
        assertTrue(result.height <= 1080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testExceedsMaxEdge32769() {
        TargetSizeCalculator.calculate(32769, 1000)
    }

    @Test
    fun testMaxSourcePixelLimit120M() {
        val result = TargetSizeCalculator.calculate(12000, 10000) // 120,000,000 pixels
        assertTrue(result.width <= 1920)
        assertTrue(result.height <= 1080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testExceedsMaxSourcePixelLimit120M() {
        TargetSizeCalculator.calculate(12001, 10000) // 120,010,000 pixels
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidZeroWidth() {
        TargetSizeCalculator.calculate(0, 1080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidZeroHeight() {
        TargetSizeCalculator.calculate(1920, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidNegativeDimensions() {
        TargetSizeCalculator.calculate(-100, 100)
    }
}
