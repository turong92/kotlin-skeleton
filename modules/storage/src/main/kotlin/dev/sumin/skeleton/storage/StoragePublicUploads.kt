package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.common.upload.PublicUploads
import java.time.Duration

/**
 * 게시판 사진의 공개 영역 - 비공개 키의 복사본을 공개 키에 두고(`Cache-Control` 붙여), 지운다.
 * 주소는 `<base-url>/<공개 키>`. 프로젝트 모드에서는 부를 때마다 지금 프로젝트의 설정 · 저장소로 간다.
 */
class StoragePublicUploads(
    private val storage: ObjectStorage,
    private val keys: StorageKeyScheme,
    private val purger: () -> PublicUploadPurger? = { null },
) : PublicUploads {
    override fun urlOf(privateKey: String): String {
        val pub = keys.properties().`public`
        return pub.baseUrl.trimEnd('/') + "/" + keys.publicKeyOf(privateKey)
    }

    override fun publish(privateKey: String) {
        val pub = keys.properties().`public`
        storage.copy(
            CopyObjectRequest(
                sourceKey = ObjectKey(privateKey),
                destinationKey = ObjectKey(keys.publicKeyOf(privateKey)),
                contentType = contentTypeOf(privateKey),
                cacheControl = pub.cacheControl,
            ),
        )
    }

    override fun unpublish(privateKey: String) {
        storage.delete(ObjectKey(keys.publicKeyOf(privateKey)))
        purger()?.purge(listOf(urlOf(privateKey)))
    }

    private fun contentTypeOf(key: String): String =
        when (key.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
}

/** 비공개 업로드의 짧은 서명 읽기 주소 */
class StoragePrivateUploadAccess(private val storage: PresignedStorage, private val keys: StorageKeyScheme) : PrivateUploadAccess {
    override val defaultTtl: Duration get() = keys.properties().`public`.accessTtl

    override fun downloadUrl(privateKey: String, ttl: Duration): String =
        storage.presignDownload(PresignedDownloadRequest(ObjectKey(privateKey), expiresIn = ttl)).url.toString()
}
