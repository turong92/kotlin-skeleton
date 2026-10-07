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
