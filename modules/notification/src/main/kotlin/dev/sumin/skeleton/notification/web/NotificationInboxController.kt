package dev.sumin.skeleton.notification.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.notification.NotificationInboxQuery
import dev.sumin.skeleton.notification.NotificationInboxRecord
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationSeverity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.time.Instant
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class NotificationInboxItemResponse(
    val id: String,
    val eventId: String,
    val recipientId: String,
    val topic: String,
    val type: String,
    val severity: NotificationSeverity,
    val title: String?,
    val message: String?,
    val payload: Map<String, Any?>,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationReadResponse(
    val eventId: String,
    val readAt: Instant,
)

data class NotificationReadAllResponse(
    val updated: Int,
)

/**
 * 호출자의 알림 받은편지함 — 호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이다.
 * 인증 없는 호출은 401. 다른 사람의 알림은 읽음 처리할 수 없다(없는 것과 같은 404).
 * 이 클래스는 [NotificationInboxWebAutoConfiguration] 이 등록한다 (`skeleton.notification.inbox.enabled=false` 로 끈다).
 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
class NotificationInboxController(
    private val inboxRepository: NotificationInboxRepository,
) {
    @Operation(
        summary = "List my notifications",
        description = "Returns the current principal's notification inbox with page metadata.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.PAGE, value = NotificationInboxItemResponse::class)
    @GetMapping
    fun list(
        authentication: Authentication?,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) unreadOnly: Boolean?,
        @RequestParam(required = false) topic: String?,
    ): PageResponse<NotificationInboxItemResponse> {
        val query = NotificationInboxQuery(
            page = pageQuery.page,
            size = pageQuery.size,
            unreadOnly = unreadOnly ?: false,
            topic = topic,
        )
        val page = inboxRepository.findByRecipient(authentication.recipientId(), query)
        return Response.ok(
            values = page.values.map { it.toResponse() },
            pagination = pageQuery.toPagination(page.totalElements),
        )
    }

    @Operation(summary = "Mark notification read")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = NotificationReadResponse::class)
    @PatchMapping("/{eventId}/read")
    fun markRead(
        authentication: Authentication?,
        @PathVariable eventId: String,
    ) = inboxRepository.markRead(authentication.recipientId(), eventId)
        ?.let { record ->
            Response.ok(NotificationReadResponse(eventId = record.event.id, readAt = requireNotNull(record.readAt)))
        }
        ?: throw NotificationNotFoundException(eventId)

    @Operation(summary = "Mark all notifications read")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = NotificationReadAllResponse::class)
    @PatchMapping("/read-all")
    fun markAllRead(authentication: Authentication?) =
        Response.ok(NotificationReadAllResponse(updated = inboxRepository.markAllRead(authentication.recipientId())))

    private fun NotificationInboxRecord.toResponse(): NotificationInboxItemResponse =
        NotificationInboxItemResponse(
            id = id,
            eventId = event.id,
            recipientId = recipientId,
            topic = event.topic,
            type = event.type,
            severity = event.severity,
            title = event.title,
            message = event.message,
            payload = event.payload,
            createdAt = event.createdAt,
            readAt = readAt,
        )

    private fun Authentication?.recipientId(): String =
        this?.takeIf { it.isAuthenticated }?.name
            ?: throw ApplicationException("Authentication required", HttpStatus.UNAUTHORIZED, "Authentication required")
}

class NotificationNotFoundException(eventId: String) : ApplicationException(
    message = "Notification not found: $eventId",
    status = HttpStatus.NOT_FOUND,
    title = "Notification not found",
)
