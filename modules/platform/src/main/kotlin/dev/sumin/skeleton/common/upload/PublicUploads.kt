package dev.sumin.skeleton.common.upload

import java.time.Duration

/**
 * 업로드 파일의 공개 영역 - storage 가 구현한다(`skeleton.storage.web.public.enabled`). 공개 주소는 **비공개 키에서 계산**되므로(비밀 HMAC) 대응표가 필요 없다.
 * 모든 호출은 멱등이다.
 */
interface PublicUploads {
    /** 이 비공개 키의 공개 주소 - 복사가 되어 있다는 뜻은 아니다 */
    fun urlOf(privateKey: String): String

    /** 공개 영역에 복사한다(있으면 덮어쓴다) */
    fun publish(privateKey: String)

    /** 공개 영역에서 지운다 - 지운 뒤 [PublicUploadPurger] 가 있으면 부른다 */
    fun unpublish(privateKey: String)
}

/** 비공개 업로드를 짧은 수명의 서명 주소로 읽는다 - 승인 전 · 판 사진을 관리하는 사람이 볼 때 */
fun interface PrivateUploadAccess {
    fun downloadUrl(privateKey: String, ttl: Duration): String

    /** 서명 주소의 기본 수명 */
    val defaultTtl: Duration get() = Duration.ofMinutes(5)
}

/** 공개 객체를 지운 뒤 CDN 캐시를 비우는 자리 - 구현이 없으면 `Cache-Control` max-age 가 지나서야 사라진다 */
fun interface PublicUploadPurger {
    fun purge(publicUrls: Collection<String>)
}
