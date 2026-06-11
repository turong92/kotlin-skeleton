package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

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
}
