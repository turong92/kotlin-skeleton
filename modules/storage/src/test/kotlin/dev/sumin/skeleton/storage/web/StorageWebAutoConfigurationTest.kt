package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storage.StorageAutoConfiguration
import dev.sumin.skeleton.storage.StorageKeyOwnership
import dev.sumin.skeleton.storage.StorageKeyScheme
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

    @Test
    fun `a StorageKeyOwnership bean reflecting the key owner is registered even without a PresignedStorage`() {
        runner.run { context -> assertThat(context).hasSingleBean(StorageKeyOwnership::class.java) }
        runner.withPropertyValues("skeleton.storage.web.key-owner=OPAQUE", "skeleton.storage.web.key-secret=" + "s".repeat(40))
            .run { context ->
                val ownership = context.getBean(StorageKeyOwnership::class.java)
                val key = StorageKeyScheme(context.getBean(StorageProperties::class.java).web).issue("acc_a", "f.png")
                assertThat(ownership.owns("acc_a", key)).isTrue()
                assertThat(ownership.owns("acc_b", key)).isFalse()
            }
    }

    @Test
    fun `an app StorageKeyOwnership replaces the default`() {
        runner.withBean(StorageKeyOwnership::class.java, Supplier { StorageKeyOwnership { _, _ -> true } })
            .run { context -> assertThat(context.getBean(StorageKeyOwnership::class.java).owns("x", "y")).isTrue() }
    }

    @Test
    fun `opaque mode without a secret fails the startup`() {
        runner.withPropertyValues("skeleton.storage.web.key-owner=OPAQUE")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.storage.web.key-secret")
                // 배포에서 고칠 수 있게 환경변수 이름과 선언의 secrets: 도 말한다 (docs/deploy.md "버전을 올릴 때 선언에서 바꿀 것")
                assertThat(context.startupFailure).hasStackTraceContaining("STORAGE_WEB_KEY_SECRET").hasStackTraceContaining("secrets:")
            }
        runner.withPropertyValues("skeleton.storage.web.key-owner=OPAQUE", "skeleton.storage.web.key-secret=tooshort-value")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.storage.web.key-secret")
                assertThat(context.startupFailure!!.stackTraceToString()).doesNotContain("tooshort-value")
            }
    }

    @Test
    fun `two UploadedKeyUsage beans (the app's next to board-jdbc's) both start and both count`() {
        runner.withBean(FakePresignedStorage::class.java, Supplier { FakePresignedStorage() })
            .withBean("boardUsage", dev.sumin.skeleton.common.upload.UploadedKeyUsage::class.java, Supplier { dev.sumin.skeleton.common.upload.UploadedKeyUsage { k -> k.filter { it == "a" }.toSet() } })
            .withBean("appUsage", dev.sumin.skeleton.common.upload.UploadedKeyUsage::class.java, Supplier { dev.sumin.skeleton.common.upload.UploadedKeyUsage { k -> k.filter { it == "b" }.toSet() } })
            .withPropertyValues(
                "skeleton.storage.web.guest.enabled=true", "skeleton.storage.web.guest.secret=guest-secret-for-tests-0123456789abcdef",
                "skeleton.storage.web.guest.cleanup.enabled=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(dev.sumin.skeleton.storage.StorageGuestCleanup::class.java)
            }
    }
}
