package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedUploadPart
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest as AwsCompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest as AwsCreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner

class S3PresignedStorageServiceTest {
    private val presigner = S3Presigner.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
        .build()
    private val recordingClient = RecordingS3Client()
    private val service = S3PresignedStorageService(
        bucket = "test-bucket",
        s3Client = recordingClient.proxy(),
        presigner = presigner,
        uploadPresignDuration = Duration.ofMinutes(7),
        downloadPresignDuration = Duration.ofMinutes(9),
        multipartPartPresignDuration = Duration.ofMinutes(11),
        publicUrlResolver = StoragePublicUrlResolver { key -> URI.create("https://cdn.example.com/${key.value}") },
    )

    @AfterTest
    fun closePresigner() {
        presigner.close()
    }

    @Test
    fun `presigns put and get urls without contacting aws`() {
        val put = service.presignUpload(
            PresignedUploadRequest(
                key = ObjectKey("docs/readme.txt"),
                contentType = "text/plain",
                contentLength = 12,
                metadata = mapOf("owner" to "user-1"),
            ),
        )
        val get = service.presignDownload(
            PresignedDownloadRequest(
                key = ObjectKey("docs/readme.txt"),
                responseContentDisposition = "attachment; filename=\"readme.txt\"",
            ),
        )

        assertEquals("PUT", put.method)
        assertEquals("GET", get.method)
        assertEquals(ObjectKey("docs/readme.txt"), put.key)
        assertThat(put.url.toString()).contains("test-bucket", "docs/readme.txt", "X-Amz-Signature")
        assertThat(get.url.toString()).contains("test-bucket", "docs/readme.txt", "X-Amz-Signature")
        assertTrue(put.expiresAt.isAfter(Instant.now()))
        assertTrue(get.expiresAt.isAfter(Instant.now()))
    }

    @Test
    fun `starts presigns completes and aborts multipart uploads`() {
        val started = service.startMultipartUpload(
            StartMultipartUploadRequest(
                key = ObjectKey("videos/intro.mp4"),
                contentType = "video/mp4",
                metadata = mapOf("owner" to "user-1"),
            ),
        )

        val part = service.presignMultipartUploadPart(
            PresignedMultipartUploadPartRequest(
                key = ObjectKey("videos/intro.mp4"),
                uploadId = started.uploadId,
                partNumber = 1,
                contentLength = 5,
            ),
        )
        val completed = service.completeMultipartUpload(
            CompleteMultipartUploadRequest(
                key = ObjectKey("videos/intro.mp4"),
                uploadId = started.uploadId,
                parts = listOf(CompletedUploadPart(partNumber = 1, eTag = "\"etag-1\"")),
            ),
        )
        service.abortMultipartUpload(AbortMultipartUploadRequest(ObjectKey("videos/intro.mp4"), started.uploadId))

        assertEquals("upload-123", started.uploadId)
        assertEquals("PUT", part.method)
        assertThat(part.url.toString()).contains("partNumber=1", "uploadId=upload-123", "X-Amz-Signature")
        assertEquals("\"complete-etag\"", completed.eTag)
        assertThat(recordingClient.createMultipartRequests.single().metadata()).containsEntry("owner", "user-1")
        assertEquals("upload-123", recordingClient.completeMultipartRequests.single().uploadId())
        assertEquals(1, recordingClient.abortMultipartUploadCount)
    }

    @Test
    fun `returns metadata public urls and deletes objects through s3 client`() {
        val metadata = service.metadata(ObjectKey("images/cat.png"))
        val publicUrl = service.publicUrl(ObjectKey("images/cat.png"))
        service.delete(ObjectKey("images/cat.png"))

        assertEquals(42, metadata?.sizeBytes)
        assertEquals("image/png", metadata?.contentType)
        assertEquals("\"head-etag\"", metadata?.eTag)
        assertEquals(URI.create("https://cdn.example.com/images/cat.png"), publicUrl)
        assertEquals("images/cat.png", recordingClient.headObjectRequests.single().key())
        assertEquals("images/cat.png", recordingClient.deleteObjectRequests.single().key())
    }

    private class RecordingS3Client : InvocationHandler {
        val createMultipartRequests = mutableListOf<AwsCreateMultipartUploadRequest>()
        val completeMultipartRequests = mutableListOf<AwsCompleteMultipartUploadRequest>()
        val headObjectRequests = mutableListOf<HeadObjectRequest>()
        val deleteObjectRequests = mutableListOf<DeleteObjectRequest>()
        var abortMultipartUploadCount = 0

        fun proxy(): S3Client =
            Proxy.newProxyInstance(
                S3Client::class.java.classLoader,
                arrayOf(S3Client::class.java),
                this,
            ) as S3Client

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? =
            when (method.name) {
                "createMultipartUpload" -> {
                    val request = args?.first() as AwsCreateMultipartUploadRequest
                    createMultipartRequests += request
                    CreateMultipartUploadResponse.builder()
                        .bucket(request.bucket())
                        .key(request.key())
                        .uploadId("upload-123")
                        .build()
                }
                "completeMultipartUpload" -> {
                    val request = args?.first() as AwsCompleteMultipartUploadRequest
                    completeMultipartRequests += request
                    CompleteMultipartUploadResponse.builder()
                        .bucket(request.bucket())
                        .key(request.key())
                        .eTag("\"complete-etag\"")
                        .versionId("version-1")
                        .build()
                }
                "abortMultipartUpload" -> {
                    abortMultipartUploadCount++
                    AbortMultipartUploadResponse.builder().build()
                }
                "headObject" -> {
                    val request = args?.first() as HeadObjectRequest
                    headObjectRequests += request
                    HeadObjectResponse.builder()
                        .contentLength(42)
                        .contentType("image/png")
                        .eTag("\"head-etag\"")
                        .lastModified(Instant.parse("2026-06-12T00:00:00Z"))
                        .metadata(mapOf("owner" to "user-1"))
                        .versionId("version-2")
                        .build()
                }
                "deleteObject" -> {
                    val request = args?.first() as DeleteObjectRequest
                    deleteObjectRequests += request
                    DeleteObjectResponse.builder().build()
                }
                "serviceName" -> "s3"
                "close" -> null
                "toString" -> "RecordingS3Client"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected S3Client call: ${method.name}")
            }
    }
}
