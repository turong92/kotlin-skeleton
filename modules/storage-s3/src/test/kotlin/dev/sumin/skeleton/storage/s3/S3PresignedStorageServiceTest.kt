package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedUploadPart
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.MoveObjectRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import dev.sumin.skeleton.storage.UploadObjectRequest
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
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest as AwsCompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CopyObjectRequest as AwsCopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest as AwsCreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
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
    fun `upload passes cache-control and content-disposition through to S3 (R2 static assets)`() {
        service.upload(
            UploadObjectRequest(
                key = ObjectKey("pages/abc/index.html"),
                content = "<html/>".toByteArray(),
                contentType = "text/html; charset=utf-8",
                cacheControl = "public, max-age=31536000, immutable",
                contentDisposition = "inline",
            ),
        )
        val put = recordingClient.putObjectRequests.last()
        assertEquals("public, max-age=31536000, immutable", put.cacheControl())
        assertEquals("inline", put.contentDisposition())
        assertEquals("text/html; charset=utf-8", put.contentType())
    }

    @Test
    fun `deleteAll batches keys into DeleteObjects requests of at most 1000`() {
        val keys = (1..1500).map { ObjectKey("photos/$it.webp") }
        service.deleteAll(keys + keys.first())
        assertEquals(2, recordingClient.deleteObjectsRequests.size)
        assertEquals(1000, recordingClient.deleteObjectsRequests[0].delete().objects().size)
        assertEquals(500, recordingClient.deleteObjectsRequests[1].delete().objects().size)
        assertTrue(recordingClient.deleteObjectRequests.isEmpty())
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

    @Test
    fun `uploads copies moves and lists objects through s3 client`() {
        val uploaded = service.upload(
            UploadObjectRequest(
                key = ObjectKey("docs/readme.txt"),
                content = "hello".toByteArray(),
                contentType = "text/plain",
                metadata = mapOf("owner" to "user-1"),
            ),
        )
        val copied = service.copy(
            CopyObjectRequest(
                sourceKey = ObjectKey("docs/readme.txt"),
                destinationKey = ObjectKey("docs/readme-copy.txt"),
                metadata = mapOf("copied" to "true"),
            ),
        )
        val moved = service.move(
            MoveObjectRequest(
                sourceKey = ObjectKey("docs/readme-copy.txt"),
                destinationKey = ObjectKey("archive/readme.txt"),
            ),
        )
        val listed = service.list(
            ListObjectsRequest(
                prefix = "docs/",
                continuationToken = "token-1",
                maxKeys = 20,
            ),
        )

        assertEquals(ObjectKey("docs/readme.txt"), uploaded.key)
        assertEquals("\"put-etag\"", uploaded.eTag)
        assertEquals(URI.create("https://cdn.example.com/docs/readme.txt"), uploaded.publicUrl)
        assertEquals(ObjectKey("docs/readme-copy.txt"), copied.key)
        assertEquals("\"copy-etag\"", copied.eTag)
        assertEquals(ObjectKey("archive/readme.txt"), moved.key)
        assertEquals("next-token", listed.nextContinuationToken)
        assertEquals(ObjectKey("docs/readme.txt"), listed.objects.single().key)
        assertEquals(5, recordingClient.lastPutBody?.size)
        assertEquals("text/plain", recordingClient.putObjectRequests.single().contentType())
        assertEquals("test-bucket", recordingClient.copyObjectRequests.first().sourceBucket())
        assertEquals("docs/readme.txt", recordingClient.copyObjectRequests.first().sourceKey())
        assertEquals("docs/readme-copy.txt", recordingClient.deleteObjectRequests.last().key())
        assertEquals("docs/", recordingClient.listObjectRequests.single().prefix())
        assertEquals("token-1", recordingClient.listObjectRequests.single().continuationToken())
    }

    private class RecordingS3Client : InvocationHandler {
        val putObjectRequests = mutableListOf<PutObjectRequest>()
        val createMultipartRequests = mutableListOf<AwsCreateMultipartUploadRequest>()
        val completeMultipartRequests = mutableListOf<AwsCompleteMultipartUploadRequest>()
        val copyObjectRequests = mutableListOf<AwsCopyObjectRequest>()
        val headObjectRequests = mutableListOf<HeadObjectRequest>()
        val listObjectRequests = mutableListOf<ListObjectsV2Request>()
        val deleteObjectRequests = mutableListOf<DeleteObjectRequest>()
        val deleteObjectsRequests = mutableListOf<DeleteObjectsRequest>()
        var abortMultipartUploadCount = 0
        var lastPutBody: ByteArray? = null

        fun proxy(): S3Client =
            Proxy.newProxyInstance(
                S3Client::class.java.classLoader,
                arrayOf(S3Client::class.java),
                this,
            ) as S3Client

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? =
            when (method.name) {
                "deleteObjects" -> {
                    deleteObjectsRequests += args?.first() as DeleteObjectsRequest
                    DeleteObjectsResponse.builder().build()
                }
                "putObject" -> {
                    val request = args?.first() as PutObjectRequest
                    val body = args?.getOrNull(1) as? RequestBody
                    putObjectRequests += request
                    lastPutBody = body?.contentStreamProvider()?.newStream()?.use { it.readBytes() }
                    PutObjectResponse.builder()
                        .eTag("\"put-etag\"")
                        .versionId("put-version-1")
                        .build()
                }
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
                "copyObject" -> {
                    val request = args?.first() as AwsCopyObjectRequest
                    copyObjectRequests += request
                    CopyObjectResponse.builder()
                        .copyObjectResult {
                            it.eTag("\"copy-etag\"")
                                .lastModified(Instant.parse("2026-06-12T00:00:00Z"))
                        }
                        .versionId("copy-version-1")
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
                "listObjectsV2" -> {
                    val request = args?.first() as ListObjectsV2Request
                    listObjectRequests += request
                    ListObjectsV2Response.builder()
                        .isTruncated(true)
                        .nextContinuationToken("next-token")
                        .contents(
                            S3Object.builder()
                                .key("docs/readme.txt")
                                .size(5)
                                .eTag("\"list-etag\"")
                                .lastModified(Instant.parse("2026-06-12T00:00:00Z"))
                                .build(),
                        )
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
