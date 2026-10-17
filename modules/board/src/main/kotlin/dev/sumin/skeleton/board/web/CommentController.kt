package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.AttachmentRole
import dev.sumin.skeleton.board.AttachmentSource
import dev.sumin.skeleton.board.BoardErrorCode
import dev.sumin.skeleton.board.BoardException
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.CommentService
import dev.sumin.skeleton.board.CommentSort
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.ReactionService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.PaginationMeta
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.idempotency.IdempotentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/** 댓글 트리 · 댓글 반응. HTTP 변환만 — 규칙은 [CommentService] · [ReactionService]. [BoardWebAutoConfiguration] 이 등록한다. */
@RestController
@RequestMapping("\${skeleton.board.http.base-path:/api/v1/boards}/{code}/posts/{postId}/comments")
@Tag(name = "Board")
class CommentController(
    private val service: CommentService,
    private val reactions: ReactionService,
    private val callers: BoardCallers,
    private val properties: BoardProperties,
) {
    @Operation(
        summary = "List comment threads",
        description = "Pages the top-level comments (sort=oldest|latest|reactions). Each carries ALL its descendants flattened in creation order in `replies`; " +
            "use parentId and depth to nest. Deleted and hidden comments stay with body=null. Comments waiting for approval have no place in the thread " +
            "unless you are a moderator, the managing post owner or their author. status=PENDING lists the waiting comments flat, oldest first (managers only; " +
            "a signed-out post owner proves the post with the X-Board-Guest-Secret header).",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.PAGE, value = CommentResponse::class)
    @GetMapping
    fun list(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) revision: String?,
    ): PageResponse<CommentResponse> {
        if (revision != null && revision.isNotBlank()) {
            if (!revision.equals("pending", ignoreCase = true)) throw BoardException(BoardErrorCode.CONTENT_INVALID, "revision must be pending when given")
            val w = callers.writer(authentication, http)
            val edits = service.listRevisions(w.caller, code, postId, pageQuery.page, pageQuery.size, w.guest)
            return Response.ok(edits.values.map { it.toResponse() }, PaginationMeta.of(pageQuery.page, properties.pageSize(pageQuery.size), edits.totalElements))
        }
        if (status?.trim().equals("pending", ignoreCase = true)) {
            val w = callers.writer(authentication, http)
            val pending = service.listPending(w.caller, code, postId, pageQuery.page, pageQuery.size, w.guest)
            return Response.ok(pending.values.map { it.toResponse() }, PaginationMeta.of(pageQuery.page, properties.pageSize(pageQuery.size), pending.totalElements))
        }
        val page = service.list(
            callers.reader(authentication), code, postId, pageQuery.page, pageQuery.size,
            enumParam<CommentSort>("sort", sort) ?: CommentSort.OLDEST,
        )
        return Response.ok(page.values.map { it.toResponse(thread = true) }, PaginationMeta.of(pageQuery.page, properties.pageSize(pageQuery.size), page.totalElements))
    }

    @Operation(
        summary = "Comment on a post, or reply when parentId is given (beyond max-comment-depth: 422 BOARD.COMMENT_TOO_DEEP)",
        description = "Signed-out writers (skeleton.board.guest.enabled and the board's guestWrite != NONE) send guestName and the header X-Board-Guest-Secret instead of signing in.",
    )
    @CreatedOperation
    @IdempotentOperation(ignoredBodyFields = ["captchaToken"])
    @PostMapping
    fun create(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @RequestBody request: CreateCommentRequest,
    ) =
        callers.writer(authentication, http, request.captchaToken).let { w ->
            service.create(w.caller, code, postId, request.parentId, request.body, request.authorId, request.guestName, w.guest, request.attachments)
        }.toResponse().let { comment ->
            Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(comment.id).toUri(), comment)
        }

    @Operation(
        summary = "Short-lived signed URL for one attachment of this comment (or of its waiting edit)",
        description = "The index-th attachment (from=current, default) or of the waiting edit (from=edit). Moderators, the managing post owner (signed-out owners send the post's secret with as=owner) and the comment's author (as=author, the default). Published attachments are served from the public area (attachmentUrls) instead.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AttachmentAccessResponse::class)
    @GetMapping("/{commentId}/attachments")
    fun attachment(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestParam index: Int,
        @RequestParam(required = false) from: String?,
        @RequestParam(name = "as", required = false) role: String?,
    ): DataResponse<AttachmentAccessResponse> =
        callers.writer(authentication, http).let { w ->
            service.attachmentAccess(
                w.caller, code, postId, commentId, index,
                enumParam<AttachmentSource>("from", from) ?: AttachmentSource.CURRENT,
                enumParam<AttachmentRole>("as", role) ?: AttachmentRole.AUTHOR, w.guest,
            )
        }.let { Response.ok(AttachmentAccessResponse(it.url, it.expiresInSeconds)) }

    @Operation(summary = "Edit a comment (author)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CommentResponse::class)
    @PatchMapping("/{commentId}")
    fun update(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateCommentRequest,
    ): DataResponse<CommentResponse> =
        callers.writer(authentication, http).let { w -> Response.ok(service.update(w.caller, code, postId, commentId, request.body, w.guest, request.attachments).toResponse()) }

    @Operation(summary = "Delete a comment (author or moderator; soft — the thread keeps its shape)")
    @NoContentOperation
    @DeleteMapping("/{commentId}")
    fun delete(authentication: Authentication?, http: HttpServletRequest, @PathVariable code: String, @PathVariable postId: Long, @PathVariable commentId: Long) =
        callers.writer(authentication, http).let { w -> service.delete(w.caller, code, postId, commentId, w.guest) }.let { Response.noContent() }

    @Operation(
        summary = "Moderate a comment: approve (PENDING to PUBLISHED), hide, restore, delete",
        description = "Moderators choose any of PUBLISHED, HIDDEN, DELETED. A managing post owner (board setting ownerModeratesComments) chooses PUBLISHED or HIDDEN " +
            "and cannot restore a comment a moderator hid; a signed-out owner proves the post with the X-Board-Guest-Secret header.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CommentResponse::class)
    @PutMapping("/{commentId}/moderation")
    fun moderate(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: CommentModerationRequest,
    ): DataResponse<CommentResponse> =
        callers.writer(authentication, http).let { w ->
            Response.ok(service.moderate(w.caller, code, postId, commentId, request.status ?: CommentStatus.PUBLISHED, w.guest).toResponse())
        }

    @Operation(
        summary = "Approve or reject a comment's pending edit",
        description = "Managers only (moderator, or the managing post owner; a signed-out owner sends X-Board-Guest-Secret). decision=APPROVE publishes the waiting text.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CommentResponse::class)
    @PutMapping("/{commentId}/revision")
    fun decideRevision(
        authentication: Authentication?,
        http: HttpServletRequest,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: RevisionDecisionRequest,
    ): DataResponse<CommentResponse> =
        callers.writer(authentication, http).let { w ->
            Response.ok(
                service.decideRevision(
                    w.caller, code, postId, commentId,
                    request.decision ?: throw BoardException(BoardErrorCode.CONTENT_INVALID, "decision must be APPROVE or REJECT"), request.submittedAt, w.guest,
                ).toResponse(),
            )
        }

    @Operation(summary = "React to a comment")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = ReactionStateResponse::class)
    @PutMapping("/{commentId}/reactions")
    fun react(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: ReactionRequest,
    ): DataResponse<ReactionStateResponse> =
        Response.ok(reactions.reactToComment(callers.require(authentication), code, postId, commentId, request.type).toResponse())

    @Operation(summary = "Remove the caller's reaction on a comment")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = ReactionStateResponse::class)
    @DeleteMapping("/{commentId}/reactions")
    fun unreact(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestParam(required = false) type: String?,
    ): DataResponse<ReactionStateResponse> =
        Response.ok(reactions.removeFromComment(callers.require(authentication), code, postId, commentId, type).toResponse())
}
