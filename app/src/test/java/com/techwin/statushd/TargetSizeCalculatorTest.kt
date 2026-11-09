package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

class TargetSizeCalculatorTest {

    @Test
    fun `test 4000x3000 to 1440x1080`() {
        val result = TargetSizeCalculator.calculate(4000, 3000)
        assertEquals(1440, result.width)
        assertEquals(1080, result.height)
    }

    @Test
    fun `test 3000x4000 to 1080x1440`() {
        val result = TargetSizeCalculator.calculate(3000, 4000)
        assertEquals(1080, result.width)
        assertEquals(1440, result.height)
    }

    @Test
    fun `test 3000x3000 to 1080x1080`() {
        val result = TargetSizeCalculator.calculate(3000, 3000)
        assertEquals(1080, result.width)
        assertEquals(1080, result.height)
    }

    @Test
    fun `test 12000x3000 to 1920x480`() {
        val result = TargetSizeCalculator.calculate(12000, 3000)
        assertEquals(1920, result.width)
        assertEquals(480, result.height)
    }

    @Test
    fun `test 800x600 to 800x600`() {
        val result = TargetSizeCalculator.calculate(800, 600)
        assertEquals(800, result.width)
        assertEquals(600, result.height)
    }

    @Test
    fun `test odd dimensions`() {
        val result = TargetSizeCalculator.calculate(3001, 4001)
        assertTrue(result.width <= 1080)
        assertTrue(result.height <= 1920)
    }

    @Test
    fun `test invalid zero or negative dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetSizeCalculator.calculate(0, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetSizeCalculator.calculate(100, -50)
        }
    }

    @Test
    fun `test extremely large values within limit`() {
        val result = TargetSizeCalculator.calculate(10000, 10000)
        assertTrue(result.width <= 1080)
        assertTrue(result.height <= 1080)
    }

    @Test
    fun `test pixel limits enforcement`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetSizeCalculator.calculate(15000, 15000) // 225M pixels, exceeds 120M limit
        }

        val maxSafe = TargetSizeCalculator.calculate(10000, 10000)
        assertTrue(maxSafe.width.toLong() * maxSafe.height.toLong() <= TargetSizeCalculator.MAX_TARGET_PIXELS)
    }
}
