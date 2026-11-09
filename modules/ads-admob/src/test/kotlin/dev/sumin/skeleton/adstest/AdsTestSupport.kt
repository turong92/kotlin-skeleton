package dev.sumin.skeleton.adstest

import dev.sumin.skeleton.ads.admob.AdmobKeySource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointBalance
import dev.sumin.skeleton.point.PointCursor
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointWallet
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class AdsTestApplication

fun newEcKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** 구글이 하는 대로 서명한 콜백 쿼리: 서명 대상은 `signature` 앞까지의 쿼리 문자열 그대로, 서명은 DER · URL-safe base64, 끝에 `key_id` */
fun signedQuery(params: List<Pair<String, String>>, keyId: String, key: KeyPair): String {
    val message = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
    val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(message.toByteArray(Charsets.UTF_8)) }.sign()
    return "$message&signature=${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}&key_id=$keyId"
}

fun callback(unit: String = "ca-app-pub-1111/2222", user: String? = "acc_1", tx: String = "tx-1", amount: String = "10"): List<Pair<String, String>> = buildList {
    add("ad_network" to "5450213213286189855")
    add("ad_unit" to unit)
    add("reward_amount" to amount)
    add("reward_item" to "coin")
    add("timestamp" to "1700000000000")
    add("transaction_id" to tx)
    if (user != null) add("user_id" to user)
}

class MutableTime(var now: Instant = Instant.parse("2026-11-09T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = now
}

/** 구글 키 파일 대신 시험이 정한 키를 돌려주는 출처 — 읽은 횟수를 세고, 못 받는 경우도 흉내 낸다 */
class FakeKeySource : AdmobKeySource {
    var keys: Map<String, PublicKey> = emptyMap()
    var failing = false
    val loads = AtomicInteger()

    override fun load(): Map<String, PublicKey> {
        loads.incrementAndGet()
        if (failing) error("cannot reach the key file")
        return keys
    }
}

/** 저장소 없이 컨트롤러의 응답 · 멱등 호출만 보려는 지갑 */
class RecordingWallet : PointWallet {
    val entries = CopyOnWriteArrayList<PointEntry>()
    var failing = false

    override fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry? {
        if (failing) throw org.springframework.dao.QueryTimeoutException("db down")
        if (entryOf(sourceType, sourceRef) != null) return null
        return PointEntry("pt_${entries.size + 1}", accountId, amount, PointEntryKind.EARN, amount, expiresAt, sourceType, sourceRef, null, Instant.parse("2026-11-09T00:00:00Z")).also { entries += it }
    }

    override fun entryOf(sourceType: String, sourceRef: String) = entries.firstOrNull { it.sourceType == sourceType && it.sourceRef == sourceRef }

    override fun balance(accountId: String): PointBalance = error("not used")
    override fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry = error("not used")
    override fun cancelSpend(accountId: String, spendSourceType: String, spendSourceRef: String, amount: Long?, sourceType: String, sourceRef: String): PointEntry = error("not used")
    override fun adjust(accountId: String, delta: Long, reason: String, expiresAt: Instant?, operatorId: String): PointEntry = error("not used")
    override fun expireDue(now: Instant): Int = error("not used")
    override fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry> = error("not used")
}

@TestConfiguration(proxyBeanMethods = false)
class AdsFakes {
    @Bean fun fakeKeySource() = FakeKeySource()

    @Bean fun recordingWallet() = RecordingWallet()

    @Bean fun mutableTime() = MutableTime()
}
