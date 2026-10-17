package dev.sumin.skeleton.common.upload

import java.time.Duration

/**
 * 업로드 파일의 공개 영역 - storage 가 구현한다(`skeleton.storage.web.public.enabled`). 공개 주소는 **비공개 키에서 계산**되므로(비밀 HMAC) 대응표가 필요 없다.
 * 모든 호출은 멱등이다.
 */
interface PublicUploads {
    /** 이 비공개 키의 공개 주소 - 복사가 되어 있다는 뜻은 아니다 */
    fun urlOf(privateKey: String): String

    /**
     * 이 키의 형식(확장자)이 공개할 수 있는 것인가 - 읽기마다 저장소를 묻지 않고 키만 보고 판정한다. 아니면 응답에 공개 주소를 싣지 않는다.
     * (실제 복사는 원본의 Content-Type 으로 한 번 더 확인한다.) 기본은 참.
     */
    fun mayPublish(privateKey: String): Boolean = true

    /** 이 비공개 키가 공개 영역에서 갖는 키 - 전체 대조가 「줄들이 가리키는 키의 집합」을 만들 때 쓴다 */
    fun publicKeyOf(privateKey: String): String

    /**
     * 공개 영역에 복사한다(있으면 덮어쓴다). 원본의 Content-Type 을 읽어 그대로 쓰고 허용 목록(image/jpeg · png · webp · gif · avif · heic, application/pdf)
     * 밖이면 **공개하지 않고** false 를 돌려준다.
     */
    fun publish(privateKey: String): Boolean

    /** 공개 영역에서 지운다 - 지운 뒤 [PublicUploadPurger] 가 있으면 부른다 */
    fun unpublish(privateKey: String)

    /** 공개 영역에 있는 객체의 키 전부(쪽을 넘겨 가며) - 줄이 가리키지 않는 객체를 찾는 전체 대조용 */
    fun listPublicKeys(): List<String>

    /** 공개 키로 직접 지운다 (줄이 가리키지 않는 객체) - [PublicUploadPurger] 도 부른다 */
    fun deletePublicKey(publicKey: String)
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
