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
    /** 게시판 사진의 공개 영역 - **기본 꺼짐** */
    val `public`: StoragePublicProperties = StoragePublicProperties(),
) {
    init {
        if (`public`.enabled) {
            val up = keyPrefix.trim('/')
            val pub = `public`.prefix.trim('/')
            require(pub != up && !pub.startsWith("$up/") && !up.startsWith("$pub/")) {
                "skeleton.storage.web.public.prefix ('$pub') must not overlap skeleton.storage.web.key-prefix ('$up') - the public area must hold only published copies"
            }
        }
    }

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

/**
 * 공개 영역(`skeleton.storage.web.public.*`). 공개 객체의 키는 비공개 키에서 HMAC 으로 계산된다: `<prefix>/<hmac32>.<ext>`.
 * 공개 영역 앞의 읽기 공개(버킷 정책 · CDN 원본 경로)는 인프라가 정한다 - 이 설정은 주소(`base-url`)와 복사만 안다.
 */
data class StoragePublicProperties(
    val enabled: Boolean = false,
    /** 공개 키 HMAC 의 서버 비밀 - 켜면 32자 이상, 프로젝트마다 달라야 한다. 환경변수 `<P>_STORAGE_WEB_PUBLIC_SECRET` */
    val secret: String = "",
    /** 공개 영역의 접두사 - 업로드 접두사(`key-prefix`)와 겹치면 기동 실패 */
    val prefix: String = "public",
    /** 공개 주소의 앞부분(CDN 또는 공개 버킷 주소) - 주소는 `<base-url>/<공개 키>`. 켜면 필수 */
    val baseUrl: String = "",
    /** 공개 객체에 붙는 `Cache-Control` - 가린 뒤에도 CDN 에 남는 시간의 상한 */
    val cacheControl: String = "public, max-age=3600",
    /** 승인 전 · 판 사진을 관리하는 사람이 볼 때의 서명 주소 수명 */
    val accessTtl: java.time.Duration = java.time.Duration.ofMinutes(5),
    /** 공개 객체를 지운 뒤 CDN 캐시를 비우는 곳 - **기본 꺼짐** */
    val purge: PublicPurgeProperties = PublicPurgeProperties(),
) {
    init {
        if (enabled) {
            require(secret.length >= StorageWebProperties.MIN_KEY_SECRET_LENGTH) {
                "skeleton.storage.web.public.secret must be at least ${StorageWebProperties.MIN_KEY_SECRET_LENGTH} characters when skeleton.storage.web.public.enabled=true " +
                    "(env <P>_STORAGE_WEB_PUBLIC_SECRET - list it under secrets:)"
            }
            require(baseUrl.isNotBlank()) { "skeleton.storage.web.public.base-url is required when skeleton.storage.web.public.enabled=true (the CDN or public bucket address in front of the public prefix)" }
            require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://") || baseUrl.startsWith("/")) {
                "skeleton.storage.web.public.base-url must be an absolute address (https://cdn.example.com/app) or a same-origin path (/files)"
            }
            require(prefix.trim('/').isNotEmpty()) { "skeleton.storage.web.public.prefix must not be blank" }
        }
        require(!accessTtl.isZero && !accessTtl.isNegative) { "skeleton.storage.web.public.access-ttl must be positive" }
        if (purge.cloudflare.enabled) {
            require(enabled) { "skeleton.storage.web.public.purge.cloudflare.enabled needs skeleton.storage.web.public.enabled=true" }
            // 상대 경로 주소는 CDN 이 모르는 주소다 - purge 에는 CDN 이 캐시한 **절대 주소**가 필요하다
            require(!baseUrl.startsWith("/") || purge.cloudflare.publicOrigin.startsWith("http://") || purge.cloudflare.publicOrigin.startsWith("https://")) {
                "skeleton.storage.web.public.base-url is a same-origin path ($baseUrl): set skeleton.storage.web.public.purge.cloudflare.public-origin (e.g. https://app.example.com) so purge can name the cached absolute URLs"
            }
        }
    }

    /** CDN 이 캐시한 공개 객체의 **절대 주소** - purge 에 보낸다. 상대 경로 base-url 이면 [PublicPurgeProperties.CloudflarePurge.publicOrigin] 을 앞에 붙인다 */
    fun absoluteUrl(publicKey: String): String {
        val origin = if (baseUrl.startsWith("/")) purge.cloudflare.publicOrigin.trimEnd('/') else ""
        return origin + baseUrl.trimEnd('/') + "/" + publicKey
    }

    /** 비밀은 로그 · 오류 메시지에 나오지 않는다 */
    override fun toString(): String = "StoragePublicProperties(enabled=$enabled, secret=${if (secret.isEmpty()) "" else "****"}, prefix=$prefix, baseUrl=$baseUrl)"
}

/** 공개 객체를 지운 뒤 CDN 캐시를 비우는 설정 (`skeleton.storage.web.public.purge.*`) */
data class PublicPurgeProperties(val cloudflare: CloudflarePurge = CloudflarePurge()) {
    /**
     * Cloudflare `POST {api-url}/zones/{zone-id}/purge_cache` `{"files":[...]}` (한 번에 30개까지). API 토큰은 **Zone -> Cache Purge 권한만** 준 것을 쓴다. 기본 꺼짐.
     */
    data class CloudflarePurge(
        val enabled: Boolean = false,
        val zoneId: String = "",
        /** 비밀 - 환경변수 `<P>_STORAGE_WEB_PUBLIC_PURGE_CLOUDFLARE_API_TOKEN`. 로그 · toString 에 나가지 않는다 */
        val apiToken: String = "",
        /** `base-url` 이 상대 경로(`/files`)일 때 purge 가 부를 절대 주소의 앞부분 (예: `https://app.example.com`) */
        val publicOrigin: String = "",
        val apiUrl: String = "https://api.cloudflare.com/client/v4",
        val timeout: java.time.Duration = java.time.Duration.ofSeconds(5),
    ) {
        init {
            if (enabled) {
                require(zoneId.isNotBlank()) { "skeleton.storage.web.public.purge.cloudflare.zone-id is required when purge is enabled (env <P>_STORAGE_WEB_PUBLIC_PURGE_CLOUDFLARE_ZONE_ID)" }
                require(apiToken.isNotBlank()) { "skeleton.storage.web.public.purge.cloudflare.api-token is required when purge is enabled (env <P>_STORAGE_WEB_PUBLIC_PURGE_CLOUDFLARE_API_TOKEN - list it under secrets:)" }
            }
        }

        override fun toString(): String = "CloudflarePurge(enabled=$enabled, zoneId=$zoneId, apiToken=${if (apiToken.isEmpty()) "" else "****"}, publicOrigin=$publicOrigin)"
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
