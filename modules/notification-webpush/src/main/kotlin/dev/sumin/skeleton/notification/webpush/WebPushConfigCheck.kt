package dev.sumin.skeleton.notification.webpush

import java.security.Signature
import java.security.interfaces.ECPrivateKey

/** 검사를 통과한 VAPID 키 쌍 - 개인 키는 [toString] 에 나오지 않는다 */
class VapidKeys internal constructor(internal val privateKey: ECPrivateKey, val publicKey: String) {
    override fun toString(): String = "VapidKeys"
}

/** VAPID 설정이 쓸 수 있는지 - 센더를 세울지(`keysOf`)와 기동 검사(`problems`)가 같은 판정을 쓴다. 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다 */
object WebPushConfigCheck {
    const val PUBLIC_PROPERTY = "skeleton.notification.webpush.public-key"
    const val PRIVATE_PROPERTY = "skeleton.notification.webpush.private-key"
    const val SUBJECT_PROPERTY = "skeleton.notification.webpush.subject"
    const val ALLOWED_PROPERTY = "skeleton.notification.webpush.allowed-hosts"
    const val PUBLIC_ENV = "<P>_NOTIFICATION_WEBPUSH_VAPID_PUBLIC_KEY"
    const val PRIVATE_ENV = "<P>_NOTIFICATION_WEBPUSH_VAPID_PRIVATE_KEY"
    const val SUBJECT_ENV = "<P>_NOTIFICATION_WEBPUSH_SUBJECT"

    /** 센더를 세울 수 있으면 키 쌍, 아니면 null (이유는 [problems]) */
    fun keysOf(properties: WebPushProperties): VapidKeys? {
        if (keyProblems(properties).isNotEmpty()) return null
        return VapidKeys(WebPushKeys.privateKey(WebPushKeys.decode(properties.privateKey)), properties.publicKey.trim())
    }

    /** 이 설정으로는 푸시를 못 보낸다는 이유들 - 비면 쓸 수 있다 */
    fun problems(properties: WebPushProperties): List<String> = keyProblems(properties) + buildList {
        val subject = properties.subject.trim()
        if (subject.isEmpty()) {
            add("$SUBJECT_PROPERTY ($SUBJECT_ENV) is empty: push services such as Apple's reject a VAPID token without a contact")
        } else if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
            add("$SUBJECT_PROPERTY ($SUBJECT_ENV) must be a mailto: or https: contact")
        }
        val invalid = WebPushEndpointPolicy.invalidEntries(properties.allowedHosts).size
        if (invalid > 0) add("$ALLOWED_PROPERTY has $invalid entr${if (invalid == 1) "y" else "ies"} that are neither a host, a *.suffix wildcard (two labels or more) nor http://host:port: they never match")
    }

    private fun keyProblems(properties: WebPushProperties): List<String> {
        val publicRaw = properties.publicKey.trim()
        val privateRaw = properties.privateKey.trim()
        val out = mutableListOf<String>()
        if (publicRaw.isEmpty()) out += "$PUBLIC_PROPERTY ($PUBLIC_ENV) is empty but skeleton.notification.webpush.enabled=true: browsers could not subscribe"
        if (privateRaw.isEmpty()) out += "$PRIVATE_PROPERTY ($PRIVATE_ENV) is empty but skeleton.notification.webpush.enabled=true: pushes cannot be signed"
        if (out.isNotEmpty()) return out
        val publicKey = runCatching { WebPushKeys.publicKey(WebPushKeys.decode(publicRaw)) }.getOrNull()
        val privateKey = runCatching { WebPushKeys.privateKey(WebPushKeys.decode(privateRaw)) }.getOrNull()
        if (publicKey == null) out += "$PUBLIC_PROPERTY is not a base64url uncompressed P-256 public key (65 bytes): regenerate the pair with 'npx web-push generate-vapid-keys'"
        if (privateKey == null) out += "$PRIVATE_PROPERTY is not a base64url P-256 private key (32 bytes): regenerate the pair with 'npx web-push generate-vapid-keys'"
        if (publicKey == null || privateKey == null) return out
        // 서명해 보고 공개 키로 검증한다 - 둘이 한 쌍이어야 브라우저가 우리 서명을 받아들인다
        val paired = runCatching {
            val probe = "vapid-pair-check".toByteArray()
            val signature = Signature.getInstance(ES256).run { initSign(privateKey); update(probe); sign() }
            Signature.getInstance(ES256).run { initVerify(publicKey); update(probe); verify(signature) }
        }.getOrDefault(false)
        if (!paired) out += "$PUBLIC_PROPERTY and $PRIVATE_PROPERTY are not a pair: use the two keys generated together (a mismatch makes every push fail and every subscription unusable)"
        return out
    }

    internal const val ES256 = "SHA256withECDSAinP1363Format"
}
