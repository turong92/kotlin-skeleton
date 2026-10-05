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
    /** 서버가 정하는 키의 맨 앞 구간: `<keyPrefix>/<계정 id>/<uuid>/<파일 이름>` */
    val keyPrefix: String = "uploads",
)
