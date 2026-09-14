package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.crypto.AesGcmKey
import dev.sumin.skeleton.crypto.AesGcmTextEncryptor
import dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI
import java.time.Duration
import java.util.Base64
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

class S3StorageAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(S3StorageAutoConfiguration::class.java))

    @Test
    fun `does not create s3 beans when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.storage-s3.enabled=false")
            .run { context ->
                assertTrue(context.getBeansOfType(S3Client::class.java).isEmpty())
                assertTrue(context.getBeansOfType(S3Presigner::class.java).isEmpty())
                assertTrue(context.getBeansOfType(PresignedStorage::class.java).isEmpty())
            }
    }

    @Test
    fun `creates s3 client presigner and storage service when bucket is configured`() {
        contextRunner
            .withPropertyValues(
                "skeleton.storage-s3.bucket=app-uploads",
                "skeleton.storage-s3.region=us-east-1",
                "skeleton.storage-s3.public-url.base-url=https://cdn.example.com/uploads",
            )
            .run { context ->
                assertThat(context).hasSingleBean(S3StorageProperties::class.java)
                assertThat(context).hasSingleBean(S3Client::class.java)
                assertThat(context).hasSingleBean(S3Presigner::class.java)
                assertThat(context).hasSingleBean(PresignedStorage::class.java)
                assertThat(context).hasSingleBean(StoragePublicUrlResolver::class.java)

                val resolver = context.getBean(StoragePublicUrlResolver::class.java)
                assertEquals(
                    URI.create("https://cdn.example.com/uploads/images/cat.png"),
                    resolver.publicUrl(ObjectKey("images/cat.png")),
                )
            }
    }

    @Test
    fun `creates opaque public url resolver when strategy is opaque`() {
        val codec = opaqueTokenCodec()

        contextRunner
            .withBean(OpaqueUrlTokenCodec::class.java, Supplier { codec })
            .withPropertyValues(
                "skeleton.storage-s3.bucket=app-uploads",
                "skeleton.storage-s3.region=us-east-1",
                "skeleton.storage-s3.public-url.base-url=https://cdn.example.com",
                "skeleton.storage-s3.public-url.strategy=opaque",
                "skeleton.storage-s3.public-url.token-path-prefix=/c",
            )
            .run { context ->
                val resolver = context.getBean(StoragePublicUrlResolver::class.java)
                val url = assertNotNull(resolver.publicUrl(ObjectKey("images/cat.png")))

                assertTrue(url.toString().startsWith("https://cdn.example.com/c/"))
                assertTrue(url.path.removePrefix("/c/").none { it == '/' || it == '+' || it == '=' })

                val payload = codec.decode(url.path.removePrefix("/c/"), expectedPurpose = "storage-public-url")
                assertEquals("images/cat.png", payload.value)
                assertEquals("s3", payload.metadata["provider"])
            }
    }

    @Test
    fun `does not create storage service without bucket`() {
        contextRunner
            .withPropertyValues("skeleton.storage-s3.region=us-east-1")
            .run { context ->
                assertThat(context).hasSingleBean(S3Client::class.java)
                assertThat(context).hasSingleBean(S3Presigner::class.java)
                assertTrue(context.getBeansOfType(PresignedStorage::class.java).isEmpty())
            }
    }

    @Test
    fun `backs off custom public url resolver`() {
        val resolver = StoragePublicUrlResolver { URI.create("https://assets.example.test/${it.value}") }

        contextRunner
            .withBean(StoragePublicUrlResolver::class.java, Supplier { resolver })
            .withPropertyValues(
                "skeleton.storage-s3.bucket=app-uploads",
                "skeleton.storage-s3.public-url.base-url=https://cdn.example.com/uploads",
            )
            .run { context ->
                assertThat(context.getBean(StoragePublicUrlResolver::class.java)).isSameAs(resolver)
                assertNull(context.getBean(S3StorageProperties::class.java).endpointOverride)
            }
    }

    private fun opaqueTokenCodec(): OpaqueUrlTokenCodec =
        OpaqueUrlTokenCodec(
            AesGcmTextEncryptor(
                primaryKeyId = "local",
                keys = mapOf("local" to AesGcmKey.fromBase64(key(1))),
            ),
        )

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { index -> (seed + index).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `static key pair credentials and region auto work for R2 style endpoints`() {
        contextRunner
            .withPropertyValues(
                "skeleton.storage-s3.bucket=ovation",
                "skeleton.storage-s3.region=auto",
                "skeleton.storage-s3.endpoint-override=https://account.r2.cloudflarestorage.com",
                "skeleton.storage-s3.path-style-access-enabled=true",
                "skeleton.storage-s3.credentials.access-key-id=r2-key",
                "skeleton.storage-s3.credentials.secret-access-key=r2-secret",
            )
            .run { context ->
                assertThat(context).hasSingleBean(S3Client::class.java)
                val client = context.getBean(S3Client::class.java)
                assertEquals("auto", client.serviceClientConfiguration().region().id())
                val identity = client.serviceClientConfiguration().credentialsProvider().resolveIdentity().join()
                assertEquals("r2-key", identity.accessKeyId())
            }
    }

    @Test
    fun `presign urls use the presign endpoint override when set`() {
        // 로컬 compose: 서버는 http://s3:8333 으로 붙고, 브라우저에 주는 URL 은 http://localhost:8333 이어야 한다
        contextRunner
            .withPropertyValues(
                "skeleton.storage-s3.bucket=b",
                "skeleton.storage-s3.region=us-east-1",
                "skeleton.storage-s3.endpoint-override=http://s3:8333",
                "skeleton.storage-s3.presign.endpoint-override=http://localhost:8333",
                "skeleton.storage-s3.path-style-access-enabled=true",
                "skeleton.storage-s3.credentials.access-key-id=k",
                "skeleton.storage-s3.credentials.secret-access-key=s",
            )
            .run { context ->
                val url = presignGet(context.getBean(S3Presigner::class.java))
                assertEquals("localhost", url.host)
                assertEquals(8333, url.port)
                assertTrue(url.path.startsWith("/b/"), url.toString())
                assertTrue(url.query.contains("X-Amz-Signature="), url.toString())
                assertEquals(URI.create("http://s3:8333"), context.getBean(S3StorageProperties::class.java).endpointOverride)
            }
    }

    @Test
    fun `presign urls fall back to the client endpoint override when presign override is unset`() {
        contextRunner
            .withPropertyValues(
                "skeleton.storage-s3.bucket=b",
                "skeleton.storage-s3.region=us-east-1",
                "skeleton.storage-s3.endpoint-override=http://s3:8333",
                "skeleton.storage-s3.path-style-access-enabled=true",
                "skeleton.storage-s3.credentials.access-key-id=k",
                "skeleton.storage-s3.credentials.secret-access-key=s",
            )
            .run { context ->
                val url = presignGet(context.getBean(S3Presigner::class.java))
                assertEquals("s3", url.host)
                assertEquals(8333, url.port)
            }
    }

    private fun presignGet(presigner: S3Presigner): java.net.URL =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(1))
                .getObjectRequest { it.bucket("b").key("k.txt") }
                .build(),
        ).url()

    @Test
    fun `half-specified key pair fails fast`() {
        contextRunner
            .withPropertyValues("skeleton.storage-s3.bucket=b", "skeleton.storage-s3.credentials.access-key-id=only-key")
            .run { context -> assertThat(context).hasFailed() }
    }
}
