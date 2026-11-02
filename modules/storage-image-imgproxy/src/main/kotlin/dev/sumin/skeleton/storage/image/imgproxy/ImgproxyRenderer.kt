package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.image.ImageInfo
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageRenderer
import dev.sumin.skeleton.storage.image.Rendered
import dev.sumin.skeleton.storage.image.VariantSpec
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * imgproxy(libvips) 컨테이너로 변환하는 선택 엔진 - HEIC · AVIF 입력, **WebP 출력**, 메모리가 적다. 서버(컨테이너) 하나가 더 뜬다.
 *
 * - **원본은 비공개 그대로다**: imgproxy 에는 설정된 저장소가 서명한 **짧은 수명의 원본 주소**([sourceUrl])만 준다. 임의 주소를 받는 길이 없다(SSRF) -
 *   imgproxy 쪽에도 `IMGPROXY_ALLOWED_SOURCES` 로 저장소 주소만 허용해 이중으로 막는다.
 * - **imgproxy 요청 주소는 key/salt 로 서명한다**(HMAC-SHA256) - 서명 없는 imgproxy 는 쓰지 않는다. 처리 옵션을 남이 바꿀 수 없다.
 * - 출력은 메타데이터 없는 WebP(`strip_metadata` · `strip_color_profile`), EXIF 방향은 픽셀에 반영(`auto_rotate`), 키우지 않는다(`enlarge` 끔).
 * - 요청마다 시간 상한([ImgproxyProperties.timeout]), 응답 크기 상한([ImgproxyProperties.maxResponseBytes]). 원본 해상도 · 바이트 상한은 imgproxy 가 한 번 더 지킨다(`IMGPROXY_MAX_SRC_RESOLUTION` · `IMGPROXY_MAX_SRC_FILE_SIZE`) - `docs/deploy.md`.
 * - 한계: 움직이는 이미지는 첫 장만 변환한다(imgproxy 기본). 크기는 머리에서 읽은 그대로라 EXIF 회전을 반영하지 않는다(변환본의 크기는 맞다).
 */
class ImgproxyRenderer(
    private val properties: ImgproxyProperties,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(properties.timeout).followRedirects(HttpClient.Redirect.NEVER).build(),
    /** 원본의 서명 읽기 주소 - 저장소가 서명한 것만 */
    private val sourceUrl: (ObjectKey, Duration) -> URI,
) : ImageRenderer {
    private val keyBytes = hex(properties.key)
    private val saltBytes = hex(properties.salt)
    private val base = properties.url.trimEnd('/')

    override val kinds: Set<DetectedKind> = setOf(
        DetectedKind.JPEG, DetectedKind.PNG, DetectedKind.GIF, DetectedKind.WEBP, DetectedKind.AVIF, DetectedKind.HEIC, DetectedKind.HEIF,
    )

    override fun probe(head: ByteArray, kind: DetectedKind): ImageInfo {
        val (width, height) = ImageHeaderSize.of(head, kind)
            ?: throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The image header cannot be read")
        return ImageInfo(kind.mime, width, height)
    }

    override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered> =
        throw UnsupportedOperationException("imgproxy reads the original through a signed address - call render(key, …)")

    override fun render(key: ObjectKey, source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered> {
        val original = sourceUrl(key, properties.sourceTtl).toString()
        return specs.map { spec ->
            val bytes = fetch(signedUrl(original, spec))
            val (w, h) = ImageHeaderSize.webp(bytes) ?: throw IOException("imgproxy returned something that is not a WebP image")
            Rendered(spec.name, bytes, "image/webp", w, h)
        }
    }

    /** `<imgproxy>/<서명>/<처리 옵션>/<원본 주소(base64url)>.webp` - 서명은 HMAC-SHA256(key, salt + 경로) */
    internal fun signedUrl(original: String, spec: VariantSpec): String {
        val path = "/rs:fit:${spec.maxWidth}:${spec.maxHeight}/el:0/q:${spec.quality}/sm:1/kcr:0/scp:1/ar:1/" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(original.toByteArray(Charsets.UTF_8)) + ".webp"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyBytes, "HmacSHA256")) }
        mac.update(saltBytes)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(path.toByteArray(Charsets.UTF_8)))
        return "$base/$signature$path"
    }

    private fun fetch(url: String): ByteArray {
        val response = try {
            http.send(HttpRequest.newBuilder(URI(url)).timeout(properties.timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while waiting for imgproxy", e)
        }
        response.body().use { body ->
            when (response.statusCode()) {
                200 -> return readLimited(body)
                // 내용 때문에 못 만든다(깨짐 · 지원 안 함 · imgproxy 의 해상도 상한) - 영구 실패. 운영 모드의 imgproxy 는 이유를 응답에 싣지 않는다("Invalid source image")
                422 -> throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "imgproxy cannot convert the image: ${String(body.readNBytes(ERROR_BYTES), Charsets.UTF_8).take(200)}")
                // 서명이 안 맞는다 - 설정 오류. 다시 해도 같으니 큐가 소진시켜 FAILED 로 남긴다
                401, 403 -> throw IllegalStateException("imgproxy rejected the signed request (status ${response.statusCode()}) - check skeleton.storage.image.imgproxy.key/salt against IMGPROXY_KEY/IMGPROXY_SALT and IMGPROXY_ALLOWED_SOURCES")
                else -> throw IOException("imgproxy answered ${response.statusCode()}: ${String(body.readNBytes(ERROR_BYTES), Charsets.UTF_8).take(200)}")
            }
        }
    }

    private fun readLimited(body: InputStream): ByteArray {
        val bytes = body.readNBytes((properties.maxResponseBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt())
        if (bytes.size > properties.maxResponseBytes) throw ImageRejectedException(ImageReasons.IMAGE_TOO_LARGE, "imgproxy's answer is larger than ${properties.maxResponseBytes} bytes")
        return bytes
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val ERROR_BYTES = 2048
    }
}
