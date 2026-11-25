package com.techwin.statushd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidPhotoPreparerTest {

    @Test
    fun testBlankSourceIdentifierRejected() {
        fun validateScheme(sourceUri: String): PhotoProcessingFailure? {
            if (sourceUri.isBlank()) return PhotoProcessingFailure.SourceUnavailable
            val scheme = sourceUri.substringBefore("://", "")
            if (!scheme.equals("content", ignoreCase = true)) {
                return PhotoProcessingFailure.SourceUnavailable
            }
            return null
        }

        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme(""))
        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme("   "))
    }

    @Test
    fun testNonContentUriRejected() {
        fun validateScheme(sourceUri: String): PhotoProcessingFailure? {
            if (sourceUri.isBlank()) return PhotoProcessingFailure.SourceUnavailable
            val scheme = sourceUri.substringBefore("://", "")
            if (!scheme.equals("content", ignoreCase = true)) {
                return PhotoProcessingFailure.SourceUnavailable
            }
            return null
        }

        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme("file:///sdcard/photo.jpg"))
        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme("https://example.com/photo.jpg"))
        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme("http://example.com/photo.jpg"))
        assertEquals(PhotoProcessingFailure.SourceUnavailable, validateScheme("/sdcard/photo.jpg"))
        assertNull(validateScheme("content://media/external/images/media/42"))
    }
}
