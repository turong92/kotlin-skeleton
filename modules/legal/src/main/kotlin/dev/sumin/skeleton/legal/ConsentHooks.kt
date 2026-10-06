package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.consent.SignUpConsentGate
import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

/**
 * `account` 의 가입에 동의를 묶는 고리 ([SignUpConsentGate]). [check] 는 요청 본문과 문서 집합만 본다 — 주소 · 계정 · 저장소를 보지 않으므로 응답이 주소를 드러내지 않는다.
 * [record] 는 계정이 만들어지는 트랜잭션 안에서 불린다.
 */
class LegalSignUpGate(
    private val service: ConsentService,
    private val catalog: LegalCatalog,
    private val rules: LegalRules,
    private val time: TimeProvider,
) : SignUpConsentGate {
    override fun check(claims: List<ConsentClaim>) {
        val now = time.now()
        val claimed = claims.map { it.type }.toSet()
        val notAgreed = rules.requiredAtSignUp.filter { it !in claimed }.mapNotNull { type ->
            catalog.current(type, now)?.let { MissingConsent(type, it.version, MissingReason.NOT_AGREED) }
        }
        val known = claims.filter { it.type in catalog.types }
        val stale = known.filter { catalog.agreeable(it.type, it.version, now) == null }
            .map { MissingConsent(it.type, catalog.current(it.type, now)?.version, MissingReason.STALE) }
        val unknown = claims.filter { it.type !in catalog.types }.map { MissingConsent(it.type, null, MissingReason.UNKNOWN) }
        val missing = notAgreed + stale + unknown
        if (missing.isNotEmpty()) throw consentRequired(missing)
    }

    override fun record(accountId: String, claims: List<ConsentClaim>, context: ConsentContext) =
        service.recordSignUp(Subject.account(accountId), claims, context)
}

/** 계정을 지울 때 동의 기록을 어떻게 하나 — [ANONYMIZE]: 증거는 남기고 사람을 지운다(기본) · [DELETE]: 익명화한 뒤 줄까지 지운다 */
enum class ErasureMode { ANONYMIZE, DELETE }

class ConsentErasureListener(private val store: ConsentStore, private val mode: ErasureMode) : AccountErasureListener {
    override val name: String = "legal"

    override fun erase(request: ErasureRequest) {
        store.anonymize(Subject.account(request.accountId), request.tombstone)
        if (mode == ErasureMode.DELETE) store.deleteAnonymized(request.tombstone)
    }
}

class ConsentDataExporter(private val store: ConsentStore) : AccountDataExporter {
    override val section: String = "legal"

    override fun export(accountId: String): Map<String, Any?> = mapOf(
        "consents" to store.export(Subject.account(accountId)).map {
            mapOf(
                "type" to it.type, "version" to it.version, "sha256" to it.sha256, "locale" to it.locale, "action" to it.action.name,
                "source" to it.source, "referenceId" to it.referenceId, "ip" to it.ip, "userAgent" to it.userAgent, "at" to it.at.toString(),
            )
        },
    )
}

/** 보관 기간이 지난 줄의 IP · UA 를 비운다 — 줄(증거)은 남는다. [interval] 마다 한 번만(기록할 때 불러도 부담이 없게). [retention] 이 0 이면 무기한 */
class ConsentRetention(
    private val store: ConsentStore,
    private val time: TimeProvider,
    private val retention: Duration,
    private val interval: Duration,
) {
    @Volatile private var last: Instant? = null

    fun runIfDue(): Int? {
        if (retention.isZero || retention.isNegative) return null
        val now = time.now()
        val previous = last
        if (previous != null && now.isBefore(previous.plus(interval))) return null
        last = now
        return store.scrubPersonalData(now.minus(retention))
    }
}
