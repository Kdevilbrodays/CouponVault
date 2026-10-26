package com.example.couponvault.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UriConversionAndVerificationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testDirectMediaStoreUriIsPreserved() {
        val mediaStoreUri = Uri.parse("content://media/external/images/media/12345")
        val converted = StorageHelper.convertToMediaStoreUri(context, mediaStoreUri)
        assertNotNull(converted)
        assertEquals(mediaStoreUri, converted)
    }

    @Test
    fun testMediaAuthorityUriIsPreserved() {
        val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 777L)
        val converted = StorageHelper.convertToMediaStoreUri(context, mediaUri)
        assertNotNull(converted)
        assertEquals(mediaUri, converted)
    }

    @Test
    fun testDocumentProviderUriWithImagePrefixConvertsToMediaStoreUri() {
        val docUri = Uri.parse("content://com.android.providers.media.documents/document/image%3A54321")
        val converted = StorageHelper.convertToMediaStoreUri(context, docUri)
        assertNotNull(converted)
        val expected = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 54321L)
        assertEquals(expected, converted)
    }

    @Test
    fun testDocumentProviderUriWithNumericIdConvertsSuccessfully() {
        val docUri = Uri.parse("content://com.android.providers.media.documents/document/99999")
        val converted = StorageHelper.convertToMediaStoreUri(context, docUri)
        assertNotNull(converted)
        val expected = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 99999L)
        assertEquals(expected, converted)
    }

    @Test
    fun testUnrecognizedOrExternalProviderReturnsNull() {
        val foreignUri = Uri.parse("content://com.google.android.apps.photos.contentprovider/1/image")
        val converted = StorageHelper.convertToMediaStoreUri(context, foreignUri)
        assertNull(converted)

        val invalidDocUri = Uri.parse("content://com.android.providers.media.documents/document/raw%3Anot_a_number")
        val invalidConverted = StorageHelper.convertToMediaStoreUri(context, invalidDocUri)
        assertNull(invalidConverted)
    }

    @Test
    fun testVerifyCopySuccessWhenSizeMatches() {
        val tempFile = File(context.cacheDir, "test_verify_success.jpg")
        tempFile.writeBytes(ByteArray(1024) { 0x55.toByte() })
        try {
            assertTrue(tempFile.exists())
            assertEquals(1024L, tempFile.length())
            assertTrue("Copy should be verified when file exists and size matches", StorageHelper.verifyCopy(tempFile, 1024L))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testVerifyCopyFailsWhenSizeMismatched() {
        val tempFile = File(context.cacheDir, "test_verify_mismatch.jpg")
        tempFile.writeBytes(ByteArray(500) { 0x11.toByte() })
        try {
            assertFalse("Copy verification must fail when size differs from source", StorageHelper.verifyCopy(tempFile, 1000L))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testVerifyCopyFailsWhenFileMissingOrEmpty() {
        val missingFile = File(context.cacheDir, "non_existent_file.jpg")
        assertFalse("Missing file must not be verified", StorageHelper.verifyCopy(missingFile, 100L))

        val emptyFile = File(context.cacheDir, "empty_file.jpg")
        emptyFile.createNewFile()
        try {
            assertEquals(0L, emptyFile.length())
            assertFalse("Empty 0-byte file must not be verified", StorageHelper.verifyCopy(emptyFile, 0L))
            assertFalse("Empty 0-byte file must not be verified even if expected is null", StorageHelper.verifyCopy(emptyFile, null))
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun testUniqueUuidFilenamesDoNotCollide() {
        val files = (1..50).map { StorageHelper.createOriginalFile(context) }
        val names = files.map { it.name }.toSet()
        assertEquals("Each created file must have a unique UUID-based name", 50, names.size)
        files.forEach { file ->
            assertTrue("Filename must start with coupon_", file.name.startsWith("coupon_"))
            assertTrue("Filename must end with .jpg", file.name.endsWith(".jpg"))
        }
    }

    @Test
    fun testUnreferencedFileDeletionSafety() {
        val tempFile = File(context.cacheDir, "unreferenced_test.jpg")
        tempFile.writeText("test coupon data")
        assertTrue(tempFile.exists())

        // If referenceCount > 0, do NOT delete
        val notDeleted = StorageHelper.deleteFileIfUnreferenced(tempFile.absolutePath, referenceCount = 2)
        assertFalse(notDeleted)
        assertTrue(tempFile.exists())

        // If referenceCount == 0, safe to delete
        val deleted = StorageHelper.deleteFileIfUnreferenced(tempFile.absolutePath, referenceCount = 0)
        assertTrue(deleted)
        assertFalse(tempFile.exists())
    }
}
