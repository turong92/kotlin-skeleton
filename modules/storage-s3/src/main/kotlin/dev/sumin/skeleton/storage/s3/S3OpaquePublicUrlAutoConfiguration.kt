package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * OPAQUE 공개 URL 은 crypto 모듈(OpaqueUrlTokenCodec)이 클래스패스에 있을 때만 이 자동 구성이 켜진다.
 * crypto 는 storage-s3 의 compileOnly 라서, 평범한 S3/R2 앱은 crypto 를 받지 않는다.
 * S3StorageAutoConfiguration 보다 먼저 돌아 StoragePublicUrlResolver 를 등록하고, 앱이 직접 정의한 빈이 있으면 물러난다.
 */
@AutoConfiguration(before = [S3StorageAutoConfiguration::class])
// 클래스 리터럴이 아니라 이름 문자열: crypto 가 없을 때 어노테이션 값을 읽다가 클래스를 로드하지 않게 한다
@ConditionalOnClass(name = ["dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec"])
@ConditionalOnProperty(prefix = "skeleton.storage-s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class S3OpaquePublicUrlAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.storage-s3.public-url", name = ["strategy"], havingValue = "OPAQUE")
    fun s3OpaqueStoragePublicUrlResolver(
        properties: S3StorageProperties,
        opaqueUrlTokenCodec: ObjectProvider<OpaqueUrlTokenCodec>,
    ): StoragePublicUrlResolver {
        if (properties.publicUrl.baseUrl.isBlank()) return StoragePublicUrlResolver.NONE
        val codec = opaqueUrlTokenCodec.getIfAvailable()
            ?: throw IllegalStateException(
                "skeleton.storage-s3.public-url.strategy=OPAQUE needs an OpaqueUrlTokenCodec bean: configure skeleton.crypto.keys (module crypto).",
            )
        return OpaqueStoragePublicUrlResolver(
            baseUrl = properties.publicUrl.baseUrl,
            tokenPathPrefix = properties.publicUrl.tokenPathPrefix,
            tokenPurpose = properties.publicUrl.tokenPurpose,
            tokenCodec = codec,
        )
    }
}
