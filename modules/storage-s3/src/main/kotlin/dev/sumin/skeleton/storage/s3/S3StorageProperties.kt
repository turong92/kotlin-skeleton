package dev.sumin.skeleton.storage.s3

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.storage-s3")
data class S3StorageProperties(
    val enabled: Boolean = true,
    val bucket: String = "",
    val region: String = "ap-northeast-2",
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val credentials: Credentials = Credentials(),
    val presign: Presign = Presign(),
    val publicUrl: PublicUrl = PublicUrl(),
) {
    /**
     * 우선순위: accessKeyId+secretAccessKey(정적 키 쌍, R2/MinIO) → profile(~/.aws) → AWS 기본 체인.
     * R2: `region: auto`, `endpoint-override: https://<account>.r2.cloudflarestorage.com`, `path-style-access-enabled: true`
     */
    data class Credentials(
        val profile: String = "",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
    )

    data class Presign(
        val upload: Duration = Duration.ofMinutes(10),
        val download: Duration = Duration.ofMinutes(10),
        val multipartPart: Duration = Duration.ofMinutes(15),
    )

    data class PublicUrl(
        val baseUrl: String = "",
        val strategy: PublicUrlStrategy = PublicUrlStrategy.RAW,
        val tokenPathPrefix: String = "/c",
        val tokenPurpose: String = "storage-public-url",
    )

    enum class PublicUrlStrategy {
        RAW,
        OPAQUE,
    }
}
