package dev.sumin.skeleton.notification.push.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.notification.NotificationMuteRepository
import dev.sumin.skeleton.notification.NotificationPreferencesProperties
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushDeviceService
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushErrorCode
import dev.sumin.skeleton.notification.push.PushException
import dev.sumin.skeleton.notification.push.PushKinds
import dev.sumin.skeleton.notification.push.PushPlatforms
import dev.sumin.skeleton.notification.push.PushProperties
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushSender
import dev.sumin.skeleton.notification.push.PushSessionIdResolver
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PushWebPushConfig(val publicKey: String)

data class PushConfigResponse(
    val webPush: PushWebPushConfig?,
    val fcm: Boolean,
    val types: List<String>,
    val advertisingTypes: List<String>,
)

data class PushDeviceResponse(
    val installationId: String,
    val kind: String,
    val platform: String,
    val label: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

data class PushDevicesResponse(val items: List<PushDeviceResponse>)

/** 웹: `{ kind: "WEBPUSH", endpoint, keys: { p256dh, auth }, label? }` · 모바일: `{ kind: "FCM", token, platform: "android"|"ios", label? }` */
data class PushDeviceRequest(
    val kind: String? = null,
    val endpoint: String? = null,
    val keys: Keys? = null,
    val token: String? = null,
    val platform: String? = null,
    val label: String? = null,
) {
    /** 끝점 · 구독 열쇠 · 기기 토큰은 비밀이다 - 자동 toString 을 쓰지 않는다 */
    override fun toString(): String = "PushDeviceRequest(kind=$kind, platform=$platform)"

    data class Keys(val p256dh: String? = null, val auth: String? = null) {
        override fun toString(): String = "Keys(***)"
    }
}

data class PushPreferenceResponse(val type: String, val push: Boolean, val allChannelsMuted: Boolean)

data class PushPreferencesResponse(val items: List<PushPreferenceResponse>)

data class PushPreferenceRequest(@field:NotNull val enabled: Boolean?)

/**
 * 호출자(`Authentication.name` = auth 의 계정 id)의 푸시 기기 · 종류별 푸시 설정. 인증 없는 호출은 401.
 * [PushWebAutoConfiguration] 이 서블릿 웹 앱 + Spring Security 가 있을 때만 등록한다 (`skeleton.notification.push.http.enabled=false` 로 뺀다).
 */
@RestController
@RequestMapping("/api/v1/push")
@Tag(name = "Push")
class PushController(
    private val service: PushDeviceService,
    private val devices: PushDeviceStore,
    private val mutes: PushTypeMuteStore,
    private val allChannelMutes: NotificationMuteRepository,
    private val notificationPreferences: NotificationPreferencesProperties,
    private val properties: PushProperties,
    private val senders: () -> List<PushSender>,
    private val sessionIds: PushSessionIdResolver,
) {
    @Operation(summary = "Push client configuration", description = "Public key for subscribing, which kinds are on, and which notification types can be pushed.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PushConfigResponse::class)
    @GetMapping("/config")
    fun config(authentication: Authentication?): DataResponse<PushConfigResponse> {
        authentication.accountId()
        val all = senders()
        val webKey = all.firstOrNull { it.kind == PushKinds.WEBPUSH }?.publicKey
        return Response.ok(
            PushConfigResponse(
                webPush = webKey?.let(::PushWebPushConfig),
                fcm = all.any { it.kind == PushKinds.FCM },
                types = properties.pushTypes,
                advertisingTypes = properties.advertisingTypeList,
            ),
        )
    }

    @Operation(summary = "List my push devices", description = "Most recently seen first.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PushDevicesResponse::class)
    @GetMapping("/devices")
    fun list(authentication: Authentication?): DataResponse<PushDevicesResponse> =
        Response.ok(PushDevicesResponse(devices.listByAccount(authentication.accountId()).map { it.toResponse() }))

    @Operation(summary = "Register or refresh a push device", description = "Call whenever the app or page opens. One row per installation id.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PushDeviceResponse::class)
    @PutMapping("/devices/{installationId}")
    fun register(
        authentication: Authentication?,
        @PathVariable installationId: String,
        @RequestBody request: PushDeviceRequest,
    ): DataResponse<PushDeviceResponse> {
        val accountId = authentication.accountId()
        val device = service.register(accountId, installationId, sessionIds.sessionIdOf(authentication?.principal), request.toRegistration())
        return Response.ok(device.toResponse())
    }

    @Operation(summary = "Remove one of my push devices", description = "204 even when it does not exist or belongs to someone else (nothing is removed then).")
    @DeleteMapping("/devices/{installationId}")
    fun remove(authentication: Authentication?, @PathVariable installationId: String): ResponseEntity<Void> {
        devices.deleteByInstallation(authentication.accountId(), installationId)
        return Response.noContent()
    }

    @Operation(summary = "Send a test push to one of my devices", description = "Once a minute per account (429 PUSH.TEST_TOO_SOON).")
    @ApiResponseEnvelope(type = ApiEnvelopeType.BASIC)
    @PostMapping("/devices/{installationId}/test")
    fun test(authentication: Authentication?, @PathVariable installationId: String): ResponseEntity<BasicResponse> {
        service.sendTest(authentication.accountId(), installationId)
        return ResponseEntity.accepted().body(Response.ok())
    }

    @Operation(summary = "List my per-type push switches", description = "One item per pushable type in configured order (advertising types are not listed).")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PushPreferencesResponse::class)
    @GetMapping("/preferences")
    fun preferences(authentication: Authentication?): DataResponse<PushPreferencesResponse> {
        val accountId = authentication.accountId()
        val types = properties.pushTypes
        if (types.isEmpty()) return Response.ok(PushPreferencesResponse(emptyList()))
        val pushMuted = mutes.mutedTypes(accountId)
        val allMuted = allChannelMutes.mutedTypes(accountId)
        return Response.ok(PushPreferencesResponse(types.map { it.toPreference(pushMuted, allMuted) }))
    }

    @Operation(summary = "Turn push on or off for one type", description = "Only the push channel: the inbox keeps the notifications. Idempotent.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PushPreferenceResponse::class)
    @PutMapping("/preferences/{type}")
    fun setPreference(
        authentication: Authentication?,
        @PathVariable type: String,
        @Valid @RequestBody request: PushPreferenceRequest,
    ): DataResponse<PushPreferenceResponse> {
        val accountId = authentication.accountId()
        if (type !in properties.pushTypes) throw PushException(PushErrorCode.UNKNOWN_TYPE, "Push type is not switchable: $type")
        mutes.setMuted(accountId, type, muted = !requireNotNull(request.enabled))
        return Response.ok(type.toPreference(mutes.mutedTypes(accountId), allChannelMutes.mutedTypes(accountId)))
    }

    /** 「모든 채널 끄기」는 끌 수 있는 종류로 정한 것만 효력이 있다(브로커가 그 목록만 본다) */
    private fun String.toPreference(pushMuted: Set<String>, allMuted: Set<String>) =
        PushPreferenceResponse(this, push = this !in pushMuted, allChannelsMuted = this in notificationPreferences.mutableTypes && this in allMuted)

    private fun PushDeviceRequest.toRegistration(): PushRegistration {
        val kind = kind?.trim().orEmpty().ifEmpty { throw invalid("kind") }
        return if (kind == PushKinds.WEBPUSH) {
            PushRegistration(
                kind = kind, platform = PushPlatforms.WEB, address = endpoint ?: throw invalid("endpoint"),
                p256dh = keys?.p256dh ?: throw invalid("keys"), authSecret = keys.auth ?: throw invalid("keys"), label = label,
            )
        } else {
            PushRegistration(kind = kind, platform = platform ?: throw invalid("platform"), address = token ?: throw invalid("token"), label = label)
        }
    }

    private fun invalid(reason: String) = PushException(PushErrorCode.INVALID_DEVICE, "Invalid push device: $reason", reason)

    private fun PushDevice.toResponse() = PushDeviceResponse(installationId, kind, platform, label, createdAt, lastSeenAt)

    private fun Authentication?.accountId(): String =
        this?.takeIf { it.isAuthenticated }?.name
            ?: throw ApplicationException("Authentication required", HttpStatus.UNAUTHORIZED, "Authentication required")
}
