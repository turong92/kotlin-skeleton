package dev.sumin.skeleton.payment.appstore

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Date

/** 서명(JWS)을 믿을 수 없다 - 알고리즘 · 인증서 체인 · 서명 중 하나가 틀렸다. 메시지에 토큰 내용을 싣지 않는다. */
class AppStoreSignatureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 애플이 서명한 JWS(거래 정보 · 갱신 정보 · 알림 본문)를 믿어도 되는지 확인한다. 순서:
 * 1) 헤더 `alg` 가 `ES256` 이 아니면 거절(알고리즘 바꿔치기) 2) `x5c` 가 정확히 잎 · 중간 · 루트 세 장 3) JDK PKIX 로 **모듈에 넣어 둔 Apple Root CA - G3** 까지(시계는 [time], 폐기 확인 OCSP 는 하지 않는다 — 애플 라이브러리도 끌 수 있는 선택이다)
 * 4) 중간 인증서에 `1.2.840.113635.100.6.2.1`, 잎에 `1.2.840.113635.100.6.11.1` 확장 5) 잎 공개키로 서명 확인.
 * 번들 id · 앱 id 는 내용의 몫이라 쓰는 쪽이 본다.
 *
 * **신뢰 루트를 바꾸는 설정은 없다** - 시험만 이 생성자에 시험 루트를 넣는다. 앱은 빈을 바꿔 끼울 수 있지만([org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean]) 속성으로는 못 바꾼다.
 */
class AppStoreSignatureVerifier(
    roots: Set<X509Certificate>,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) {
    constructor(time: TimeProvider) : this(setOf(appleRootCaG3()), time)

    private val anchors: Set<TrustAnchor> = roots.map { TrustAnchor(it, null) }.toSet().also { require(it.isNotEmpty()) { "At least one trusted root is required" } }

    /** @return 서명이 맞은 JWS 의 본문(문자열). 아니면 [AppStoreSignatureException]. */
    fun verify(jws: String): String {
        val parsed = try {
            JWSObject.parse(jws)
        } catch (e: Exception) {
            throw AppStoreSignatureException("not a JWS", e)
        }
        if (parsed.header.algorithm != JWSAlgorithm.ES256) throw AppStoreSignatureException("the JWS algorithm is not ES256")
        val encoded = parsed.header.x509CertChain.orEmpty()
        if (encoded.size != CHAIN_LENGTH) throw AppStoreSignatureException("the certificate chain is not leaf, intermediate and root")
        val certificates = try {
            val factory = CertificateFactory.getInstance("X.509")
            encoded.map { factory.generateCertificate(it.decode().inputStream()) as X509Certificate }
        } catch (e: Exception) {
            throw AppStoreSignatureException("the certificate chain is unreadable", e)
        }
        val leaf = certificates[0]
        val intermediate = certificates[1]
        // 루트(셋째 장)는 쓰지 않는다 - 믿는 것은 우리가 넣어 둔 루트뿐이다
        if (LEAF_OID !in leaf.nonCriticalExtensionOIDs.orEmpty()) throw AppStoreSignatureException("the signing certificate lacks Apple's receipt signing extension")
        if (INTERMEDIATE_OID !in intermediate.nonCriticalExtensionOIDs.orEmpty()) throw AppStoreSignatureException("the intermediate certificate lacks Apple's intermediate extension")
        try {
            val parameters = PKIXParameters(anchors).apply {
                isRevocationEnabled = false
                date = Date.from(time.now())
            }
            CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf, intermediate)), parameters)
        } catch (e: Exception) {
            throw AppStoreSignatureException("the certificate chain does not lead to the trusted root", e)
        }
        val key = leaf.publicKey as? ECPublicKey ?: throw AppStoreSignatureException("the signing key is not an EC key")
        val valid = try {
            parsed.verify(ECDSAVerifier(key))
        } catch (e: Exception) {
            throw AppStoreSignatureException("the signature cannot be checked", e)
        }
        if (!valid) throw AppStoreSignatureException("the signature does not match")
        return parsed.payload.toString()
    }

    companion object {
        private const val CHAIN_LENGTH = 3
        private const val LEAF_OID = "1.2.840.113635.100.6.11.1"
        private const val INTERMEDIATE_OID = "1.2.840.113635.100.6.2.1"

        /** 모듈에 넣어 둔 Apple Root CA - G3(`apple.com/certificateauthority` 의 공개 인증서). */
        fun appleRootCaG3(): X509Certificate =
            requireNotNull(AppStoreSignatureVerifier::class.java.getResourceAsStream("/AppleRootCA-G3.cer")) { "AppleRootCA-G3.cer is missing from the module" }.use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
    }
}
