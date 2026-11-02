package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import java.time.Duration

/**
 * 저장소 키 -> imgproxy 가 원본을 가져갈 **주소**. 이 주소는 앱이 imgproxy 에 주는 것이지 사용자 입력이 아니다(SSRF 가 열리지 않는 이유) -
 * 구현은 설정된 저장소의 주소만 돌려줘야 한다. 기본 구현은 둘: [SignedUrlSourceResolver](짧은 수명의 서명 주소, 기본) · [S3SourceResolver](`s3://` - imgproxy 가 자기 자격으로 직접 읽음).
 * 다른 저장소(예: `gs://` - imgproxy 의 `IMGPROXY_USE_GCS`)는 이 포트를 구현한 빈을 두면 된다(이 모듈은 구현을 주지 않는다).
 */
fun interface ImgproxySourceResolver {
    /** [ttl] 은 서명 주소 방식이 쓰는 수명 - 직접 읽는 방식은 무시한다 */
    fun sourceUrl(key: ObjectKey, ttl: Duration): String
}

/** 설정된 저장소가 서명한 읽기 주소 - imgproxy 에 저장소 자격이 필요 없다. 주소의 호스트가 imgproxy 컨테이너에서 닿아야 한다 */
class SignedUrlSourceResolver(private val storage: PresignedStorage) : ImgproxySourceResolver {
    override fun sourceUrl(key: ObjectKey, ttl: Duration): String = storage.presignDownload(PresignedDownloadRequest(key, expiresIn = ttl)).url.toString()
}

/**
 * `s3://<버킷>/<키>` - imgproxy 가 `IMGPROXY_USE_S3=true` 와 자기 읽기 전용 자격(R2 · MinIO 면 `IMGPROXY_S3_ENDPOINT`)으로 직접 읽는다.
 * 버킷은 storage-s3 의 버킷과 같아야 하고, 저장소를 [KeyPrefixedPresignedStorage](dev.sumin.skeleton.storage.KeyPrefixedPresignedStorage) 로 감쌌다면 그 접두사를 [keyPrefix] 로 준다.
 */
class S3SourceResolver(private val bucket: String, keyPrefix: String = "") : ImgproxySourceResolver {
    private val prefix = keyPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    init {
        require(bucket.isNotBlank() && '/' !in bucket) { "the S3 bucket name must be a plain bucket name" }
    }

    override fun sourceUrl(key: ObjectKey, ttl: Duration): String = "s3://$bucket/$prefix${key.value}"
}
