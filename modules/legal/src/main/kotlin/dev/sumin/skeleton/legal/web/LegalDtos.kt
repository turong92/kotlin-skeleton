package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.legal.ConsentEvent
import dev.sumin.skeleton.legal.ConsentStatus
import dev.sumin.skeleton.legal.MissingConsent
import dev.sumin.skeleton.legal.TypeStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

// ---- 공개 읽기 ----

data class NextVersionResponse(val version: String, val effectiveFrom: Instant)

data class DocumentSummaryResponse(
    val type: String,
    val locale: String,
    val version: String,
    val effectiveFrom: Instant,
    val title: String?,
    val sha256: String,
    val required: Boolean,
    val requiredAtSignUp: Boolean,
    val template: Boolean,
    val next: NextVersionResponse?,
)

data class DocumentDetailResponse(
    val type: String,
    val version: String,
    val locale: String,
    val requestedLocale: String,
    val effectiveFrom: Instant,
    val current: Boolean,
    val template: Boolean,
    val title: String?,
    val sha256: String,
    val required: Boolean,
    val requiredAtSignUp: Boolean,
    val markdown: String,
)

// ---- 로그인한 사용자 ----

data class CurrentVersionResponse(val version: String, val effectiveFrom: Instant, val locales: List<String>)

data class AgreedResponse(val version: String, val agreedAt: Instant, val source: String)

data class ConsentItemResponse(
    val type: String,
    val required: Boolean,
    val requiredAtSignUp: Boolean,
    val state: String,
    val current: CurrentVersionResponse,
    val agreed: AgreedResponse?,
    val graceUntil: Instant?,
)

data class MissingResponse(val type: String, val version: String?, val reason: String)

data class ConsentStatusResponse(val blocked: Boolean, val items: List<ConsentItemResponse>, val missing: List<MissingResponse>)

data class HistoryEventResponse(val type: String, val version: String, val action: String, val locale: String, val source: String, val at: Instant)

data class ConsentClaimRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$") val type: String?,
    @field:NotBlank @field:Size(max = 32) val version: String?,
    @field:Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") val locale: String? = null,
)

data class ConsentRequest(
    @field:NotNull @field:Size(min = 1, max = 20) @field:Valid val consents: List<ConsentClaimRequest>?,
    /** `sign-up` 는 서버만 쓴다 */
    @field:Pattern(regexp = "^(?!sign-up$)[a-z][a-z0-9-]{1,31}$") val source: String? = null,
)

// ---- 운영자 ----

data class AdminConsentResponse(
    val id: Long,
    val subjectType: String,
    val subjectId: String,
    val type: String,
    val version: String,
    val sha256: String,
    val locale: String,
    val action: String,
    val source: String,
    val referenceId: String?,
    val ip: String?,
    val userAgent: String?,
    val at: Instant,
)

data class AdminDocumentResponse(
    val type: String,
    val version: String,
    val status: String,
    val effectiveFrom: Instant?,
    val locales: List<String>,
    val template: Boolean,
    val current: Boolean,
    val sha256: Map<String, String>,
    val pinned: Boolean,
    val missingFacts: List<String>,
)

fun ConsentStatus.toResponse() = ConsentStatusResponse(blocked, items.map { it.toResponse() }, missing.map { it.toResponse() })

fun MissingConsent.toResponse() = MissingResponse(type, version, reason.name)

fun TypeStatus.toResponse() = ConsentItemResponse(
    type, required, requiredAtSignUp, state.name,
    CurrentVersionResponse(current.version, current.effectiveFrom!!, current.locales),
    agreed?.let { AgreedResponse(it.version, it.at, it.source) },
    graceUntil,
)

fun ConsentEvent.toHistory() = HistoryEventResponse(type, version, action.name, locale, source, at)

fun ConsentEvent.toAdmin() = AdminConsentResponse(id, subject.type, subject.id, type, version, sha256, locale, action.name, source, referenceId, ip, userAgent, at)
