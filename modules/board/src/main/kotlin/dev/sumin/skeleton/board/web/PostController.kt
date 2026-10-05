package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardErrorCode
import dev.sumin.skeleton.board.BoardException
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.CreatePost
import dev.sumin.skeleton.board.PostListRequest
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.PostSort
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.ReactionService
import dev.sumin.skeleton.board.UpdatePost
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

/** 글 · 글 반응. HTTP 변환만 — 규칙은 [PostService] · [ReactionService]. [BoardWebAutoConfiguration] 이 등록한다. */
@RestController
@RequestMapping("\${skeleton.board.http.base-path:/api/v1/boards}/{code}/posts")
@Tag(name = "Board")
class PostController(
    private val service: PostService,
    private val reactions: ReactionService,
    private val callers: BoardCallers,
    private val properties: BoardProperties,
) {
    @Operation(
        summary = "List posts",
        description = "Pinned posts first, then sort=latest|reactions|comments. reaction=<code> sorts by that type's count. " +
            "q matches title and body (wildcards are escaped). status and mine: see docs/modules/board.md.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.PAGE, value = PostSummaryResponse::class)
    @GetMapping
    fun list(
        authentication: Authentication?,
        @PathVariable code: String,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) reaction: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) mine: Boolean?,
    ): PageResponse<PostSummaryResponse> {
        val page = service.list(
            callers.reader(authentication), code,
            PostListRequest(
                page = pageQuery.page, size = pageQuery.size,
                sort = enumParam<PostSort>("sort", sort) ?: PostSort.LATEST,
                q = q, reaction = reaction, status = enumParam<PostStatus>("status", status), mine = mine ?: false,
            ),
        )
        return Response.ok(page.values.map { it.toResponse() }, PaginationMeta.of(pageQuery.page, properties.pageSize(pageQuery.size), page.totalElements))
    }

    @Operation(summary = "Get a post", description = "Counts one view per call. Hidden, deleted and draft posts are 404 unless you are the author or a moderator.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PostDetailResponse::class)
    @GetMapping("/{postId}")
    fun get(authentication: Authentication?, @PathVariable code: String, @PathVariable postId: Long): DataResponse<PostDetailResponse> =
        Response.ok(service.get(callers.reader(authentication), code, postId).toResponse(properties.excerptLength))

    @Operation(summary = "Create a post")
    @CreatedOperation
    @IdempotentOperation
    @PostMapping
    fun create(authentication: Authentication?, @PathVariable code: String, @RequestBody request: CreatePostRequest) =
        service.create(callers.require(authentication), code, CreatePost(request.title, request.body, request.attachments, request.status))
            .toResponse(properties.excerptLength).let { post ->
                Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(post.id).toUri(), post)
            }

    @Operation(summary = "Edit a post (author or moderator)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PostDetailResponse::class)
    @PatchMapping("/{postId}")
    fun update(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @RequestBody request: UpdatePostRequest,
    ): DataResponse<PostDetailResponse> =
        Response.ok(
            service.update(callers.require(authentication), code, postId, UpdatePost(request.title, request.body, request.attachments, request.status))
                .toResponse(properties.excerptLength),
        )

    @Operation(summary = "Delete a post (author or moderator; soft delete)")
    @NoContentOperation
    @DeleteMapping("/{postId}")
    fun delete(authentication: Authentication?, @PathVariable code: String, @PathVariable postId: Long) =
        service.delete(callers.require(authentication), code, postId).let { Response.noContent() }

    @Operation(summary = "Moderate a post (moderator): hide, restore, delete, pin")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = PostDetailResponse::class)
    @PutMapping("/{postId}/moderation")
    fun moderate(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @RequestBody request: PostModerationRequest,
    ): DataResponse<PostDetailResponse> =
        Response.ok(service.moderate(callers.require(authentication), code, postId, request.status, request.pinned).toResponse(properties.excerptLength))

    @Operation(summary = "React to a post", description = "type must be one of the configured reaction types (GET …/config). SINGLE mode switches the caller's reaction.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = ReactionStateResponse::class)
    @PutMapping("/{postId}/reactions")
    fun react(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @RequestBody request: ReactionRequest,
    ): DataResponse<ReactionStateResponse> =
        Response.ok(reactions.reactToPost(callers.require(authentication), code, postId, request.type).toResponse())

    @Operation(summary = "Remove the caller's reaction (one type, or all when type is omitted)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = ReactionStateResponse::class)
    @DeleteMapping("/{postId}/reactions")
    fun unreact(
        authentication: Authentication?,
        @PathVariable code: String,
        @PathVariable postId: Long,
        @RequestParam(required = false) type: String?,
    ): DataResponse<ReactionStateResponse> =
        Response.ok(reactions.removeFromPost(callers.require(authentication), code, postId, type).toResponse())
}

/** 쿼리 파라미터의 enum 을 대소문자 구별 없이 읽는다 (`sort=latest` 도 `sort=LATEST` 도) — 모르는 값은 BOARD.CONTENT_INVALID */
internal inline fun <reified E : Enum<E>> enumParam(name: String, raw: String?): E? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw BoardException(BoardErrorCode.CONTENT_INVALID, "$name must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}: $value")
    }
