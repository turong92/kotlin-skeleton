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

    override fun publicKeyOf(privateKey: String): String = keys.publicKeyOf(privateKey)

    override fun publish(privateKey: String): Boolean {
        val pub = keys.properties().`public`
        // 원본의 Content-Type 을 그대로 쓴다 (확장자로 짐작하지 않는다) - 허용 밖이면 공개하지 않는다
        val type = storage.metadata(ObjectKey(privateKey))?.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (type !in PUBLISHABLE_TYPES) return false
        storage.copy(
            CopyObjectRequest(
                sourceKey = ObjectKey(privateKey),
                destinationKey = ObjectKey(keys.publicKeyOf(privateKey)),
                contentType = type,
                cacheControl = pub.cacheControl,
            ),
        )
        return true
    }

    override fun unpublish(privateKey: String) = deletePublicKey(keys.publicKeyOf(privateKey))

    override fun deletePublicKey(publicKey: String) {
        storage.delete(ObjectKey(publicKey))
        purger()?.purge(listOf(keys.properties().`public`.baseUrl.trimEnd('/') + "/" + publicKey))
    }

    override fun listPublicKeys(): List<String> {
        val prefix = keys.properties().`public`.prefix.trim('/') + "/"
        val found = mutableListOf<String>()
        var token: String? = null
        do {
            val page = storage.list(ListObjectsRequest(prefix, token, 1000))
            page.objects.forEach { found += it.key.value }
            token = page.nextContinuationToken.takeIf { page.truncated }
        } while (token != null)
        return found
    }

    private companion object {
        /** 공개해도 되는 형식 - 이미지(SVG 제외: 스크립트를 담을 수 있다)와 PDF. 그 밖은 비공개로만 둔다 */
        val PUBLISHABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heic", "application/pdf")
    }
}

/** 비공개 업로드의 짧은 서명 읽기 주소 */
class StoragePrivateUploadAccess(private val storage: PresignedStorage, private val keys: StorageKeyScheme) : PrivateUploadAccess {
    override val defaultTtl: Duration get() = keys.properties().`public`.accessTtl

    override fun downloadUrl(privateKey: String, ttl: Duration): String =
        storage.presignDownload(PresignedDownloadRequest(ObjectKey(privateKey), expiresIn = ttl)).url.toString()
}
