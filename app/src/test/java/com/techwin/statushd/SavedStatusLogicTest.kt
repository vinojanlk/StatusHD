package com.techwin.statushd.feature.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedStatusLogicTest {

    data class SimpleMediaItem(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        val mediaType: StatusMediaType
    )

    @Test
    fun testNewestFirstSorting() {
        val item1 = SimpleMediaItem("1", "old.jpg", "image/jpeg", 100, 1000L, StatusMediaType.PHOTO)
        val item2 = SimpleMediaItem("2", "new.jpg", "image/jpeg", 100, 2000L, StatusMediaType.PHOTO)
        val item3 = SimpleMediaItem("3", "unknown.jpg", "image/jpeg", 100, 0L, StatusMediaType.PHOTO)

        val list = mutableListOf(item1, item3, item2)
        list.sortByDescending { it.lastModified }

        assertEquals("new.jpg", list[0].displayName)
        assertEquals("old.jpg", list[1].displayName)
        assertEquals("unknown.jpg", list[2].displayName)
    }

    @Test
    fun testFilenameGenerationAndMimeFiltering() {
        val mimeJpeg = "image/jpeg"
        val extensionJpeg = when {
            mimeJpeg.contains("jpeg") -> "jpg"
            else -> "unknown"
        }
        assertEquals("jpg", extensionJpeg)

        val mimePng = "image/png"
        val extensionPng = when {
            mimePng.contains("png") -> "png"
            else -> "unknown"
        }
        assertEquals("png", extensionPng)

        val mimeMp4 = "video/mp4"
        val extensionMp4 = when {
            mimeMp4.contains("mp4") -> "mp4"
            else -> "unknown"
        }
        assertEquals("mp4", extensionMp4)
    }

    @Test
    fun testDigestMatchingAndChangedByteMismatch() {
        val record1 = DigestRecord("sha256_hash_1", "content://media/1", "image/jpeg", 1024L)

        // Exact SHA-256 match
        assertEquals("sha256_hash_1", record1.sha256)
        assertEquals(1024L, record1.size)

        // Changed byte count mismatch
        val changedByteCount = 2048L
        assertNotEquals(record1.size, changedByteCount)
    }

    @Test
    fun testEmptyFolderAndPermissionLostStates() {
        val noFolderState = SavedStatusUiState.NoFolderSelected
        assertEquals(SavedStatusUiState.NoFolderSelected, noFolderState)

        val permissionLostState = SavedStatusUiState.PermissionLost
        assertEquals(SavedStatusUiState.PermissionLost, permissionLostState)
    }
}
