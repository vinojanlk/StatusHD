package com.techwin.statushd.feature.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StatusSignatureValidatorTest {

    @Test
    fun testJpegSignatureValid() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
        assertTrue(StatusSignatureValidator.isJpeg(header, header.size))
        assertEquals(StatusMediaType.PHOTO, StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
        assertEquals("image/jpeg", StatusSignatureValidator.detectMimeType(header, header.size))
    }

    @Test
    fun testPngSignatureValid() {
        val header = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        assertTrue(StatusSignatureValidator.isPng(header, header.size))
        assertEquals(StatusMediaType.PHOTO, StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
        assertEquals("image/png", StatusSignatureValidator.detectMimeType(header, header.size))
    }

    @Test
    fun testWebpSignatureValid() {
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        )
        assertTrue(StatusSignatureValidator.isWebp(header, header.size))
        assertEquals(StatusMediaType.PHOTO, StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
        assertEquals("image/webp", StatusSignatureValidator.detectMimeType(header, header.size))
    }

    @Test
    fun testMp4FtypSignatureValid() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
        )
        assertTrue(StatusSignatureValidator.isMp4Ftyp(header, header.size))
        assertEquals(StatusMediaType.VIDEO, StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
        assertEquals("video/mp4", StatusSignatureValidator.detectMimeType(header, header.size))
    }

    @Test
    fun testInvalidSignature() {
        val header = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        assertFalse(StatusSignatureValidator.isJpeg(header, header.size))
        assertFalse(StatusSignatureValidator.isPng(header, header.size))
        assertFalse(StatusSignatureValidator.isWebp(header, header.size))
        assertFalse(StatusSignatureValidator.isMp4Ftyp(header, header.size))
        assertNull(StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
    }

    @Test
    fun testShortByteArray() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        assertFalse(StatusSignatureValidator.isJpeg(header, header.size))
        assertNull(StatusSignatureValidator.validateStreamHeader(ByteArrayInputStream(header)))
    }
}
