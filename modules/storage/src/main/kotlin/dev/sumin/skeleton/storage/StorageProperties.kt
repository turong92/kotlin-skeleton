package dev.sumin.skeleton.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.storage")
data class StorageProperties(
    val validation: StorageFileValidationRule = StorageFileValidationRule(),
    val web: StorageWebProperties = StorageWebProperties(),
)

/** 업로드 HTTP 엔드포인트(`/api/v1/storage` 아래) 설정 — 서블릿 웹 앱이고 [PresignedStorage] 빈이 있을 때만 의미가 있다 */
data class StorageWebProperties(
    /** false 면 엔드포인트를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
    val enabled: Boolean = true,
    /** 서버가 정하는 키의 맨 앞 구간: `<keyPrefix>/<계정 id>/<uuid>/<파일 이름>` (`OPAQUE` 면 `<keyPrefix>/o/<uuid>.<mac>/file[.<ext>]`) */
    val keyPrefix: String = "uploads",
    /** 키가 소유자를 담는 방식. `ACCOUNT_ID`(기본) 는 키에 계정 id 가 들어 있고, `OPAQUE` 는 키에서 계정 id 를 알 수 없다 */
    val keyOwner: StorageKeyOwner = StorageKeyOwner.ACCOUNT_ID,
    /** `OPAQUE` 키의 HMAC 비밀 — `OPAQUE` 일 때 32자 이상. 비밀이라 환경변수(`<P>_STORAGE_WEB_KEY_SECRET`)로 넣는다. 바꾸면 이미 발급한 `OPAQUE` 키는 소유 확인에서 떨어진다 */
    val keySecret: String = "",
    /** 로그인하지 않은 사람의 이미지 업로드 - **기본 꺼짐**, 앱 yml 이 명시해야 켜진다 */
    val guest: StorageGuestProperties = StorageGuestProperties(),
) {
    init {
        if (keyOwner == StorageKeyOwner.OPAQUE) {
            require(keySecret.length >= MIN_KEY_SECRET_LENGTH) {
                "skeleton.storage.web.key-secret must be at least $MIN_KEY_SECRET_LENGTH characters when skeleton.storage.web.key-owner=OPAQUE " +
                    "(env <P>_STORAGE_WEB_KEY_SECRET — list it under secrets:)"
            }
        }
    }

    /** 비밀은 로그 · 오류 메시지에 나오지 않는다 */
    override fun toString(): String =
        "StorageWebProperties(enabled=$enabled, keyPrefix=$keyPrefix, keyOwner=$keyOwner, keySecret=${if (keySecret.isEmpty()) "" else "****"})"

    companion object {
        const val MIN_KEY_SECRET_LENGTH = 32
    }
}

enum class StorageKeyOwner {
    ACCOUNT_ID,
    OPAQUE,
}

/**
 * 비회원 업로드(`POST /api/v1/storage/guest/presign`) 설정. 켜도 익명은 이미지 업로드 발급만 받는다 - 다운로드 · 멀티파트 · 검증은 로그인이 필요하다.
 * 키: `<keyPrefix>/g/<uuid>.<mac>/file.<ext>`, `mac = HMAC-SHA256(secret, "guest\n" + uuid)` 의 앞 32자 (회원 키 방식과 비밀도 따로).
 */
data class StorageGuestProperties(
    val enabled: Boolean = false,
    /** 키 MAC 의 서버 비밀 - 켜면 32자 이상, 프로젝트마다 달라야 한다. 환경변수 `<P>_STORAGE_WEB_GUEST_SECRET` */
    val secret: String = "",
    /** 받을 이미지 형식 - jpeg · png · webp · gif 안에서만 고를 수 있다 (SVG 는 설정으로도 열 수 없다) */
    val allowedContentTypes: Set<String> = StorageGuestProperties.IMAGE_EXTENSIONS.keys,
    val maxSizeBytes: Long = 10L * 1024 * 1024,
    /** IP 별 발급 한도 */
    val rateLimit: GuestRateLimit = GuestRateLimit(),
    /** REQUIRED 면 발급에 `captchaToken` 이 필요하고 검증기 빈이 없으면 기동 실패 */
    val captcha: StorageGuestCaptchaMode = StorageGuestCaptchaMode.OFF,
    val cleanup: GuestCleanup = GuestCleanup(),
) {
    init {
        if (enabled) {
            require(secret.length >= StorageWebProperties.MIN_KEY_SECRET_LENGTH) {
                "skeleton.storage.web.guest.secret must be at least ${StorageWebProperties.MIN_KEY_SECRET_LENGTH} characters when skeleton.storage.web.guest.enabled=true " +
                    "(env <P>_STORAGE_WEB_GUEST_SECRET - list it under secrets:)"
            }
        }
        val normalized = allowedContentTypes.map { it.trim().lowercase() }
        require(normalized.isNotEmpty() && normalized.all { it in IMAGE_EXTENSIONS }) {
            "skeleton.storage.web.guest.allowed-content-types must be a non-empty subset of ${IMAGE_EXTENSIONS.keys} (SVG and other types cannot be enabled)"
        }
        require(maxSizeBytes > 0) { "skeleton.storage.web.guest.max-size-bytes must be positive" }
    }

    /** 비밀은 로그 · 오류 메시지에 나오지 않는다 */
    override fun toString(): String = "StorageGuestProperties(enabled=$enabled, secret=${if (secret.isEmpty()) "" else "****"}, allowedContentTypes=$allowedContentTypes, maxSizeBytes=$maxSizeBytes)"

    data class GuestRateLimit(val capacity: Int = 20, val window: java.time.Duration = java.time.Duration.ofHours(1)) {
        init {
            require(capacity > 0 && !window.isZero && !window.isNegative) { "skeleton.storage.web.guest.rate-limit: capacity and window must be positive" }
        }
    }

    /** 붙지 않은 비회원 업로드를 지우는 주기 작업 - 기본 꺼짐 */
    data class GuestCleanup(
        val enabled: Boolean = false,
        /** 이 시간 안에 글 · 댓글에 붙지 않은 키를 지운다 */
        val ttl: java.time.Duration = java.time.Duration.ofHours(24),
        /** 한 번에 지우는 최대 개수 */
        val batchSize: Int = 500,
    ) {
        init {
            require(!ttl.isZero && !ttl.isNegative && batchSize in 1..1000) { "skeleton.storage.web.guest.cleanup: ttl must be positive and batch-size 1..1000" }
        }
    }

    companion object {
        /** 허용할 수 있는 이미지 형식과 그 확장자 - 키의 확장자는 파일 이름이 아니라 Content-Type 에서 정한다 */
        val IMAGE_EXTENSIONS: Map<String, String> = linkedMapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif")
        val EXTENSION_ALIASES: Map<String, Set<String>> = mapOf("image/jpeg" to setOf("jpg", "jpeg"), "image/png" to setOf("png"), "image/webp" to setOf("webp"), "image/gif" to setOf("gif"))
    }
}

enum class StorageGuestCaptchaMode { OFF, REQUIRED }
