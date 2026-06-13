package dev.sumin.skeleton.storage

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageContractsTest {
    @Test
    fun `object key rejects blank absolute traversal and control character values`() {
        assertFailsWith<IllegalArgumentException> { ObjectKey("") }
        assertFailsWith<IllegalArgumentException> { ObjectKey("/avatars/user.png") }
        assertFailsWith<IllegalArgumentException> { ObjectKey("avatars/../secret.txt") }
        assertFailsWith<IllegalArgumentException> { ObjectKey("avatars/user\u0000.png") }
    }

    @Test
    fun `presign requests preserve upload and download intent`() {
        val key = ObjectKey("avatars/user.png")
        val upload = PresignedUploadRequest(
            key = key,
            contentType = "image/png",
            contentLength = 1024,
            expiresIn = Duration.ofMinutes(5),
            metadata = mapOf("owner" to "user-1"),
        )
        val download = PresignedDownloadRequest(
            key = key,
            expiresIn = Duration.ofMinutes(3),
            responseContentDisposition = "attachment; filename=\"user.png\"",
        )

        assertEquals(key, upload.key)
        assertEquals("image/png", upload.contentType)
        assertEquals(1024, upload.contentLength)
        assertEquals(mapOf("owner" to "user-1"), upload.metadata)
        assertEquals("attachment; filename=\"user.png\"", download.responseContentDisposition)
    }

    @Test
    fun `object storage requests validate copy move list and upload intent`() {
        val source = ObjectKey("avatars/source.png")
        val destination = ObjectKey("avatars/destination.png")

        val upload = UploadObjectRequest(
            key = source,
            content = "hello".toByteArray(),
            contentType = "image/png",
            metadata = mapOf("owner" to "user-1"),
        )
        val copy = CopyObjectRequest(
            sourceKey = source,
            destinationKey = destination,
            metadata = mapOf("copied-by" to "storage"),
        )
        val move = MoveObjectRequest(sourceKey = source, destinationKey = destination)
        val list = ListObjectsRequest(prefix = "avatars/", maxKeys = 50)

        assertEquals(source, upload.key)
        assertEquals("image/png", upload.contentType)
        assertEquals(mapOf("owner" to "user-1"), upload.metadata)
        assertEquals(destination, copy.destinationKey)
        assertEquals(source, move.sourceKey)
        assertEquals("avatars/", list.prefix)
        assertEquals(50, list.maxKeys)
        assertFailsWith<IllegalArgumentException> {
            UploadObjectRequest(key = source, content = ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            CopyObjectRequest(sourceKey = source, destinationKey = source)
        }
        assertFailsWith<IllegalArgumentException> {
            ListObjectsRequest(maxKeys = 0)
        }
    }

    @Test
    fun `multipart requests validate upload id part number and completed parts`() {
        val key = ObjectKey("videos/intro.mp4")

        assertFailsWith<IllegalArgumentException> {
            PresignedMultipartUploadPartRequest(key = key, uploadId = "upload-1", partNumber = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompleteMultipartUploadRequest(key = key, uploadId = "upload-1", parts = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            CompletedUploadPart(partNumber = 1, eTag = " ")
        }

        val request = CompleteMultipartUploadRequest(
            key = key,
            uploadId = "upload-1",
            parts = listOf(CompletedUploadPart(partNumber = 2, eTag = "etag-2")),
        )

        assertEquals("upload-1", request.uploadId)
        assertEquals(2, request.parts.single().partNumber)
    }

    @Test
    fun `storage file validator accepts matching size type and extension`() {
        val validator = StorageFileValidator(
            StorageFileValidationRule(
                maxSizeBytes = 1024,
                allowedContentTypes = setOf("image/*"),
                allowedExtensions = setOf("png", "jpg"),
            ),
        )

        val result = validator.validate(
            StorageFileCandidate(
                fileName = "avatar.PNG",
                contentType = "image/png",
                sizeBytes = 1024,
            ),
        )

        assertTrue(result.valid)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `storage file validator reports size content type and extension failures`() {
        val validator = StorageFileValidator(
            StorageFileValidationRule(
                maxSizeBytes = 10,
                allowedContentTypes = setOf("image/png"),
                allowedExtensions = setOf("png"),
            ),
        )

        val result = validator.validate(
            StorageFileCandidate(
                fileName = "notes.txt",
                contentType = "text/plain",
                sizeBytes = 11,
            ),
        )

        assertEquals(
            listOf(
                StorageFileValidationErrorCode.SIZE_TOO_LARGE,
                StorageFileValidationErrorCode.UNSUPPORTED_CONTENT_TYPE,
                StorageFileValidationErrorCode.UNSUPPORTED_EXTENSION,
            ),
            result.errors.map { it.code },
        )
        assertFailsWith<StorageFileValidationException> {
            validator.requireValid(StorageFileCandidate("notes.txt", "text/plain", 11))
        }
    }
}
