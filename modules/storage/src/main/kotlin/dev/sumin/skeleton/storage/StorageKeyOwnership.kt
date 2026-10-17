package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.upload.GuestUploadKeys
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

    /** 지금 프로젝트의 규칙 */
    fun properties(): StorageWebProperties = webOf()

    /** 비회원 업로드 키 `<prefix>/g/<uuid>.<mac>/file.<ext>` - 확장자는 허용한 Content-Type 에서 정한다 */
    fun issueGuest(contentType: String): String {
        val web = webOf()
        val ext = StorageGuestProperties.IMAGE_EXTENSIONS.getValue(contentType.trim().lowercase())
        val uuid = UUID.randomUUID().toString()
        return "${web.keyPrefix.trim('/')}/$GUEST_SEGMENT/$uuid.${guestMac(web, uuid)}/file.$ext"
    }

    /** 비공개 키의 공개 영역 키 `<prefix>/b/<hmac32>.<ext>` (`b/` = board 가 쓰는 하위 접두사 - 전체 대조는 그 안만 본다) - 비공개 키에서만 계산된다(대응표 없음). 확장자는 알려진 것만 남는다 */
    fun publicKeyOf(privateKey: String): String {
        val pub = webOf().`public`
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(pub.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val id = hmac.doFinal("public\n$privateKey".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(MAC_LENGTH)
        val ext = privateKey.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return "${pub.prefix.trim('/')}/$BOARD_PUBLIC_SEGMENT/$id" + if (ext in KNOWN_EXTENSIONS) ".$ext" else ""
    }

    /** 비회원 업로드 키인가 - 꼴 + MAC. 꺼져 있으면 참이 되는 키가 없다 */
    fun isGuestKey(key: String): Boolean {
        val web = webOf()
        if (!web.guest.enabled || runCatching { ObjectKey(key) }.isFailure) return false
        val guestPrefix = "${web.keyPrefix.trim('/')}/$GUEST_SEGMENT/"
        if (!key.startsWith(guestPrefix)) return false
        val rest = key.removePrefix(guestPrefix)
        val segment = rest.substringBefore('/', missingDelimiterValue = "")
        val tail = rest.substringAfter('/', missingDelimiterValue = "")
        val uuid = segment.substringBefore('.')
        val presented = segment.substringAfter('.', missingDelimiterValue = "")
        if (!GUEST_TAIL_FORMAT.matches(tail) || !UUID_FORMAT.matches(uuid) || !MAC_FORMAT.matches(presented)) return false
        return MessageDigest.isEqual(guestMac(web, uuid).toByteArray(), presented.toByteArray())
    }

    override fun owns(accountId: String, key: String): Boolean {
        if (runCatching { ObjectKey(key) }.isFailure) return false
        val web = webOf()
        val prefix = web.keyPrefix.trim('/')
        // 공개 영역의 키는 누구의 업로드 키도 아니다
        if (web.`public`.enabled && key.startsWith(web.`public`.prefix.trim('/') + "/")) return false
        // 비회원 업로드 접두사는 누구의 계정 키도 아니다 (계정 id 가 `g` 인 계정이 남의 비회원 키를 주장하지 못하게)
        if (web.guest.enabled && key.startsWith("$prefix/$GUEST_SEGMENT/")) return false
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

    private fun guestMac(web: StorageWebProperties, uuid: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(web.guest.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hmac.doFinal("guest\n$uuid".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(MAC_LENGTH)
    }

    companion object {
        /** 공개 접두사 아래 board 가 쓰는 하위 접두사 */
        const val BOARD_PUBLIC_SEGMENT = "b"
        private const val GUEST_SEGMENT = "g"
        val GUEST_TAIL_FORMAT = Regex("file\\.(jpg|png|webp|gif)")
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

/** platform 의 [GuestUploadKeys] 를 [StorageKeyScheme] 의 비회원 키 확인으로 잇는다 (키 방식 타입과 한 빈이 되지 않게 따로 둔다) */
class StorageGuestKeys(private val scheme: StorageKeyScheme) : GuestUploadKeys {
    override fun isGuestKey(key: String): Boolean = scheme.isGuestKey(key)
}
