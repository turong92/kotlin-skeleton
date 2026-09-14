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

    /**
     * [endpointOverride]: presign URL 에 들어갈 주소. 비어 있으면 서버가 S3 에 붙는 `endpoint-override` 를 그대로 쓴다.
     * 로컬 compose 처럼 서버는 `http://s3:8333` 으로 붙지만 브라우저는 그 호스트를 못 푸는 경우에만 따로 준다
     * (SigV4 서명에 host 가 들어가므로 발급 뒤 주소를 바꿔 끼울 수 없다). R2 처럼 두 주소가 같으면 비워 둔다.
     */
    data class Presign(
        val upload: Duration = Duration.ofMinutes(10),
        val download: Duration = Duration.ofMinutes(10),
        val multipartPart: Duration = Duration.ofMinutes(15),
        val endpointOverride: URI? = null,
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
