package dev.sumin.skeleton.storage

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 앞 바이트(매직 바이트)로 알아낸 내용 종류 - [mime] 은 판정한 형식 */
enum class DetectedKind(val mime: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    GIF("image/gif"),
    WEBP("image/webp"),
    AVIF("image/avif"),
    HEIC("image/heic"),

    /** `mif1` · `msf1` 브랜드 - HEIC 인지 AVIF 인지 머리만으로는 모른다 */
    HEIF("image/heif"),
    PDF("application/pdf"),

    /** zip 계열(docx · xlsx · pptx · hwpx · zip) - 안을 열어 구분하지 않는다 */
    ZIP("application/zip"),

    /** OLE 복합 문서(hwp · 옛 doc · xls · ppt) */
    OLE("application/x-ole-storage"),
    MP4("video/mp4"),
    WEBM("video/webm"),
    MP3("audio/mpeg"),
    WAV("audio/wav"),

    /** 스크립트를 담을 수 있는 마크업 - 서명이 보이면 어떤 형식으로 선언됐든 받지 않는다(선언이 같은 마크업일 때만 예외) */
    HTML("text/html"),
    SVG("image/svg+xml"),
}

/**
 * 알려진 형식 표 - 선언한 Content-Type 을 믿지 않고 내용의 서명(앞 바이트)으로 종류를 판정한다.
 * 표에 없는 형식은 null(판정 못 함): 선언이 표 안의 형식이면 거절, 표 밖(예: `application/octet-stream` · `application/gzip`)이면 확인할 수 없어 통과시킨다 - 모듈 문서의 한계선.
 */
object UploadMagicBytes {
    /** 판정에 쓰는 앞 바이트 수 (완료 호출이 이만큼 범위로 읽는다) */
    const val HEAD_BYTES = 256 * 1024

    fun detect(head: ByteArray): DetectedKind? {
        val n = head.size
        fun at(i: Int, vararg bytes: Int) = n >= i + bytes.size && bytes.indices.all { head[i + it] == bytes[it].toByte() }
        fun ascii(i: Int, text: String) = n >= i + text.length && text.indices.all { head[i + it] == text[it].code.toByte() }
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> DetectedKind.JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> DetectedKind.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> DetectedKind.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> DetectedKind.WEBP
            ascii(0, "RIFF") && ascii(8, "WAVE") -> DetectedKind.WAV
            ascii(0, "%PDF-") -> DetectedKind.PDF
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> DetectedKind.ZIP
            at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> DetectedKind.OLE
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> DetectedKind.WEBM
            ascii(0, "ID3") || (n >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> DetectedKind.MP3
            n >= 12 && ascii(4, "ftyp") -> ftypKind(String(head, 8, 4, Charsets.ISO_8859_1))
            else -> markup(head)
        }
    }

    private fun ftypKind(brand: String): DetectedKind = when (brand) {
        "avif", "avis" -> DetectedKind.AVIF
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs" -> DetectedKind.HEIC
        "mif1", "msf1" -> DetectedKind.HEIF
        else -> DetectedKind.MP4   // isom · mp41 · mp42 · avc1 · M4A · M4V · qt 등 - ISO 기본 미디어 계열
    }

    /** 맨 앞(BOM · 공백 뒤)이 HTML · SVG 로 시작하는가 */
    private fun markup(head: ByteArray): DetectedKind? {
        var start = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) start = 3
        while (start < head.size && head[start].toInt().toChar().isWhitespace()) start++
        val text = String(head, start, minOf(head.size - start, 1024), Charsets.ISO_8859_1).lowercase()
        return when {
            text.startsWith("<svg") -> DetectedKind.SVG
            text.startsWith("<?xml") && "<svg" in text -> DetectedKind.SVG
            HTML_STARTS.any { text.startsWith(it) } -> DetectedKind.HTML
            else -> null
        }
    }

    private val HTML_STARTS = listOf("<!doctype html", "<html", "<head", "<body", "<script", "<iframe")

    /** 선언한 Content-Type 이 가리키는 형식 계열 - 표에 없으면 null(확인할 수 없는 선언) */
    fun declaredKinds(declared: String?): Set<DetectedKind>? {
        val type = declared?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            type == "image/jpeg" || type == "image/jpg" || type == "image/pjpeg" -> setOf(DetectedKind.JPEG)
            type == "image/png" -> setOf(DetectedKind.PNG)
            type == "image/gif" -> setOf(DetectedKind.GIF)
            type == "image/webp" -> setOf(DetectedKind.WEBP)
            type == "image/avif" -> setOf(DetectedKind.AVIF, DetectedKind.HEIF)
            type in HEIC_TYPES -> setOf(DetectedKind.HEIC, DetectedKind.HEIF)
            type == "application/pdf" -> setOf(DetectedKind.PDF)
            type == "application/zip" || type == "application/x-zip-compressed" || type.startsWith("application/vnd.openxmlformats-officedocument.") ||
                type == "application/vnd.hancom.hwpx" || type == "application/haansofthwpx" || type == "application/x-hwpx" -> setOf(DetectedKind.ZIP)
            type in OLE_TYPES -> setOf(DetectedKind.OLE)
            type == "video/mp4" || type == "video/quicktime" || type == "video/x-m4v" || type == "audio/mp4" || type == "audio/x-m4a" || type == "audio/m4a" -> setOf(DetectedKind.MP4)
            type == "video/webm" || type == "audio/webm" -> setOf(DetectedKind.WEBM)
            type == "audio/mpeg" || type == "audio/mp3" -> setOf(DetectedKind.MP3)
            type == "audio/wav" || type == "audio/x-wav" || type == "audio/wave" || type == "audio/vnd.wave" -> setOf(DetectedKind.WAV)
            type == "text/html" -> setOf(DetectedKind.HTML)
            type == "image/svg+xml" -> setOf(DetectedKind.SVG)
            else -> null
        }
    }

    /** 텍스트로 선언한 파일인가(HTML 은 마크업이라 따로) */
    fun declaredText(declared: String?): Boolean {
        val type = declared?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return (type.startsWith("text/") && type != "text/html") || type == "application/json" || type == "application/xml"
    }

    /** NUL 이 없고 UTF-8 로 읽히는가 - 머리에서 잘린 마지막 글자는 봐준다 */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.size + 1), false).isError
    }

    private val HEIC_TYPES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")
    private val OLE_TYPES = setOf(
        "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
    )
}
