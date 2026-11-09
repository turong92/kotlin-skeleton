package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.point.StorePointWallet
import java.net.URLDecoder
import java.security.Signature
import java.util.Base64

/** 서명과 허용 광고 단위를 통과한 보상 콜백. */
data class AdmobReward(val userId: String, val amount: Long, val transactionId: String)

/**
 * AdMob SSV 콜백 확인. 서명 대상은 받은 쿼리 문자열을 고치지 않고 `&signature=` 앞까지(UTF-8)이고, 서명은 ECDSA-SHA256(DER, URL-safe base64),
 * 쿼리 끝은 `signature` · `key_id` 순서다. 서명이 맞아도 [AdmobProperties.adUnits] 에 없는 광고 단위면 거절한다.
 * 거절 사유는 코드만 [AdmobRejectedException] 에 담는다(값은 싣지 않는다).
 */
class AdmobSsvVerifier(private val keys: AdmobVerifierKeys, private val properties: AdmobProperties) {
    /** 콘솔의 전체 ID(`ca-app-pub-…/123`)로 적든 뒤의 숫자(`123`)만 적든 받는다 — 구글의 콜백 예시는 `/` 뒤 숫자만 온다. */
    private val allowedUnits = properties.adUnits.map(::unitOf).toSet()

    private fun unitOf(value: String) = value.substringAfterLast('/')

    fun verify(rawQuery: String?): AdmobReward {
        val query = rawQuery.orEmpty()
        val cut = query.lastIndexOf(SIGNATURE_MARK)
        if (cut < 0) throw AdmobRejectedException("NO_SIGNATURE")
        val message = query.substring(0, cut)
        val tail = query.substring(cut + 1).split('&')
        if (tail.size != 2 || !tail[0].startsWith("signature=") || !tail[1].startsWith("key_id=")) throw AdmobRejectedException("BAD_TAIL")
        val signature = try {
            Base64.getUrlDecoder().decode(tail[0].removePrefix("signature="))
        } catch (e: IllegalArgumentException) {
            throw AdmobRejectedException("BAD_SIGNATURE")
        }
        val key = keys.find(tail[1].removePrefix("key_id=")) ?: throw AdmobRejectedException("UNKNOWN_KEY")
        if (!signatureMatches(message, signature, key)) throw AdmobRejectedException("BAD_SIGNATURE")

        val params = parse(message)
        if (params["ad_unit"]?.let(::unitOf) !in allowedUnits) throw AdmobRejectedException("AD_UNIT_NOT_ALLOWED")
        val userId = params["user_id"]?.takeIf { it.isNotBlank() && it.length <= MAX_USER_ID } ?: throw AdmobRejectedException("NO_USER")
        val transactionId = params["transaction_id"]?.takeIf { it.isNotBlank() } ?: throw AdmobRejectedException("NO_TRANSACTION")
        val amount = params["reward_amount"]?.toLongOrNull()?.takeIf { it in 1..StorePointWallet.MAX_AMOUNT } ?: throw AdmobRejectedException("BAD_AMOUNT")
        return AdmobReward(userId, amount, transactionId)
    }

    private fun signatureMatches(message: String, signature: ByteArray, key: java.security.PublicKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message.toByteArray(Charsets.UTF_8))
            verify(signature)
        }
    } catch (e: java.security.GeneralSecurityException) {
        false   // 깨진 DER 도 위조와 같다
    }

    private fun parse(message: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        for (pair in message.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val name = URLDecoder.decode(pair.substring(0, eq), Charsets.UTF_8)
            if (params.put(name, URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8)) != null) throw AdmobRejectedException("DUPLICATE_PARAM")
        }
        return params
    }

    private companion object {
        const val SIGNATURE_MARK = "&signature="
        const val MAX_USER_ID = 128
    }
}
