package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import java.time.Instant
import org.springframework.http.HttpStatus

enum class InquiryKind { QUESTION, FEATURE, BUG, OTHER }

enum class InquiryStatus { NEW, DONE }

/**
 * 문의 한 건. 본문 · [replyEmail] 은 개인정보다 — 로그 · 경보에 싣지 않는다.
 * [accountId] 는 로그인해서 보냈을 때만(계정이 지워지면 [InquiryRepository.detachAccount] 가 끊는다), [context] 는 앱이 붙이는 선택 문자열(예: 어느 페이지에서 왔는지).
 */
data class Inquiry(
    val id: Long,
    val kind: InquiryKind,
    val body: String,
    val replyEmail: String?,
    val accountId: String?,
    val context: String?,
    val lang: String,
    val status: InquiryStatus,
    val createdAt: Instant,
    val handledAt: Instant?,
)

/** 폼 본문. [captchaToken] 은 로그인하지 않은 호출만 본다 */
data class InquiryRequest(
    val kind: String? = null,
    val body: String? = null,
    val replyEmail: String? = null,
    val context: String? = null,
    val lang: String? = null,
    val captchaToken: String? = null,
) {
    override fun toString() = "InquiryRequest(kind=$kind, body=<redacted>, replyEmail=<redacted>, context=$context, lang=$lang, captchaToken=<redacted>)"
}

/** 저장할 값 — [InquiryService] 가 검사해 정리한 뒤 */
data class NewInquiry(
    val kind: InquiryKind,
    val body: String,
    val replyEmail: String?,
    val accountId: String?,
    val context: String?,
    val lang: String,
    val at: Instant,
)

/** 저장 포트 — 기본은 인메모리, `inquiry-jdbc` 가 DB 로 갈아 끼운다 */
interface InquiryRepository {
    fun create(inquiry: NewInquiry): Inquiry

    fun find(id: Long): Inquiry?

    /** 최신순. [status] 가 null 이면 전부 */
    fun list(status: InquiryStatus?, limit: Int): List<Inquiry>

    /** DONE 이면 처리 시각을 적고 NEW 로 되돌리면 비운다. 없으면 null */
    fun setStatus(id: Long, status: InquiryStatus, at: Instant): Inquiry?

    /** 계정 연결만 끊는다(본문 · 이메일은 남는다). 멱등 */
    fun detachAccount(accountId: String): Int
}

/** 로그인하지 않은 호출의 사람 확인 — 기본은 통과(앱이 Turnstile 같은 것으로 갈아 끼운다) */
fun interface InquiryHumanCheck {
    fun passes(token: String?, ip: String?): Boolean
}

/** 문의가 저장된 뒤 불린다(주인 경보 같은 것 — 앱이 빈으로 등록). 던져도 접수는 이미 끝났으니 서비스가 삼킨다 */
fun interface InquiryListener {
    fun received(inquiry: Inquiry)
}

data class InquiryViolation(val field: String, val code: String, val message: String)

enum class InquiryErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    INVALID("INQUIRY.INVALID", HttpStatus.BAD_REQUEST, "문의 값이 올바르지 않다"),
    TOO_MANY("INQUIRY.TOO_MANY", HttpStatus.TOO_MANY_REQUESTS, "문의가 너무 잦다"),
    CAPTCHA_FAILED("INQUIRY.CAPTCHA_FAILED", HttpStatus.BAD_REQUEST, "사람 확인에 실패했다"),
    NOT_FOUND("INQUIRY.NOT_FOUND", HttpStatus.NOT_FOUND, "문의가 없다"),
}

class InquiryInvalidException(violations: List<InquiryViolation>) :
    ApplicationException("inquiry invalid", InquiryErrorCode.INVALID, data = mapOf("violations" to violations))

class InquiryTooManyException(retryAfterSeconds: Long) :
    ApplicationException("too many inquiries", InquiryErrorCode.TOO_MANY, data = mapOf("retryAfterSeconds" to retryAfterSeconds))

class InquiryCaptchaFailedException : ApplicationException("inquiry captcha failed", InquiryErrorCode.CAPTCHA_FAILED)

class InquiryNotFoundException : ApplicationException("inquiry not found", InquiryErrorCode.NOT_FOUND)
