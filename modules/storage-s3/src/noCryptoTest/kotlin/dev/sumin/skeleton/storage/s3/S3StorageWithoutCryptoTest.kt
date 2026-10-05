package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * crypto 는 OPAQUE 공개 URL 에만 필요한 선택 의존이다 (build.gradle.kts: compileOnly).
 * 이 묶음(noCryptoTest)의 클래스패스에는 crypto 모듈이 정말로 없다 — 평범한 S3/R2 (RAW) 는 그대로 떠야 하고,
 * OPAQUE 를 요청했는데 crypto 가 없으면 어떤 모듈을 더해야 하는지 말하며 시작에 실패해야 한다.
 */
class S3StorageWithoutCryptoTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(S3StorageAutoConfiguration::class.java, S3OpaquePublicUrlAutoConfiguration::class.java))
        .withPropertyValues(
            "skeleton.storage-s3.bucket=app-uploads",
            "skeleton.storage-s3.region=us-east-1",
            "skeleton.storage-s3.public-url.base-url=https://cdn.example.com/uploads",
        )

    @Test
    fun `this suite really has no crypto on the classpath`() {
        assertThatThrownBy { Class.forName("dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec") }
            .isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun `RAW public urls work when the crypto module is not on the classpath`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val resolver = context.getBean(StoragePublicUrlResolver::class.java)
            assertThat(resolver.publicUrl(ObjectKey("images/cat.png")))
                .isEqualTo(URI.create("https://cdn.example.com/uploads/images/cat.png"))
        }
    }

    @Test
    fun `OPAQUE without the crypto module fails at startup naming the module to add`() {
        runner
            .withPropertyValues("skeleton.storage-s3.public-url.strategy=opaque")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(":modules:crypto")
            }
    }
}
