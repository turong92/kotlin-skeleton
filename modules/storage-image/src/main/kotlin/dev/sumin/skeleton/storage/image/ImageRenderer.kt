package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import java.io.InputStream

/** 엔진이 머리에서 읽은 이미지 정보 - [width] · [height] 는 **회전을 반영한** 크기, [contentType] 은 원본의 형식 */
data class ImageInfo(
    val contentType: String,
    val width: Int,
    val height: Int,
    /** 움직이는 이미지 - 썸네일만 첫 장으로 만든다(처리기가 표시본 요청을 빼고, 움직임을 남긴 메타데이터 없는 사본을 따로 만든다) */
    val animated: Boolean = false,
    /** 엔진이 픽셀에 반영할 EXIF 방향 1..8 (엔진 안에서만 쓰는 값) */
    val orientation: Int = 1,
    /** 투명한 픽셀이 있을 수 있다 - 엔진이 기본 형식을 JPEG 대신 PNG 로 고르는 데 쓴다(머리에서 알 수 있는 만큼) */
    val transparent: Boolean = false,
)

/** 만들 변환본 하나 - 긴 변을 [maxWidth] x [maxHeight] 상자에 맞춘다(키우지 않는다) */
data class VariantSpec(val name: String, val maxWidth: Int, val maxHeight: Int, val quality: Int)

/**
 * 만든 변환본 - 메타데이터가 없는 바이트. [contentType] 은 `image/jpeg` · `image/png` · `image/webp`.
 * [alternates] 는 같은 그림의 다른 형식(예: 기본이 JPEG 일 때 WebP, 이름 · 크기 같음) - 엔진이 둘을 만들면 처리기가 함께 올리고 상태 줄에 적는다
 */
class Rendered(val name: String, val bytes: ByteArray, val contentType: String, val width: Int, val height: Int, val alternates: List<Rendered> = emptyList()) {
    /** 저장소 키 · 상태 표에 쓰는 확장자 */
    val extension: String get() = extensionOf(contentType)
}

fun extensionOf(contentType: String): String = when (contentType) {
    "image/jpeg" -> "jpg"
    else -> contentType.substringAfter('/')
}

/** 내용이 이미지로 읽히지 않는다(깨짐 · 지원하지 않는 변형) - 영구 실패라 작업이 [code] 로 `REJECTED` 처리한다. 저장소 · 일시 오류는 이 예외가 아니라 그대로 던진다(큐가 다시 한다) */
class ImageRejectedException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 변환 엔진 - `storage-image-imageio`(앱 안 순수 자바)가 기본이고, `storage-image-imgproxy`(HEIC 입력 · WebP 출력) · `storage-image-pdfbox`(PDF 1쪽)가 같은 자리에 끼운다.
 * 엔진 여럿이 함께 있어도 된다: 어느 엔진이 어느 내용을 맡을지는 **엔진이 [kinds] 로 고르고**, [ImageRenderers] 가 `@Order` 순으로 그 종류를 가진 첫 엔진에 맡긴다.
 * 구현은 상태를 갖지 않고 스레드에 안전해야 한다. 메모리 · 시간 상한은 엔진 몫이다(처리기는 픽셀 상한을 [probe] 의 크기로 먼저 확인한다).
 */
interface ImageRenderer {
    /** 이 엔진이 읽을 수 있는 내용 종류(매직 바이트로 판정한 것) */
    val kinds: Set<DetectedKind>

    /** 앞 256KB 만으로 크기 · 방향 · 움직임을 읽는다 - **전체를 디코드하지 않는다**. 읽을 수 없으면 [ImageRejectedException] */
    fun probe(head: ByteArray, kind: DetectedKind): ImageInfo

    /** [specs] 마다 변환본 하나를 같은 순서로 돌려준다. [source] 는 원본 전체를 새 스트림으로 연다(엔진이 닫는다). 메타데이터 없이, 회전을 픽셀에 반영해서 */
    fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered>

    /** 원본 객체 키를 아는 호출 - 처리기는 이것을 부른다. 원본을 스트림이 아니라 서명 주소로 넘기는 엔진(imgproxy)이 재정의하고, 기본은 [source] 스트림으로 [render] 한다 */
    fun render(key: ObjectKey, source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered> = render(source, info, specs)
}

/** 엔진 목록 - 종류로 고른다 */
class ImageRenderers(private val renderers: List<ImageRenderer>) {
    /** 이 종류를 읽을 엔진(순서대로 첫 번째), 없으면 null */
    fun forKind(kind: DetectedKind): ImageRenderer? = renderers.firstOrNull { kind in it.kinds }

    /** 이 종류가 변환 대상인가 - 엔진이 읽거나, 이미지인데 엔진이 못 읽는 것(그래도 상태 줄은 남긴다: `UNSUPPORTED`) */
    fun handles(kind: DetectedKind): Boolean = kind in IMAGE_KINDS || forKind(kind) != null

    companion object {
        val IMAGE_KINDS = setOf(DetectedKind.JPEG, DetectedKind.PNG, DetectedKind.GIF, DetectedKind.WEBP, DetectedKind.AVIF, DetectedKind.HEIC, DetectedKind.HEIF)
    }
}
