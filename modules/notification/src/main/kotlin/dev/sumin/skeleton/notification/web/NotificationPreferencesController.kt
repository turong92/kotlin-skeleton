package dev.sumin.skeleton.notification.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.notification.NotificationMuteRepository
import dev.sumin.skeleton.notification.NotificationPreferencesProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class NotificationPreferenceResponse(
    val type: String,
    val muted: Boolean,
)

data class NotificationPreferencesResponse(
    val items: List<NotificationPreferenceResponse>,
)

data class NotificationPreferenceRequest(
    val muted: Boolean,
)

/**
 * 호출자의 알림 종류별 수신 설정 - 끌 수 있는 종류는 `skeleton.notification.preferences.types` 가 정한다(목록 순서 그대로 돌려준다).
 * 목록에 없는 종류는 404. 인증 없는 호출은 401. [NotificationInboxWebAutoConfiguration] 이 받은편지함과 같은 스위치로 등록한다.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@Tag(name = "Notifications")
class NotificationPreferencesController(
    private val muteRepository: NotificationMuteRepository,
    properties: NotificationPreferencesProperties,
) {
    private val types = properties.mutableTypes

    @Operation(summary = "List my notification preferences", description = "One item per mutable notification type, in configured order.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = NotificationPreferencesResponse::class)
    @GetMapping
    fun list(authentication: Authentication?): DataResponse<NotificationPreferencesResponse> {
        val recipientId = authentication.recipientId()
        val muted = if (types.isEmpty()) emptySet() else muteRepository.mutedTypes(recipientId)
        return Response.ok(NotificationPreferencesResponse(types.map { NotificationPreferenceResponse(it, it in muted) }))
    }

    @Operation(summary = "Mute or unmute a notification type")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = NotificationPreferenceResponse::class)
    @PutMapping("/{type}")
    fun set(
        authentication: Authentication?,
        @PathVariable type: String,
        @RequestBody request: NotificationPreferenceRequest,
    ): DataResponse<NotificationPreferenceResponse> {
        val recipientId = authentication.recipientId()
        if (type !in types) throw NotificationPreferenceNotFoundException(type)
        muteRepository.setMuted(recipientId, type, request.muted)
        return Response.ok(NotificationPreferenceResponse(type, request.muted))
    }

    private fun Authentication?.recipientId(): String =
        this?.takeIf { it.isAuthenticated }?.name
            ?: throw ApplicationException("Authentication required", HttpStatus.UNAUTHORIZED, "Authentication required")
}

class NotificationPreferenceNotFoundException(type: String) : ApplicationException(
    message = "Notification type is not mutable: $type",
    status = HttpStatus.NOT_FOUND,
    title = "Notification preference not found",
)
