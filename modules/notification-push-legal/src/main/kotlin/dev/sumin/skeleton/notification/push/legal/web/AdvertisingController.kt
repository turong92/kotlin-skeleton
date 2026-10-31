package dev.sumin.skeleton.notification.push.legal.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import dev.sumin.skeleton.notification.legal.AdvertisingConsentItem
import dev.sumin.skeleton.notification.legal.LegalAdvertisingConsents
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AdvertisingConsentResponse(
    val type: String,
    val state: String,
    val agreedAt: Instant?,
    val expiresAt: Instant?,
    val reconfirmDue: Boolean,
    val effective: Boolean,
)

data class NightWindowResponse(val start: String, val end: String, val zone: String)

data class AdvertisingResponse(
    val advertisingTypes: List<String>,
    val senderName: String?,
    val consent: AdvertisingConsentResponse,
    val night: AdvertisingConsentResponse,
    val nightWindow: NightWindowResponse,
)

/**
 * 광고 동의 상태 읽기 하나 - 동의 · 철회 · 재확인은 `legal` 의 기존 경로(`/api/v1/legal/consents`)로 한다(증거가 한 곳에 쌓이게).
 * 광고 종류가 설정됐고 서블릿 웹 앱 + Spring Security 가 있을 때만 등록한다. 호출자 = `Authentication.name`.
 */
@RestController
@RequestMapping("/api/v1/push/advertising")
@Tag(name = "Push")
class AdvertisingController(
    private val consents: LegalAdvertisingConsents,
    private val gate: AdvertisingGate,
    private val properties: AdvertisingProperties,
    private val time: TimeProvider,
) {
    @Operation(summary = "My advertising consent state", description = "Consent and night consent with state, agreement and expiry time, whether a reconfirm is due and whether advertising can actually be sent; plus the night window in my time zone. Agree, withdraw and reconfirm go through /api/v1/legal/consents.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdvertisingResponse::class)
    @GetMapping
    fun advertising(authentication: Authentication?): DataResponse<AdvertisingResponse> {
        val accountId = authentication?.takeIf { it.isAuthenticated }?.name
            ?: throw ApplicationException("Authentication required", HttpStatus.UNAUTHORIZED, "Authentication required")
        val snapshot = consents.snapshot(accountId, time.now())
        return Response.ok(
            AdvertisingResponse(
                advertisingTypes = properties.typeList,
                senderName = properties.senderName,
                consent = snapshot.consent.toResponse(),
                night = snapshot.night.toResponse(),
                nightWindow = NightWindowResponse(properties.nightStart, properties.nightEnd, gate.zoneOf(accountId).id),
            ),
        )
    }

    private fun AdvertisingConsentItem.toResponse() = AdvertisingConsentResponse(type, state.name, agreedAt, expiresAt, reconfirmDue, effective)
}
