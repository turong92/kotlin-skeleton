package dev.sumin.skeleton.storage.s3

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** crypto 모듈은 있는데 키가 없어 OpaqueUrlTokenCodec 빈이 없을 때: 설정 키를 짚어 주며 시작에 실패한다. */
class S3OpaqueWithoutCodecTest {
    @Test
    fun `OPAQUE with crypto present but no codec bean fails naming the key setting`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3StorageAutoConfiguration::class.java, S3OpaquePublicUrlAutoConfiguration::class.java))
            .withPropertyValues(
                "skeleton.storage-s3.bucket=app-uploads",
                "skeleton.storage-s3.public-url.base-url=https://cdn.example.com",
                "skeleton.storage-s3.public-url.strategy=opaque",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.crypto.keys")
            }
    }
}
