package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.common.upload.PublicUploads
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.upload.UploadVariantsPendingException
import java.time.Duration

/**
 * 게시판 사진의 공개 영역 - 비공개 키의 복사본을 공개 키에 두고(`Cache-Control` 붙여), 지운다.
 * 주소는 `<base-url>/<공개 키>`. 프로젝트 모드에서는 부를 때마다 지금 프로젝트의 설정 · 저장소로 간다.
 */
class StoragePublicUploads(
    private val storage: ObjectStorage,
    private val keys: StorageKeyScheme,
    /** 이미지 모듈 - 있으면 이미지는 원본이 아니라 변환본(`display` · `thumb`, 메타데이터 없음)이 공개 영역에 복사된다 */
    private val images: () -> UploadImages? = { null },
    private val purger: () -> PublicUploadPurger? = { null },
) : PublicUploads {
    override fun urlOf(privateKey: String): String {
        val pub = keys.properties().`public`
        return pub.baseUrl.trimEnd('/') + "/" + keys.publicKeyOf(privateKey)
    }

    override fun enabled(): Boolean = keys.publicEnabled()

    override fun purgeEnabled(): Boolean {
        val purger = purger() ?: return false
        return enabled() && (purger !is CloudflarePublicUploadPurger || keys.properties().`public`.purge.cloudflare.enabled)
    }

    override fun publicKeyOf(privateKey: String): String = keys.publicKeyOf(privateKey)

    override fun publicKeysOf(privateKey: String): List<String> =
        if (images() != null && isImageKey(privateKey)) listOf(keys.publicKeyOf(privateKey), thumbKeyOf(privateKey)) else listOf(keys.publicKeyOf(privateKey))

    override fun mayPublish(privateKey: String): Boolean =
        keys.properties().`public`.enabled &&
            privateKey.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase() in PUBLISHABLE_EXTENSIONS

    override fun publish(privateKey: String): Boolean {
        if (!keys.properties().`public`.enabled) return false   // 이 프로젝트에서 꺼져 있으면 복사하지 않는다 (앱 단에서만 켜고 프로젝트가 끈 경우)
        val pub = keys.properties().`public`
        // 원본의 Content-Type 을 그대로 쓴다 (확장자로 짐작하지 않는다) - 허용 밖이면 공개하지 않는다
        val type = storage.metadata(ObjectKey(privateKey))?.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (type !in PUBLISHABLE_TYPES) return false
        val imageModule = images()
        if (imageModule != null && type in IMAGE_TYPES) return publishVariants(imageModule, privateKey, pub.cacheControl)
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

    /** 남에게는 원본이 아니라 변환본 - 변환 전이면 늦춰 다시 하게 하고(원본을 대신 주지 않는다), 변환할 수 없는 이미지는 공개하지 않는다 */
    private fun publishVariants(imageModule: UploadImages, privateKey: String, cacheControl: String): Boolean {
        val status = imageModule.status(privateKey)
        if (status == null) {
            imageModule.ensure(privateKey)
            throw UploadVariantsPendingException(privateKey)
        }
        when (status.state) {
            UploadImageState.READY -> Unit
            UploadImageState.PENDING -> throw UploadVariantsPendingException(privateKey)
            UploadImageState.REJECTED, UploadImageState.UNSUPPORTED, UploadImageState.FAILED -> return false
        }
        val display = status.variants[UploadImages.DISPLAY] ?: return false
        copyVariant(display, keys.publicKeyOf(privateKey), cacheControl)
        status.variants[UploadImages.THUMB]?.let { copyVariant(it, thumbKeyOf(privateKey), cacheControl) }
        return true
    }

    private fun copyVariant(variant: dev.sumin.skeleton.common.upload.UploadImageVariant, destination: String, cacheControl: String) {
        storage.copy(CopyObjectRequest(sourceKey = ObjectKey(variant.key), destinationKey = ObjectKey(destination), contentType = variant.contentType, cacheControl = cacheControl))
    }

    /** 공개 키 꼬리의 확장자 앞에 `.thumb` - 비공개 키에서만 계산된다 */
    private fun thumbKeyOf(privateKey: String): String {
        val publicKey = keys.publicKeyOf(privateKey)
        val dot = publicKey.lastIndexOf('.')
        return if (dot > publicKey.lastIndexOf('/')) publicKey.substring(0, dot) + ".thumb" + publicKey.substring(dot) else "$publicKey.thumb"
    }

    private fun isImageKey(privateKey: String) = privateKey.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase() in IMAGE_EXTENSIONS

    override fun unpublish(privateKey: String) = publicKeysOf(privateKey).forEach(::deletePublicKey)

    override fun deletePublicKey(publicKey: String) {
        storage.delete(ObjectKey(publicKey))   // 객체 삭제가 먼저이고 동기다 - 공개 쪽 보장
    }

    override fun purgePublicKey(publicKey: String) {
        purger()?.purge(listOf(keys.properties().`public`.absoluteUrl(publicKey)))
    }

    override fun listPublicKeys(): List<String> {
        // board 가 쓰는 하위 접두사 안만 본다 - 같은 공개 접두사를 다른 용도가 써도 전체 대조가 지우지 않는다
        val prefix = keys.properties().`public`.prefix.trim('/') + "/" + StorageKeyScheme.BOARD_PUBLIC_SEGMENT + "/"
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
        val PUBLISHABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "pdf")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic")
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heic")
        val PUBLISHABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heic", "application/pdf")
    }
}

/** 비공개 업로드의 짧은 서명 읽기 주소 */
class StoragePrivateUploadAccess(private val storage: PresignedStorage, private val keys: StorageKeyScheme) : PrivateUploadAccess {
    override fun enabled(): Boolean = keys.publicEnabled()

    override val defaultTtl: Duration get() = keys.properties().`public`.accessTtl

    override fun downloadUrl(privateKey: String, ttl: Duration): String =
        storage.presignDownload(PresignedDownloadRequest(ObjectKey(privateKey), expiresIn = ttl)).url.toString()
}
