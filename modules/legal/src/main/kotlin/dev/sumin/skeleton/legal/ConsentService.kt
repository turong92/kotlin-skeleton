package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration

/**
 * 동의 검사 · 기록. 판단은 모두 [LegalCatalog] 가 `now` 로 답하고(현재 판 · 유예), 이 클래스는 저장된 사건과 맞춰 본다.
 *
 * 한 (주체, 종류, 참조)의 사건은 순번이 이어지는 줄이다. 기록은 "마지막 사건의 다음 순번"을 넣고, 같은 순번을 동시에 넣은 쪽은 저장소의 유니크 키에 져서
 * 다시 읽는다 — 이긴 쪽이 이미 같은 동의면 할 일이 없다. 그래서 같은 요청이 몇 번 겹쳐도 한 줄이다.
 */
class ConsentService(
    private val catalog: LegalCatalog,
    private val rules: LegalRules,
    private val store: ConsentStore,
    private val time: TimeProvider,
    private val options: ConsentOptions = ConsentOptions(),
    private val retention: ConsentRetention? = null,
) {
    /** 모든 종류의 상태 — 시행된 판이 있는 종류만. `missing` 은 **필수** 종류만 */
    fun status(subject: Subject, referenceId: String? = null): ConsentStatus {
        val items = items(subject, catalog.types, referenceId)
        return ConsentStatus(items, missingOf(items.filter { it.required }))
    }

    /** [types] 중 동의가 모자라거나 낡은 것 — 기본은 필수 종류. 모르는 종류는 `UNKNOWN` */
    fun requireCurrent(subject: Subject, types: Collection<String> = rules.required, referenceId: String? = null): List<MissingConsent> {
        val known = types.filter { it in catalog.types }
        val unknown = types.filter { it !in catalog.types }.map { MissingConsent(it, null, MissingReason.UNKNOWN) }
        return missingOf(items(subject, known, referenceId)) + unknown
    }

    /**
     * [claims] 를 모두 검사한 뒤(하나라도 모르는 종류 · 받을 수 없는 판이면 아무것도 기록하지 않는다) 기록한다. 이미 그 판에 동의한 상태면 아무것도 하지 않는다.
     * 판은 현재 판, 또는 유예 안의 바로 앞 판.
     */
    fun record(subject: Subject, claims: List<ConsentClaim>, context: ConsentContext, source: String, referenceId: String? = null): ConsentStatus {
        val now = time.now()
        val unknown = claims.map { it.type }.filter { it !in catalog.types }.distinct()
        if (unknown.isNotEmpty()) throw LegalException(LegalErrorCode.UNKNOWN_DOCUMENT, "unknown document type: ${unknown.joinToString()}", mapOf("types" to unknown))
        val accepted = claims.map { it to catalog.agreeable(it.type, it.version, now) }
        val stale = accepted.filter { it.second == null }.map { StaleVersion(it.first.type, catalog.current(it.first.type, now)?.version) }
        if (stale.isNotEmpty()) throw LegalException(LegalErrorCode.VERSION_STALE, "not the agreeable version: ${stale.joinToString { it.type }}", mapOf("stale" to stale))
        accepted.forEach { (claim, document) -> agree(subject, claim, document!!, context, source, referenceId) }
        sweep()
        return status(subject, referenceId)
    }

    /**
     * 가입 시도가 확인되어 계정이 만들어질 때 — 사용자가 **가입 화면에서 본 판** 그대로 적는다(그 사이 새 판이 시행됐어도). 판이 시행된 적이 없으면 던진다(계정 만들기 트랜잭션이 되돌려진다).
     * 새 주체라 일일 한도는 보지 않는다.
     */
    fun recordSignUp(subject: Subject, claims: List<ConsentClaim>, context: ConsentContext) {
        val now = time.now()
        claims.forEach { claim ->
            val document = catalog.find(claim.type, claim.version, now) ?: error("sign-up consent names ${claim.type} ${claim.version}, which is not an effective version")
            agree(subject, claim, document, context, SIGN_UP_SOURCE, null, rateGuard = false)
        }
    }

    /** 선택 종류의 동의를 거둔다 — 거둔다는 사건이 따로 남는다. 동의 중이 아니면 아무것도 하지 않는다 */
    fun withdraw(subject: Subject, type: String, context: ConsentContext): ConsentStatus {
        if (type !in catalog.types) throw LegalException(LegalErrorCode.UNKNOWN_DOCUMENT, "unknown document type: $type", mapOf("types" to listOf(type)))
        if (type in rules.required) throw LegalException(LegalErrorCode.WITHDRAWAL_NOT_ALLOWED, "$type is required and cannot be withdrawn")
        repeat(MAX_ROUNDS) {
            val latest = store.latest(subject, listOf(type), null)[type]
            if (latest == null || latest.action != ConsentAction.AGREED) return status(subject)
            guardRate(subject)
            val event = NewConsentEvent(
                subject, type, latest.version, latest.sha256, latest.locale, ConsentAction.WITHDRAWN, "withdraw", null, latest.seq + 1,
                ip(context), userAgent(context), time.now(),
            )
            if (store.append(event)) return status(subject)
        }
        error("could not record the withdrawal of $type after $MAX_ROUNDS rounds")
    }

    private fun agree(subject: Subject, claim: ConsentClaim, document: DocumentVersion, context: ConsentContext, source: String, referenceId: String?, rateGuard: Boolean = true) {
        val shown = catalog.render(document, claim.locale ?: rules.defaultLocale)
        repeat(MAX_ROUNDS) {
            val latest = store.latest(subject, listOf(claim.type), referenceId)[claim.type]
            if (latest != null && latest.action == ConsentAction.AGREED && latest.version == document.version) return
            if (rateGuard) guardRate(subject)
            val event = NewConsentEvent(
                subject, claim.type, document.version, shown.sha256, shown.locale, ConsentAction.AGREED, source, referenceId, (latest?.seq ?: 0) + 1,
                ip(context), userAgent(context), time.now(),
            )
            if (store.append(event)) return
        }
        error("could not record the consent to ${claim.type} after $MAX_ROUNDS rounds")
    }

    /** 보관 기간이 지난 IP · UA 정리 — 기록하는 김에 간격마다 한 번. 실패해도 기록은 이미 끝났다 */
    private fun sweep() {
        runCatching { retention?.runIfDue() }
    }

    private fun items(subject: Subject, types: Collection<String>, referenceId: String?): List<TypeStatus> {
        val now = time.now()
        val latest = store.latest(subject, types, referenceId)
        return types.mapNotNull { type ->
            val current = catalog.current(type, now) ?: return@mapNotNull null
            val event = latest[type]
            var graceUntil: java.time.Instant? = null
            val state = when {
                event == null -> ConsentState.MISSING
                event.action == ConsentAction.WITHDRAWN -> ConsentState.WITHDRAWN
                event.version == current.version -> ConsentState.CURRENT
                catalog.agreeable(type, event.version, now) != null -> ConsentState.GRACE.also { graceUntil = catalog.graceUntil(type, now) }
                else -> ConsentState.OUTDATED
            }
            TypeStatus(type, type in rules.required, type in rules.requiredAtSignUp, state, current, event, graceUntil)
        }
    }

    private fun missingOf(items: List<TypeStatus>): List<MissingConsent> = items.mapNotNull {
        when (it.state) {
            ConsentState.MISSING, ConsentState.WITHDRAWN -> MissingConsent(it.type, it.current.version, MissingReason.NOT_AGREED)
            ConsentState.OUTDATED -> MissingConsent(it.type, it.current.version, MissingReason.STALE)
            else -> null
        }
    }

    private fun guardRate(subject: Subject) {
        if (options.maxEventsPerDay <= 0) return
        if (store.countSince(subject, time.now().minus(Duration.ofDays(1))) >= options.maxEventsPerDay) {
            throw LegalException(LegalErrorCode.RATE_LIMITED, "too many consent events", mapOf("retryAfterSeconds" to 3600))
        }
    }

    private fun ip(context: ConsentContext) = context.ip.takeIf { options.storeIp }?.take(64)

    private fun userAgent(context: ConsentContext) = context.userAgent.takeIf { options.storeUserAgent }?.take(255)

    private companion object {
        const val MAX_ROUNDS = 5
        const val SIGN_UP_SOURCE = "sign-up"
    }
}
