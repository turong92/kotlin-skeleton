package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.storage.DetectedKind
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StorageImageAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageImageAutoConfiguration::class.java))
        .withPropertyValues("skeleton.storage.image.enabled=true")

    private val engine = object : ImageRenderer {
        override val kinds = setOf(DetectedKind.JPEG)
        override fun probe(head: ByteArray, kind: DetectedKind) = ImageInfo("image/jpeg", 1, 1)
        override fun render(source: () -> java.io.InputStream, info: ImageInfo, specs: List<VariantSpec>) = emptyList<Rendered>()
    }

    private val store = object : ImageStatusStore {
        override fun find(keyHash: String) = null
        override fun findAll(keyHashes: Collection<String>) = emptyMap<String, ImageRow>()
        override fun register(keyHash: String, objectKey: String, onCreated: () -> Unit) = false
        override fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>) = false
        override fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int?, height: Int?) = false
        override fun replaceVariant(keyHash: String, name: String, variant: StoredVariant) = false
        override fun delete(keyHashes: Collection<String>) = Unit
    }

    @Test
    fun `enabling without a state store fails startup and names storage-image-jdbc`() {
        runner.withBean(ImageRenderer::class.java, { engine }).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("storage-image-jdbc")
        }
    }

    @Test
    fun `enabling without an engine fails startup and names storage-image-imageio`() {
        runner.withBean(ImageStatusStore::class.java, { store }).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("storage-image-imageio")
        }
    }
}
