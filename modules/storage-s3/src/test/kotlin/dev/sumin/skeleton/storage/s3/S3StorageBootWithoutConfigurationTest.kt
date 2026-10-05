package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 모듈 + 선언된 의존(storage)만 얹고 설정이 없으면 뜬다 — 버킷도 S3 도 자격증명도 없이.
 * 저장소 빈(PresignedStorage)은 `skeleton.storage-s3.bucket` 을 정해야 생긴다.
 */
class S3StorageBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration and no S3`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration::class.java, S3StorageAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(PresignedStorage::class.java)
            }
    }
}
