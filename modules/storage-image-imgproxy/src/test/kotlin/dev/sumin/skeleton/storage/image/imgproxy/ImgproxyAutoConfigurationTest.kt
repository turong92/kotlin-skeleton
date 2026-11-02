package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.DetectedKind
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ImgproxyAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ImgproxyRendererAutoConfiguration::class.java))
    private val ok = arrayOf("skeleton.storage.image.imgproxy.url=http://imgproxy:8080", "skeleton.storage.image.imgproxy.key=00ff", "skeleton.storage.image.imgproxy.salt=ab12")

    private val storage: dev.sumin.skeleton.storage.PresignedStorage = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(dev.sumin.skeleton.storage.PresignedStorage::class.java),
    ) { _, _, _ -> error("not used") } as dev.sumin.skeleton.storage.PresignedStorage

    @Test
    fun `enabled without a storage fails startup and names storage-s3`() {
        runner.withPropertyValues(*ok, "skeleton.storage.image.imgproxy.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("storage-s3")
        }
    }

    @Test
    fun `off by default - no engine and no settings read`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(ImgproxyRenderer::class.java) }
    }

    @Test
    fun `enabled without address key or salt fails startup naming the setting`() {
        listOf("url", "key", "salt").forEach { missing ->
            val values = ok.filterNot { it.contains(".$missing=") } + "skeleton.storage.image.imgproxy.enabled=true"
            runner.withPropertyValues(*values.toTypedArray()).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.storage.image.imgproxy.$missing")
            }
        }
    }

    @Test
    fun `a key that is not hex fails startup`() {
        runner.withPropertyValues(*ok, "skeleton.storage.image.imgproxy.enabled=true", "skeleton.storage.image.imgproxy.key=not-hex").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("skeleton.storage.image.imgproxy.key")
        }
    }

    @Test
    fun `enabled with a storage reads heic and avif as well as the common formats`() {
        runner.withPropertyValues(*ok, "skeleton.storage.image.imgproxy.enabled=true")
            .withBean(dev.sumin.skeleton.storage.PresignedStorage::class.java, { storage })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ImgproxyRenderer::class.java).kinds).contains(DetectedKind.HEIC, DetectedKind.AVIF, DetectedKind.JPEG)
            }
    }
}
