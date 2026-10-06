package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import java.time.Instant
import org.springframework.http.HttpStatus

/** 누가 동의했나 — 종류 + id. 기본은 계정(`account`, 계정 id). 앱이 다른 주체(조직 · 기기)를 쓰면 종류만 바꾼다 */
data class Subject(val type: String, val id: String) {
    companion object {
        const val ACCOUNT = "account"
        fun account(id: String) = Subject(ACCOUNT, id)
    }
}

enum class ConsentAction { AGREED, WITHDRAWN }

/** 저장된 동의 사건 하나 (고치지 않는다). [seq]: (주체 · 종류 · 참조) 안의 순번 — 동시에 같은 순번을 넣으려는 쪽이 유니크 키로 진다 */
data class ConsentEvent(
    val id: Long,
    val subject: Subject,
    val type: String,
    val version: String,
    val sha256: String,
    val locale: String,
    val action: ConsentAction,
    val source: String,
    val referenceId: String?,
    val seq: Int,
    val ip: String?,
    val userAgent: String?,
    val at: Instant,
)

data class NewConsentEvent(
    val subject: Subject,
    val type: String,
    val version: String,
    val sha256: String,
    val locale: String,
    val action: ConsentAction,
    val source: String,
    val referenceId: String?,
    val seq: Int,
    val ip: String?,
    val userAgent: String?,
    val at: Instant,
)

data class ConsentPage(val items: List<ConsentEvent>, val total: Long)

data class ConsentSearch(val subjectType: String? = null, val subjectId: String? = null, val type: String? = null, val action: ConsentAction? = null)

/** 저장 포트 — `legal-jdbc` 가 구현한다. 메모리 구현은 없다(동의 기록은 증거라서 재시작에 사라지면 안 된다) */
interface ConsentStore {
    /** (주체, 참조) 안에서 종류마다 **가장 큰 순번**의 사건 */
    fun latest(subject: Subject, types: Collection<String>, referenceId: String?): Map<String, ConsentEvent>

    /** 같은 (주체, 종류, 참조, 순번)이 이미 있으면 false — 동시에 온 다른 요청이 먼저 넣은 것 */
    fun append(event: NewConsentEvent): Boolean

    fun history(subject: Subject, page: Int, size: Int): ConsentPage

    fun search(search: ConsentSearch, page: Int, size: Int): ConsentPage

    fun countSince(subject: Subject, since: Instant): Int

    /** 주체의 개인정보(주체 id · IP · UA)를 지운다 — [tombstone] 으로 바꾼다. 바뀐 줄 수 */
    fun anonymize(subject: Subject, tombstone: String): Int

    /** [tombstone] 으로 이미 익명화된 줄을 지운다 (익명화를 거치지 않은 줄은 DB 가 지우지 못하게 막는다) */
    fun deleteAnonymized(tombstone: String): Int

    /** [before] 보다 오래된 줄의 IP · UA 를 비운다 (보관 기간). 바뀐 줄 수 */
    fun scrubPersonalData(before: Instant): Int

    fun export(subject: Subject): List<ConsentEvent>
}

enum class LegalErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    CONSENT_REQUIRED("LEGAL.CONSENT_REQUIRED", HttpStatus.BAD_REQUEST, "Consent required"),
    RECONSENT_REQUIRED("LEGAL.RECONSENT_REQUIRED", HttpStatus.FORBIDDEN, "Consent required"),
    VERSION_STALE("LEGAL.VERSION_STALE", HttpStatus.CONFLICT, "Document version is not the current one"),
    WITHDRAWAL_NOT_ALLOWED("LEGAL.WITHDRAWAL_NOT_ALLOWED", HttpStatus.CONFLICT, "A required document cannot be withdrawn"),
    UNKNOWN_DOCUMENT("LEGAL.UNKNOWN_DOCUMENT", HttpStatus.BAD_REQUEST, "Unknown document type"),
    DOCUMENT_NOT_FOUND("LEGAL.DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found"),
    RATE_LIMITED("LEGAL.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many consent events"),
}

data class StaleVersion(val type: String, val requiredVersion: String?)

enum class MissingReason { NOT_AGREED, STALE, UNKNOWN }

/** 동의해야 하는 것 하나 — [version] 은 지금 동의할 판(알 수 없는 종류면 null) */
data class MissingConsent(val type: String, val version: String?, val reason: MissingReason)

class LegalException(code: LegalErrorCode, message: String, data: Any? = null) : ApplicationException(message, code, data = data)

fun consentRequired(missing: List<MissingConsent>, code: LegalErrorCode = LegalErrorCode.CONSENT_REQUIRED) =
    LegalException(code, "consent required for ${missing.joinToString { it.type }}", mapOf("missing" to missing))

enum class ConsentState { CURRENT, GRACE, OUTDATED, MISSING, WITHDRAWN }

data class TypeStatus(
    val type: String,
    val required: Boolean,
    val requiredAtSignUp: Boolean,
    val state: ConsentState,
    val current: DocumentVersion,
    val agreed: ConsentEvent?,
    val graceUntil: Instant?,
)

data class ConsentStatus(val items: List<TypeStatus>, val missing: List<MissingConsent>) {
    val blocked: Boolean get() = missing.isNotEmpty()
}

/** 동의 기록의 저장 옵션 — 설정에서 온다 */
data class ConsentOptions(
    val storeIp: Boolean = true,
    val storeUserAgent: Boolean = true,
    val maxEventsPerDay: Int = 200,
)
