package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storage.StorageAutoConfiguration
import dev.sumin.skeleton.storage.StorageProperties
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import org.assertj.core.api.Assertions.assertThat
import java.util.function.Supplier
import kotlin.test.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

class StorageWebAutoConfigurationTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration::class.java, StorageWebAutoConfiguration::class.java))

    @Test
    fun `no controller without a PresignedStorage bean (no bucket configured)`() {
        runner.run { context -> assertThat(context).doesNotHaveBean(StorageController::class.java) }
    }

    @Test
    fun `controller is registered when a PresignedStorage exists`() {
        runner.withBean(FakePresignedStorage::class.java, Supplier { FakePresignedStorage() })
            .run { context -> assertThat(context).hasSingleBean(StorageController::class.java) }
    }

    @Test
    fun `an app controller of its own replaces the default`() {
        runner.withBean(FakePresignedStorage::class.java, Supplier { FakePresignedStorage() })
            .withBean(StorageController::class.java, Supplier { StorageController(FakePresignedStorage(), dev.sumin.skeleton.storage.StorageFileValidator(dev.sumin.skeleton.storage.StorageFileValidationRule()), StorageProperties().web) })
            .run { context -> assertThat(context).hasSingleBean(StorageController::class.java) }
    }
}
