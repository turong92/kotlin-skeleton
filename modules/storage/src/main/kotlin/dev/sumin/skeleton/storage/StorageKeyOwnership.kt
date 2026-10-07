package dev.sumin.skeleton.storage

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 이 계정이 이 저장소 키의 주인인가 — 앱의 첨부 검사(예: `BoardPolicy.canAttach`)가 접두사 비교 대신 쓴다.
 * 모듈이 `skeleton.storage.web.key-owner` 에 맞는 구현을 빈으로 내고, 앱이 같은 타입의 빈을 두면 그것이 쓰인다.
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
 * - `OPAQUE` 에서도 `ACCOUNT_ID` 꼴의 옛 키는 지금처럼 소유가 확인된다. 단 계정 id 가 `o` 인 계정은 옛 접두사로 `OPAQUE` 키를 주장할 수 없게 옛 꼴에서 빠진다.
 */
class StorageKeyScheme(private val web: StorageWebProperties) : StorageKeyOwnership {
    private val prefix = web.keyPrefix.trim('/')

    fun issue(accountId: String, fileName: String): String {
        val uuid = UUID.randomUUID().toString()
        return when (web.keyOwner) {
            StorageKeyOwner.ACCOUNT_ID -> "$prefix/$accountId/$uuid/$fileName"
            StorageKeyOwner.OPAQUE -> "$prefix/$OPAQUE_SEGMENT/$uuid.${mac(accountId, uuid)}/$fileName"
        }
    }

    override fun owns(accountId: String, key: String): Boolean {
        if (accountId != OPAQUE_SEGMENT && key.startsWith("$prefix/$accountId/")) return true
        if (web.keyOwner != StorageKeyOwner.OPAQUE) return false
        val rest = key.removePrefix("$prefix/$OPAQUE_SEGMENT/").takeIf { it != key } ?: return false
        val segment = rest.substringBefore('/', missingDelimiterValue = "")
        val uuid = segment.substringBefore('.')
        val presented = segment.substringAfter('.', missingDelimiterValue = "")
        if (!UUID_FORMAT.matches(uuid) || !MAC_FORMAT.matches(presented)) return false
        return MessageDigest.isEqual(mac(accountId, uuid).toByteArray(), presented.toByteArray())
    }

    private fun mac(accountId: String, uuid: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(web.keySecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hmac.doFinal("$accountId\n$uuid".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(MAC_LENGTH)
    }

    private companion object {
        const val OPAQUE_SEGMENT = "o"
        const val MAC_LENGTH = 32
        val UUID_FORMAT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val MAC_FORMAT = Regex("[0-9a-f]{$MAC_LENGTH}")
    }
}
