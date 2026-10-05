package dev.sumin.skeleton.board.web

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
            "use parentId and depth to nest. Deleted and hidden comments stay with body=null.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.PAGE, value = CommentResponse::class)
    @GetMapping
    fun list(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) sort: String?,
    ): PageResponse<CommentResponse> {
        val page = service.list(
            callers.reader(authentication), code, postId, pageQuery.page, pageQuery.size,
            enumParam<CommentSort>("sort", sort) ?: CommentSort.OLDEST,
        )
        return Response.ok(page.values.map { it.toResponse(thread = true) }, PaginationMeta.of(pageQuery.page, properties.pageSize(pageQuery.size), page.totalElements))
    }

    @Operation(summary = "Comment on a post, or reply when parentId is given (beyond max-comment-depth: 422 BOARD.COMMENT_TOO_DEEP)")
    @CreatedOperation
    @IdempotentOperation
    @PostMapping
    fun create(authentication: Authentication?, @PathVariable code: String, @PathVariable postId: Long, @RequestBody request: CreateCommentRequest) =
        service.create(callers.require(authentication), code, postId, request.parentId, request.body).toResponse().let { comment ->
            Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(comment.id).toUri(), comment)
        }

    @Operation(summary = "Edit a comment (author)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CommentResponse::class)
    @PatchMapping("/{commentId}")
    fun update(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: UpdateCommentRequest,
    ): DataResponse<CommentResponse> =
        Response.ok(service.update(callers.require(authentication), code, postId, commentId, request.body).toResponse())

    @Operation(summary = "Delete a comment (author or moderator; soft — the thread keeps its shape)")
    @NoContentOperation
    @DeleteMapping("/{commentId}")
    fun delete(authentication: Authentication?, @PathVariable code: String, @PathVariable postId: Long, @PathVariable commentId: Long) =
        service.delete(callers.require(authentication), code, postId, commentId).let { Response.noContent() }

    @Operation(summary = "Moderate a comment (moderator): hide, restore, delete")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = CommentResponse::class)
    @PutMapping("/{commentId}/moderation")
    fun moderate(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @PathVariable commentId: Long,
        @RequestBody request: CommentModerationRequest,
    ): DataResponse<CommentResponse> =
        Response.ok(
            service.moderate(callers.require(authentication), code, postId, commentId, request.status ?: CommentStatus.PUBLISHED).toResponse(),
        )

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
