package dev.sumin.skeleton.storage

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 이 계정이 이 저장소 키의 주인인가 — 앱의 첨부 검사(예: `BoardPolicy.canAttach`)가 접두사 비교 대신 쓴다.
 * 모듈이 `skeleton.storage.web.key-owner` 에 맞는 구현을 빈으로 내고, 앱이 같은 타입의 빈을 두면 그것이 쓰인다.
 *
 * **업로드 HTTP(`StorageController`)의 발급 · 확인은 이 빈을 보지 않는다** — 컨트롤러는 [StorageKeyScheme] 을 직접 쓴다.
 * 그쪽 규칙을 바꾸려면 이 빈이 아니라 `StorageController` 를 교체한다.
 */
fun interface StorageKeyOwnership {
    fun owns(accountId: String, key: String): Boolean
}

/**
 * 서버가 정하는 키의 발급과 소유 확인.
 *
 * - `ACCOUNT_ID`: `<prefix>/<계정 id>/<uuid>/<파일>` — 소유는 접두사 비교.
 * - `OPAQUE`: `<prefix>/o/<uuid>.<mac>/<파일>`, `mac = HMAC-SHA256(key-secret, 계정 id + "\n" + uuid)` 의 앞 32자(hex).
 *   소유는 다시 계산해 상수시간으로 비교한다(DB 없음). 업로드마다 uuid 가 달라 같은 계정의 첨부끼리도 이어지지 않는다.
 *   파일 이름도 두 프로필을 잇는 값이 되므로 클라이언트 파일 이름은 버리고 알려진 확장자(내장 목록, 원본 이름 끝에서 소문자로 비교)만 남긴다(`file[.<ext>]`). 그 밖의 확장자(`kim.minsu` · `example.com` 처럼 이름의 일부일 수 있는 것)는 `file`.
 * - `OPAQUE` 에서도 `ACCOUNT_ID` 꼴의 옛 키는 지금처럼 소유가 확인된다. 다만 `<prefix>/o/` 로 시작하는 키는 MAC 으로만 판정하고, `<uuid>.<mac>/` 뒤 꼬리는 `file[.<소문자 영숫자 1~8자>]` 만 인정한다
 *   (계정 id 가 `o` 인 계정이 옛 접두사로 남의 `OPAQUE` 키를 주장하지 못하게).
 * - 유효한 [ObjectKey] 가 아니거나(`..` · 제어문자 …) 접두사 뒤가 비어 있으면 어느 방식에서나 소유가 아니다.
 *
 * 프로젝트 모드에서는 규칙(접두사 · 소유 방식 · 비밀)을 **부를 때마다** 지금 프로젝트의 것으로 읽는다 — A 의 비밀로 만든 OPAQUE 키는 B 에서 소유로 인정되지 않는다.
 */
class StorageKeyScheme(private val webOf: () -> StorageWebProperties) : StorageKeyOwnership {
    constructor(web: StorageWebProperties) : this({ web })

    fun issue(accountId: String, fileName: String): String {
        val web = webOf()
        val prefix = web.keyPrefix.trim('/')
        val uuid = UUID.randomUUID().toString()
        return when (web.keyOwner) {
            StorageKeyOwner.ACCOUNT_ID -> "$prefix/$accountId/$uuid/${safeFileName(fileName)}"
            StorageKeyOwner.OPAQUE -> "$prefix/$OPAQUE_SEGMENT/$uuid.${mac(web, accountId, uuid)}/${opaqueFileName(fileName)}"
        }
    }

    override fun owns(accountId: String, key: String): Boolean {
        if (runCatching { ObjectKey(key) }.isFailure) return false
        val web = webOf()
        val prefix = web.keyPrefix.trim('/')
        val opaquePrefix = "$prefix/$OPAQUE_SEGMENT/"
        if (web.keyOwner == StorageKeyOwner.OPAQUE && key.startsWith(opaquePrefix)) return ownsOpaque(web, opaquePrefix, accountId, key)
        val own = "$prefix/$accountId/"
        return key.startsWith(own) && key.length > own.length
    }

    private fun ownsOpaque(web: StorageWebProperties, opaquePrefix: String, accountId: String, key: String): Boolean {
        val rest = key.removePrefix(opaquePrefix)
        val segment = rest.substringBefore('/', missingDelimiterValue = "")
        val tail = rest.substringAfter('/', missingDelimiterValue = "")
        if (!OPAQUE_TAIL_FORMAT.matches(tail)) return false
        val uuid = segment.substringBefore('.')
        val presented = segment.substringAfter('.', missingDelimiterValue = "")
        if (!UUID_FORMAT.matches(uuid) || !MAC_FORMAT.matches(presented)) return false
        return MessageDigest.isEqual(mac(web, accountId, uuid).toByteArray(), presented.toByteArray())
    }

    /** 원본 이름(자르기 · 치환 전)의 끝에서 알려진 확장자만 남긴다 */
    private fun opaqueFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (ext in KNOWN_EXTENSIONS) "file.$ext" else "file"
    }

    private fun safeFileName(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
            .take(100)
            .ifBlank { "file" }

    private fun mac(web: StorageWebProperties, accountId: String, uuid: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(web.keySecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hmac.doFinal("$accountId\n$uuid".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(MAC_LENGTH)
    }

    private companion object {
        const val OPAQUE_SEGMENT = "o"
        const val MAC_LENGTH = 32
        val UUID_FORMAT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val MAC_FORMAT = Regex("[0-9a-f]{$MAC_LENGTH}")
        val OPAQUE_TAIL_FORMAT = Regex("file(\\.[a-z0-9]{1,8})?")
        val KNOWN_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "svg", "pdf", "txt", "md", "csv", "json", "xml",
            "zip", "gz", "mp3", "wav", "m4a", "mp4", "mov", "webm", "docx", "xlsx", "pptx", "hwp",
        )
    }
}
