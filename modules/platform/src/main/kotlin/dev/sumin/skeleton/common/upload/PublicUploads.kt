package dev.sumin.skeleton.common.upload

import java.time.Duration

/**
 * 업로드 파일의 공개 영역 - storage 가 구현한다(`skeleton.storage.web.public.enabled`). 공개 주소는 **비공개 키에서 계산**되므로(비밀 HMAC) 대응표가 필요 없다.
 * 모든 호출은 멱등이다.
 */
interface PublicUploads {
    /**
     * 같은 영역의 **하위 접두사만 다른** 사본 - 한 용도(예: 프로필 사진 `a`)의 사본이 다른 용도(board `b`)의 전체 대조에 지워지지 않게 한다. 하위 접두사를 모르는 구현은 자기 자신을 돌려준다
     */
    fun scoped(segment: String): PublicUploads = this

    /**
     * 지금 프로젝트에서 공개 영역이 켜져 있는가 - 빈은 어느 프로젝트든 켜졌으면 만들어지므로 **부를 때마다** 묻는다. 프로젝트 문맥이 없으면 「어느 프로젝트든 켜졌는가」.
     * 꺼져 있으면 호출하는 쪽은 아무것도 하지 않는다(주소도 복사도 큐 줄도 없다). 기본은 참.
     */
    fun enabled(): Boolean = true

    /** 객체를 지운 뒤 CDN 캐시를 비워야 하는가(비우는 구현이 있고 지금 프로젝트에서 켜져 있다) - 아니면 비울 키를 큐에 적지 않는다. 기본 거짓 */
    fun purgeEnabled(): Boolean = false

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
     * 이 비공개 키가 공개 영역에 만드는 객체 전부 - 이미지 모듈이 있으면 `display` 복사본([publicKeyOf])과 썸네일 복사본.
     * 전체 대조는 「줄이 가리키는 공개 객체」를 이것으로 센다(안 그러면 썸네일을 고아로 지운다). 기본은 [publicKeyOf] 하나
     */
    fun publicKeysOf(privateKey: String): List<String> = listOf(publicKeyOf(privateKey))

    /**
     * 이미지 모듈이 있을 때, 이 비공개 키들의 이미지 상태 - 변환이 끝난(READY) 것은 공개 모습(폭 · 높이 · `thumb` · `display` 주소, 다른 형식은 [PublicUploadImage.alternates]),
     * 변환 중(PENDING)이면 [PublicImages.pending], 영구히 공개할 수 없으면(거절 · 못 읽는 형식 · 실패) [PublicImages.refused]. 이미지가 아닌 키(PDF …)와
     * **상태 줄이 없는 키(이미지 모듈을 켜기 전에 올린 옛 업로드)** 는 [PublicImages.unregistered] 에만 있다 - 호출하는 쪽은 그 키를 지금까지처럼 다룬다.
     * 복사가 되어 있다는 뜻은 아니다([urlOf] 와 같다 - 복사는 board 의 맞추기 큐가 한다). 기본은 빈 결과
     */
    fun imagesOf(privateKeys: Collection<String>): PublicImages = PublicImages()

    /**
     * 공개 영역에 복사한다(있으면 덮어쓴다). 원본의 Content-Type 을 읽어 그대로 쓰고 허용 목록(image/jpeg · png · webp · gif · avif · heic, application/pdf)
     * 밖이면 **공개하지 않고** false 를 돌려준다.
     * 이미지 모듈([UploadImages])이 있으면 원본이 아니라 변환본을 복사한다 - 변환 전이면 [UploadVariantsPendingException], 변환할 수 없는 이미지면 false.
     */
    fun publish(privateKey: String): Boolean

    /** 공개 영역에서 **객체만**([publicKeysOf] 전부) 지운다(동기). CDN 캐시는 [purgePublicKey] 가 따로 - 요청 스레드에서 외부 CDN 을 기다리지 않는다 */
    fun unpublish(privateKey: String)

    /** 공개 영역에 있는 객체의 키 전부(쪽을 넘겨 가며) - 줄이 가리키지 않는 객체를 찾는 전체 대조용 */
    fun listPublicKeys(): List<String>

    /** 공개 키로 직접 지운다 (줄이 가리키지 않는 객체) - 객체만, CDN 캐시는 [purgePublicKey] */
    fun deletePublicKey(publicKey: String)

    /** 이 공개 키의 CDN 캐시를 비운다([PublicUploadPurger]). 실패하면 예외 - 호출한 쪽(조정자의 비동기 작업)이 늦춰 다시 한다. 구현이 없으면 아무 일도 안 한다 */
    fun purgePublicKey(publicKey: String) = Unit
}

/**
 * 공개된 이미지 하나 - [width] · [height] 는 `display` 사본의 크기, 주소는 공개 주소(기본 형식: JPEG, 투명하면 PNG).
 * 공개 영역은 정적 CDN 이라 서버가 `Accept` 로 고를 수 없으므로 같은 그림의 다른 형식은 [alternates] 에 형식(MIME)별로 실린다 - 화면이 `<picture><source type=…>` 로 고른다.
 * 앱 안 엔진만 쓰면 비어 있다.
 */
data class PublicUploadImage(
    val width: Int,
    val height: Int,
    val thumb: String,
    val display: String,
    val alternates: Map<String, PublicUploadFormat> = emptyMap(),
)

/** 같은 그림의 다른 형식 하나의 `thumb` · `display` 공개 주소 */
data class PublicUploadFormat(val thumb: String, val display: String)

/** [PublicUploads.imagesOf] 의 답 - 키 -> 준비된 이미지 / 변환 중인 키 / 공개할 수 없는 키 */
data class PublicImages(
    val ready: Map<String, PublicUploadImage> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val refused: Set<String> = emptySet(),
    /** 이미지 형식인데 상태 줄이 없는 키 - 이미지 모듈을 켜기 전에 올린(공개 영역에 원본이 복사됐을 수 있는) 업로드. 맞추기가 이것을 보고 다시 공개한다 */
    val unregistered: Set<String> = emptySet(),
)

/** 비공개 업로드를 짧은 수명의 서명 주소로 읽는다 - 승인 전 · 판 사진을 관리하는 사람이 볼 때 */
fun interface PrivateUploadAccess {
    /** 지금 프로젝트에서 켜져 있는가 (공개 영역과 함께 켜진다) - 꺼져 있으면 서명 열람도 없다. 기본은 참 */
    fun enabled(): Boolean = true


    fun downloadUrl(privateKey: String, ttl: Duration): String

    /** 서명 주소의 기본 수명 */
    val defaultTtl: Duration get() = Duration.ofMinutes(5)
}

/** 공개 객체를 지운 뒤 CDN 캐시를 비우는 자리 - 구현이 없으면 `Cache-Control` max-age 가 지나서야 사라진다 */
fun interface PublicUploadPurger {
    fun purge(publicUrls: Collection<String>)
}
