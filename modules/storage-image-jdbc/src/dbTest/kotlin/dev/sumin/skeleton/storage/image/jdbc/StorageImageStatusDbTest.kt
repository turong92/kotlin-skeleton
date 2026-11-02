package dev.sumin.skeleton.storage.image.jdbc

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.*
import dev.sumin.skeleton.storage.image.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 상태 줄 · 작업 넣기가 두 방언(postgresTest · mysqlTest)에서 같은지 - 같은 소스가 두 DB 에서 돈다. 큐 워커 스레드는 끈다 */
@Import(DbTestcontainers::class, StorageImageStatusDbTest.Fixtures::class)
@SpringBootTest(
    classes = [StorageImageJdbcTestApplication::class],
    properties = ["skeleton.storage.image.enabled=true", "skeleton.job-queue.worker-enabled=false", "spring.main.web-application-type=none"],
)
class StorageImageStatusDbTest {
    /** 어떤 키든 이미지로 보이는 저장소 - 지연 등록이 선언 형식을 본다 */
    class ImageOnlyStorage : PresignedStorage {
        override fun metadata(key: ObjectKey) = StoredObjectMetadata(key, sizeBytes = 10, contentType = "image/jpeg")
        override fun delete(key: ObjectKey) = Unit
        override fun upload(request: UploadObjectRequest) = error("not used")
        override fun copy(request: CopyObjectRequest) = error("not used")
        override fun list(request: ListObjectsRequest) = ListedObjects(emptyList())
        override fun presignUpload(request: PresignedUploadRequest) = error("not used")
        override fun presignDownload(request: PresignedDownloadRequest) = PresignedUrl(request.key, "GET", URI("http://s3.test/${request.key}"), emptyMap(), Instant.now())
        override fun startMultipartUpload(request: StartMultipartUploadRequest) = error("not used")
        override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) = error("not used")
        override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = error("not used")
        override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = error("not used")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean
        fun storage(): PresignedStorage = ImageOnlyStorage()

        @Bean
        fun engine(): ImageRenderer = object : ImageRenderer {
            override val kinds = setOf(DetectedKind.JPEG)
            override fun probe(head: ByteArray, kind: DetectedKind) = ImageInfo("image/jpeg", 10, 10)
            override fun render(source: () -> java.io.InputStream, info: ImageInfo, specs: List<VariantSpec>) = emptyList<Rendered>()
        }
    }

    @Autowired lateinit var images: UploadImages
    @Autowired lateinit var listener: UploadCompletionListener
    @Autowired lateinit var store: ImageStatusStore
    @Autowired lateinit var jdbc: JdbcClient

    private fun rows(hash: String) = jdbc.sql("select count(*) from storage_images where key_hash = :h").param("h", hash).query(Long::class.java).single()
    private fun jobs(hash: String) = jdbc.sql("select count(*) from jobs where job_type = 'storage.image.variants' and payload_json like :p").param("p", "%$hash%").query(Long::class.java).single()

    @Test
    fun `completions and lazy registrations racing on one key leave one row and one job`() {
        val key = "uploads/7/race/x.jpg"
        val hash = imageKeyHash(key)
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
        val upload = InspectedUpload(ObjectKey(key), "image/jpeg", "image/jpeg", 10, head) { ByteArrayInputStream(head) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(12)
        val results = (0 until 12).map { i ->
            pool.submit {
                start.await()
                if (i % 2 == 0) listener.onAccepted(upload) else images.ensure(key)
            }
        }
        start.countDown()
        results.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1L, rows(hash))
        assertEquals(1L, jobs(hash))
        assertEquals(UploadImageState.PENDING, images.status(key)!!.state)
    }

    @Test
    fun `a status row round-trips its variants and only leaves PENDING once`() {
        val hash = imageKeyHash("uploads/7/trip/x.jpg")
        assertTrue(store.register(hash, "uploads/7/trip/x.jpg") {})
        assertFalse(store.register(hash, "uploads/7/trip/x.jpg") { error("must not run when the row exists") })

        val variants = mapOf("thumb" to StoredVariant(400, 300, "jpg"), "display" to StoredVariant(1600, 1200, StoredVariant.ORIGINAL))
        assertTrue(store.markReady(hash, 4000, 3000, "gif", true, variants))
        assertFalse(store.markTerminal(hash, UploadImageState.FAILED, "RETRIES_EXHAUSTED"), "a READY row is never failed afterwards")

        val row = store.find(hash)!!
        assertEquals(UploadImageState.READY, row.state)
        assertEquals(listOf(4000, 3000, "gif", true), listOf(row.width, row.height, row.format, row.animated))
        assertEquals(variants, row.variants)
        assertEquals(setOf(hash), store.findAll(listOf(hash, "nope")).keys)

        store.delete(listOf(hash))
        assertNull(store.find(hash))
    }
}
