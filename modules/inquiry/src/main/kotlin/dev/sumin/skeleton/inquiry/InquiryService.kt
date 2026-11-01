package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * 문의 받기 — 값 검사 → 한도 → (로그인하지 않았으면) 사람 확인 → 저장 → 리스너. 한도를 사람 확인보다 앞에 둬 1회용 토큰을 헛쓰지 않는다.
 * 한도는 로그인하지 않으면 클라이언트 키(IP), 로그인하면 계정별. 저장소는 [RateLimitStore] 빈이 있으면 그것(`redis-rate-limit` 이면 여러 대 공유), 없으면 메모리(서버 한 대 기준 · 다시 켜면 빈다).
 */
class InquiryService(
    private val repository: InquiryRepository,
    private val humanCheck: InquiryHumanCheck,
    private val listeners: List<InquiryListener>,
    private val properties: InquiryProperties,
    private val time: TimeProvider,
    private val store: RateLimitStore = InMemoryFixedWindowRateLimitStore(time.asClock()),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [accountId] 가 있으면 로그인한 호출. [clientKey] · [ip] 는 로그인하지 않은 호출의 한도 키 · 사람 확인용 */
    fun submit(request: InquiryRequest, accountId: String?, clientKey: String, ip: String?): Inquiry {
        val valid = validate(request)
        consume(accountId?.let { "account:$it" } ?: "client:$clientKey", if (accountId != null) properties.perAccount else properties.perClient)
        if (accountId == null && !humanCheck.passes(request.captchaToken, ip)) throw InquiryCaptchaFailedException()
        val saved = repository.create(valid.copy(accountId = accountId, at = time.now()))
        listeners.forEach {
            try {
                it.received(saved)
            } catch (e: Exception) {
                log.warn("inquiry listener failed: {}", e.javaClass.simpleName) // 접수는 끝났다 — 본문 · 이메일은 싣지 않는다
            }
        }
        return saved
    }

    private fun consume(key: String, limit: Int) {
        val now = time.now()
        val decision = store.consume("inquiry:$key", limit, properties.window.toMillis(), now)
        if (!decision.allowed) throw InquiryTooManyException(Duration.between(now, decision.resetAt).seconds.coerceAtLeast(1))
    }

    internal fun validate(request: InquiryRequest): NewInquiry {
        val violations = mutableListOf<InquiryViolation>()
        val kind = InquiryKind.entries.firstOrNull { it.name == request.kind }
        if (kind == null) violations += InquiryViolation("kind", "INVALID_FORMAT", "종류를 골라 주세요")
        val body = request.body.orEmpty().trim()
        if (body.length < BODY_MIN || body.length > BODY_MAX) {
            violations += InquiryViolation("body", "INVALID_LENGTH", "내용은 $BODY_MIN~${BODY_MAX}자로 적어 주세요")
        }
        val email = request.replyEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (email != null && (email.length > EMAIL_MAX || !EMAIL.matches(email))) {
            violations += InquiryViolation("replyEmail", "INVALID_FORMAT", "이메일 주소 모양이 아니에요")
        }
        val context = request.context?.trim()?.takeIf { it.isNotEmpty() }
        if (context != null && (context.length > CONTEXT_MAX || context.any { it.isISOControl() })) {
            violations += InquiryViolation("context", "INVALID_FORMAT", "참조 값이 올바르지 않아요")
        }
        if (violations.isNotEmpty()) throw InquiryInvalidException(violations)
        val lang = request.lang?.takeIf { it in LANGS } ?: "ko"
        return NewInquiry(kind!!, body, email, null, context, lang, time.now())
    }

    companion object {
        const val BODY_MIN = 10
        const val BODY_MAX = 2000
        const val EMAIL_MAX = 254
        const val CONTEXT_MAX = 100
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val LANGS = setOf("ko", "en", "ja")
    }
}

/** 운영 쪽이 쓰는 목록 · 처리 표시 — 인증 · 접속기록 · 감사는 앱이 둘러싼다(앱의 운영자 체계가 다르다) */
class InquiryAdminService(private val repository: InquiryRepository, private val time: TimeProvider) {
    fun list(status: String?, limit: Int): List<Inquiry> =
        repository.list(status?.takeIf { it.isNotBlank() }?.let(::parse), limit.coerceIn(1, LIST_MAX))

    fun setStatus(id: Long, status: String?): Inquiry =
        repository.setStatus(id, parse(status), time.now()) ?: throw InquiryNotFoundException()

    private fun parse(status: String?): InquiryStatus =
        InquiryStatus.entries.firstOrNull { it.name == status }
            ?: throw InquiryInvalidException(listOf(InquiryViolation("status", "INVALID_FORMAT", "NEW · DONE 중에서 골라요")))

    private companion object {
        const val LIST_MAX = 200
    }
}
