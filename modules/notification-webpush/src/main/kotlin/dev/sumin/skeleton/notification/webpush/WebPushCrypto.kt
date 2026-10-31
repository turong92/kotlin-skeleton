package dev.sumin.skeleton.notification.webpush

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 키를 웹 푸시가 쓰는 모양(비압축 65바이트 공개 키 · 32바이트 개인 키, base64url)과 JDK 키 사이로 옮긴다. 개인 키는 어디에도 문자열로 남기지 않는다 */
internal object WebPushKeys {
    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trim())

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** 65바이트 `0x04 || X || Y`. 곡선 위의 점이 아니면 던진다 */
    fun publicKey(bytes: ByteArray): ECPublicKey {
        require(bytes.size == PUBLIC_KEY_BYTES && bytes[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        require(onCurve(point)) { "point is not on the P-256 curve" }   // JDK 는 키를 만들 때 곡선 위인지 보지 않는다 - 잘못된 곡선 점 공격을 여기서 막는다
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
    }

    private fun onCurve(point: ECPoint): Boolean {
        val p = (curve.curve.field as java.security.spec.ECFieldFp).p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        return y.multiply(y).mod(p) == x.pow(3).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
    }

    fun privateKey(bytes: ByteArray): ECPrivateKey {
        require(bytes.size == PRIVATE_KEY_BYTES) { "not a 32-byte P-256 scalar" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), curve)) as ECPrivateKey
    }

    fun uncompressed(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    fun generate(): WebPushCrypto.ServerKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return WebPushCrypto.ServerKey(pair.private as ECPrivateKey, uncompressed(pair.public as ECPublicKey))
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    const val PUBLIC_KEY_BYTES = 65
    const val PRIVATE_KEY_BYTES = 32
}

/** RFC 8291 `aes128gcm` 본문 암호화 — 푸시 하나 = 레코드 하나. 외부 라이브러리 없이 JDK 암호화만 쓴다 */
internal object WebPushCrypto {
    /** 응용 서버의 한 번 쓰는 키 쌍(개인 키 + 비압축 공개 키 65바이트) */
    class ServerKey(val private: ECPrivateKey, val public: ByteArray)

    private const val RECORD_SIZE = 4096
    /** 머리(salt 16 + rs 4 + idlen 1 + 공개 키 65 = 86) + 구분 바이트 1 + GCM 태그 16 을 뺀, 한 레코드에 들어가는 평문의 최대 길이 */
    const val MAX_PLAINTEXT_BYTES = RECORD_SIZE - 86 - 1 - 16

    private val random = SecureRandom()

    /** 머리 + 암호문. [serverKey] · [salt] 는 시험 벡터를 위해 받을 수 있고, 평소에는 부를 때마다 새로 만든다 */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        serverKey: ServerKey = WebPushKeys.generate(),
        salt: ByteArray = ByteArray(16).also(random::nextBytes),
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "payload too large for one record" }
        val ecdh = KeyAgreement.getInstance("ECDH").run {
            init(serverKey.private)
            doPhase(WebPushKeys.publicKey(uaPublic), true)
            generateSecret()
        }
        val prkKey = hmac(authSecret, ecdh)
        val keyInfo = "WebPush: info".toByteArray() + 0 + uaPublic + serverKey.public
        val ikm = hmac(prkKey, keyInfo + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)

        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext + 0x02)   // 마지막 레코드 구분 바이트
        }
        val rs = byteArrayOf((RECORD_SIZE ushr 24).toByte(), (RECORD_SIZE ushr 16).toByte(), (RECORD_SIZE ushr 8).toByte(), RECORD_SIZE.toByte())
        return salt + rs + byteArrayOf(serverKey.public.size.toByte()) + serverKey.public + sealed
    }

    /** HKDF-Extract(salt, ikm) 도, 길이 32 이하의 HKDF-Expand(prk, info || 0x01) 도 HMAC 한 번이다 */
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
