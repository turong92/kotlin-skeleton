package dev.sumin.skeleton.notification.broadcast.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.notification.broadcast.Broadcast
import dev.sumin.skeleton.notification.broadcast.BroadcastAudience
import dev.sumin.skeleton.notification.broadcast.BroadcastContent
import dev.sumin.skeleton.notification.broadcast.BroadcastOptions
import dev.sumin.skeleton.notification.broadcast.BroadcastPermissions
import dev.sumin.skeleton.notification.broadcast.BroadcastStatus
import dev.sumin.skeleton.notification.broadcast.Broadcasts
import dev.sumin.skeleton.notification.broadcast.ChannelStats
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class AudienceBody(val kind: String? = null, val param: String? = null)

data class BroadcastBody(
    val type: String? = null,
    val title: String? = null,
    val body: String? = null,
    val link: String? = null,
    val channels: List<String>? = null,
    val audience: AudienceBody? = null,
)

data class SendBody(val scheduledAt: Instant? = null)

data class AudienceCountResponse(val count: Long)

data class AudienceResponse(val kind: String, val param: String?)

/** R1 화면의 `Broadcast` 타입과 같은 모양 — `channels` 는 알림함 말고 고른 채널 */
data class BroadcastResponse(
    val id: String,
    val status: BroadcastStatus,
    val type: String,
    val title: String,
    val body: String?,
    val link: String?,
    val channels: List<String>,
    val audience: AudienceResponse,
    val scheduledAt: Instant?,
    val estimate: Long?,
    val recipients: Long,
    val failureReason: String?,
    val createdBy: String,
    val createdAt: Instant,
    val sentBy: String?,
    val sendRequestedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val cancelledBy: String?,
    val cancelledAt: Instant?,
)

data class InboxStatsResponse(val stored: Long, val read: Long)

data class BroadcastStatsResponse(val inbox: InboxStatsResponse?, val channels: Map<String, ChannelStats>)

/** [BroadcastResponse] 의 모든 칸 + `stats` */
data class BroadcastDetailResponse(
    val id: String,
    val status: BroadcastStatus,
    val type: String,
    val title: String,
    val body: String?,
    val link: String?,
    val channels: List<String>,
    val audience: AudienceResponse,
    val scheduledAt: Instant?,
    val estimate: Long?,
    val recipients: Long,
    val failureReason: String?,
    val createdBy: String,
    val createdAt: Instant,
    val sentBy: String?,
    val sendRequestedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val cancelledBy: String?,
    val cancelledAt: Instant?,
    val stats: BroadcastStatsResponse,
)

private fun Broadcast.toResponse() = BroadcastResponse(
    id, status, content.type, content.title, content.body, content.link, content.channels,
    AudienceResponse(content.audience.kind, content.audience.param), scheduledAt, estimate, recipients, failureReason,
    createdBy, createdAt, sentBy, sendRequestedAt, startedAt, finishedAt, cancelledBy, cancelledAt,
)

/**
 * 공지 운영자 경로(`skeleton.notification.broadcast.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 보기(`notification-broadcast.read`)와
 * 보내기(`notification-broadcast.send`)를 매 호출 판정한다 - 익명 401 · 권한 없음 403. 명령(보내기 · 예약 풀기 · 취소 · 이어 보내기 · 지우기)은 204, 테스트는 202.
 */
@RestController
@RequestMapping("\${skeleton.notification.broadcast.admin.base-path:/api/v1/admin/broadcasts}")
class BroadcastAdminController(
    private val broadcasts: Broadcasts,
    private val access: OperatorAccess,
) {
    @GetMapping("/options")
    fun options(authentication: Authentication?): DataResponse<BroadcastOptions> {
        operator(authentication, BroadcastPermissions.READ)
        return Response.ok(broadcasts.options())
    }

    @GetMapping("/audience-count")
    fun audienceCount(authentication: Authentication?, @RequestParam kind: String, @RequestParam(required = false) param: String?): DataResponse<AudienceCountResponse> {
        operator(authentication, BroadcastPermissions.READ)
        return Response.ok(AudienceCountResponse(broadcasts.audienceCount(kind, param)))
    }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) limit: Int?,
    ): ListResponse<BroadcastResponse> {
        operator(authentication, BroadcastPermissions.READ)
        val parsed = status?.takeIf { it.isNotBlank() }?.let { raw ->
            BroadcastStatus.entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw ApplicationException("Unknown status '$raw'", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        }
        return Response.ok(broadcasts.list(parsed, before, limit ?: DEFAULT_LIMIT).map { it.toResponse() })
    }

    @GetMapping("/{id}")
    fun get(authentication: Authentication?, @PathVariable id: String): DataResponse<BroadcastDetailResponse> {
        operator(authentication, BroadcastPermissions.READ)
        val detail = broadcasts.get(id)
        val r = detail.broadcast.toResponse()
        return Response.ok(
            BroadcastDetailResponse(
                r.id, r.status, r.type, r.title, r.body, r.link, r.channels, r.audience, r.scheduledAt, r.estimate, r.recipients, r.failureReason,
                r.createdBy, r.createdAt, r.sentBy, r.sendRequestedAt, r.startedAt, r.finishedAt, r.cancelledBy, r.cancelledAt,
                BroadcastStatsResponse(detail.inbox?.let { InboxStatsResponse(it.stored, it.read) }, detail.channels),
            ),
        )
    }

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody body: BroadcastBody): DataResponse<BroadcastResponse> {
        val operator = operator(authentication, BroadcastPermissions.SEND)
        return Response.ok(broadcasts.create(operator.accountId, body.toContent()).toResponse())
    }

    @PutMapping("/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody body: BroadcastBody): DataResponse<BroadcastResponse> {
        operator(authentication, BroadcastPermissions.SEND)
        return Response.ok(broadcasts.update(id, body.toContent()).toResponse())
    }

    @DeleteMapping("/{id}")
    fun delete(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        operator(authentication, BroadcastPermissions.SEND)
        broadcasts.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/test")
    fun test(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val operator = operator(authentication, BroadcastPermissions.SEND)
        broadcasts.test(operator.accountId, id)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{id}/send")
    fun send(authentication: Authentication?, @PathVariable id: String, @RequestBody(required = false) body: SendBody?): ResponseEntity<Void> {
        val operator = operator(authentication, BroadcastPermissions.SEND)
        broadcasts.send(operator.accountId, id, body?.scheduledAt)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/unschedule")
    fun unschedule(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        operator(authentication, BroadcastPermissions.SEND)
        broadcasts.unschedule(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/cancel")
    fun cancel(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val operator = operator(authentication, BroadcastPermissions.SEND)
        broadcasts.cancel(operator.accountId, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/resume")
    fun resume(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val operator = operator(authentication, BroadcastPermissions.SEND)
        broadcasts.resume(operator.accountId, id)
        return ResponseEntity.noContent().build()
    }

    private fun BroadcastBody.toContent() = BroadcastContent(
        type = type.orEmpty(), title = title.orEmpty(), body = body, link = link, channels = channels.orEmpty(),
        audience = BroadcastAudience(audience?.kind.orEmpty(), audience?.param),
    )

    /** 익명 401 · [permission] 없음 403 */
    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, permission) }
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}
