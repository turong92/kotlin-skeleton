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
    data class Credentials(
        val profile: String = "",
    )

    data class Presign(
        val upload: Duration = Duration.ofMinutes(10),
        val download: Duration = Duration.ofMinutes(10),
        val multipartPart: Duration = Duration.ofMinutes(15),
    )

    data class PublicUrl(
        val baseUrl: String = "",
    )
}
