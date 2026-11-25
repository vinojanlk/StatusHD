package com.techwin.statushd.feature.status

import java.io.InputStream

object StatusSignatureValidator {

    fun isJpeg(header: ByteArray, length: Int): Boolean {
        if (length < 3) return false
        return (header[0].toInt() and 0xFF) == 0xFF &&
               (header[1].toInt() and 0xFF) == 0xD8 &&
               (header[2].toInt() and 0xFF) == 0xFF
    }

    fun isPng(header: ByteArray, length: Int): Boolean {
        if (length < 8) return false
        return (header[0].toInt() and 0xFF) == 0x89 &&
               (header[1].toInt() and 0xFF) == 0x50 &&
               (header[2].toInt() and 0xFF) == 0x4E &&
               (header[3].toInt() and 0xFF) == 0x47 &&
               (header[4].toInt() and 0xFF) == 0x0D &&
               (header[5].toInt() and 0xFF) == 0x0A &&
               (header[6].toInt() and 0xFF) == 0x1A &&
               (header[7].toInt() and 0xFF) == 0x0A
    }

    fun isWebp(header: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val isRiff = (header[0].toInt() and 0xFF) == 'R'.code &&
                     (header[1].toInt() and 0xFF) == 'I'.code &&
                     (header[2].toInt() and 0xFF) == 'F'.code &&
                     (header[3].toInt() and 0xFF) == 'F'.code
        val isWebp = (header[8].toInt() and 0xFF) == 'W'.code &&
                     (header[9].toInt() and 0xFF) == 'E'.code &&
                     (header[10].toInt() and 0xFF) == 'B'.code &&
                     (header[11].toInt() and 0xFF) == 'P'.code
        return isRiff && isWebp
    }

    fun isMp4Ftyp(header: ByteArray, length: Int): Boolean {
        if (length < 8) return false
        return (header[4].toInt() and 0xFF) == 'f'.code &&
               (header[5].toInt() and 0xFF) == 't'.code &&
               (header[6].toInt() and 0xFF) == 'y'.code &&
               (header[7].toInt() and 0xFF) == 'p'.code
    }

    fun validateStreamHeader(inputStream: InputStream): StatusMediaType? {
        val buffer = ByteArray(32)
        val bytesRead = inputStream.read(buffer, 0, 32)
        if (bytesRead < 8) return null

        return when {
            isJpeg(buffer, bytesRead) -> StatusMediaType.PHOTO
            isPng(buffer, bytesRead) -> StatusMediaType.PHOTO
            isWebp(buffer, bytesRead) -> StatusMediaType.PHOTO
            isMp4Ftyp(buffer, bytesRead) -> StatusMediaType.VIDEO
            else -> null
        }
    }

    fun detectMimeType(header: ByteArray, length: Int): String? {
        return when {
            isJpeg(header, length) -> "image/jpeg"
            isPng(header, length) -> "image/png"
            isWebp(header, length) -> "image/webp"
            isMp4Ftyp(header, length) -> "video/mp4"
            else -> null
        }
    }
}
