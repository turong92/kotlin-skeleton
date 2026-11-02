package dev.sumin.skeleton.common.upload

import java.time.Duration

/** 이미지 원본 하나의 변환 상태 */
enum class UploadImageState {
    /** 변환 작업을 기다리는 중 (또는 도는 중) */
    PENDING,

    /** 변환본이 준비됐다 */
    READY,

    /** 이미지가 아니거나 상한을 넘었거나 디코드할 수 없다 - 영구. 원본은 남아 있지만 남에게는 보이지 않는다 */
    REJECTED,

    /** 엔진이 읽을 수 없는 형식(예: HEIC) - 영구 */
    UNSUPPORTED,

    /** 재시도를 다 썼다 */
    FAILED,
}

/**
 * 만들어 둔 변환본 하나 - [key] 는 저장소의 객체 키(원본을 그대로 가리킬 수도 있다: 움직이는 이미지의 `display`).
 * 이 객체는 **누구나 읽을 수 있는 형식**(JPEG, 투명하면 PNG - 엔진이 WebP 만 만들면 WebP)이고, 같은 그림의 다른 형식(예: WebP)이 있으면 [alternates] 에 있다
 * (크기는 같다). 브라우저의 `Accept` 로 고르려면 [forAccept] - 앱 안 엔진은 형식이 하나뿐이라 [alternates] 가 비어 있고, 그때도 호출이 깨지지 않는다.
 */
data class UploadImageVariant(
    val key: String,
    val width: Int,
    val height: Int,
    val contentType: String,
    val alternates: List<UploadImageVariant> = emptyList(),
) {
    /** `Accept` 헤더(없어도 된다)가 받겠다고 **명시한** 형식의 변환본을 고른다 - 와일드카드 범위(image 전체)는 기본 형식이 받으므로 대안을 고르지 않는다. 맞는 게 없으면 자신 */
    fun forAccept(accept: String?): UploadImageVariant =
        alternates.firstOrNull { acceptsExplicitly(accept, it.contentType) } ?: this

    /** 같은 변환본을 이 형식 하나로 보는 목록 - 기본 형식이 먼저 */
    fun formats(): List<UploadImageVariant> = listOf(copy(alternates = emptyList())) + alternates

    private fun acceptsExplicitly(accept: String?, type: String): Boolean {
        if (accept.isNullOrBlank()) return false
        return accept.split(',').any { range ->
            val parts = range.split(';').map { it.trim() }
            val q = parts.drop(1).firstOrNull { it.startsWith("q=", ignoreCase = true) }?.substring(2)?.toDoubleOrNull() ?: 1.0
            parts[0].equals(type, ignoreCase = true) && q > 0.0
        }
    }
}

/** 이미지 하나의 상태. [width] · [height] 는 회전을 반영한 원본 크기, [variants] 는 [UploadImages.THUMB] · [UploadImages.DISPLAY] 로 이름 붙는다(READY 일 때만 비어 있지 않다) */
data class UploadImageStatus(
    val state: UploadImageState,
    val width: Int? = null,
    val height: Int? = null,
    val variants: Map<String, UploadImageVariant> = emptyMap(),
)

/**
 * 업로드한 이미지의 변환본(썸네일 · 표시본) - `storage-image` 가 구현한다. 빈이 없으면 board · review · storage 는 지금까지처럼 원본을 다룬다.
 * 이 포트가 있는 앱은 **남에게(공개 영역 · 다른 계정) 원본을 주지 않는다** - 변환본(메타데이터 없음)만 간다.
 * 모든 키는 비공개 업로드 키다. 호출은 멱등이다.
 */
interface UploadImages {
    /** 변환본이 놓이는 저장소 접두사 - 업로드 `key-prefix` · 공개 `prefix` 와 겹치면 storage 가 기동을 막는다 */
    val variantPrefix: String

    /** 이 키의 상태 - 상태 줄이 없으면 null (이미지가 아니거나 아직 등록되지 않았다). 저장소를 읽지 않는다 */
    fun status(privateKey: String): UploadImageStatus?

    /** 여러 키의 상태 - 줄이 있는 키만 담긴다 */
    fun views(privateKeys: Collection<String>): Map<String, UploadImageStatus> =
        privateKeys.mapNotNull { key -> status(key)?.let { key to it } }.toMap()

    /** READY 변환본 하나의 서명 읽기 주소 - 아직이거나 그 이름의 변환본이 없으면 null (원본 키를 서명해 주지 않는다) */
    fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String?

    /**
     * [signedVariantUrl] 에 브라우저의 `Accept` 를 더한다 - 그 형식의 대안(WebP)이 있으면 그것을, 없으면 기본 형식을 서명한다.
     * 응답이 `Accept` 에 따라 달라지므로 캐시되는 응답이면 `Vary: Accept` 를 붙인다. 기본 구현은 `Accept` 를 무시한다.
     */
    fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration, accept: String?): String? = signedVariantUrl(privateKey, variant, ttl)

    /** 공개 영역에 복사할 변환본(READY 일 때만, 아니면 빈 맵) - 이름은 [THUMB] · [DISPLAY]. 다른 형식은 각 변환본의 [UploadImageVariant.alternates] */
    fun publicVariants(privateKey: String): Map<String, UploadImageVariant> =
        status(privateKey)?.takeIf { it.state == UploadImageState.READY }?.variants.orEmpty()

    /**
     * 완료 호출 없이 쓰이는 키를 처음 쓸 때 등록한다(지연 등록) - 줄이 없고 이미지 형식이면 PENDING 줄 + 작업. 줄이 있으면 아무 일도 안 한다.
     * 호출하는 쪽은 「아직 아님」으로 답한다.
     */
    fun ensure(privateKey: String)

    /** 원본이 지워졌다 - 이 키들의 상태 줄과 변환본을 지운다. 원본을 지운 **뒤에** 부른다 */
    fun forget(privateKeys: Collection<String>)

    companion object {
        const val THUMB = "thumb"
        const val DISPLAY = "display"
    }
}

/** 변환이 끝나지 않아 지금은 공개할 수 없다 - 호출한 쪽(board 의 맞추기 큐)이 늦춰 다시 한다 */
class UploadVariantsPendingException(privateKey: String) : RuntimeException("Image variants are not ready yet: $privateKey")
