package dev.sumin.skeleton.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StorageAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration::class.java))

    @Test
    fun `binds storage properties and creates validator`() {
        contextRunner
            .withPropertyValues(
                "skeleton.storage.validation.max-size-bytes=2048",
                "skeleton.storage.validation.allowed-content-types=image/png,image/jpeg",
                "skeleton.storage.validation.allowed-extensions=png,jpg,jpeg",
            )
            .run { context ->
                assertThat(context).hasSingleBean(StorageProperties::class.java)
                assertThat(context).hasSingleBean(StorageFileValidator::class.java)

                val properties = context.getBean(StorageProperties::class.java)
                assertEquals(2048L, properties.validation.maxSizeBytes)
                assertEquals(setOf("image/png", "image/jpeg"), properties.validation.allowedContentTypes)
                assertEquals(setOf("png", "jpg", "jpeg"), properties.validation.allowedExtensions)
            }
    }
}
