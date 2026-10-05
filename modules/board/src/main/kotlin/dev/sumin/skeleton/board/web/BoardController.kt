package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.CreatedOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * 게시판 설정 보고 · 게시판 목록. 이 클래스는 [BoardWebAutoConfiguration] 이 등록한다 (`skeleton.board.http.enabled=false` 로 끈다).
 * 경로는 `skeleton.board.http.base-path`(기본 `/api/v1/boards`).
 */
@RestController
@RequestMapping("\${skeleton.board.http.base-path:/api/v1/boards}")
@Tag(name = "Board")
class BoardController(private val service: BoardService, private val callers: BoardCallers) {
    @Operation(summary = "Board configuration", description = "Reaction types and mode, limits, and whether the caller moderates.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = BoardConfigResponse::class)
    @GetMapping("/config")
    fun config(authentication: Authentication?) =
        Response.ok(service.config(callers.reader(authentication)).toResponse())

    @Operation(summary = "List boards")
    @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = BoardResponse::class)
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<BoardResponse> {
        callers.reader(authentication)
        return Response.ok(service.list().map { it.toResponse() })
    }

    @Operation(summary = "Get a board")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = BoardResponse::class)
    @GetMapping("/{code}")
    fun get(authentication: Authentication?, @PathVariable code: String): DataResponse<BoardResponse> {
        callers.reader(authentication)
        return Response.ok(service.get(code).toResponse())
    }

    @Operation(summary = "Create a board (moderator)")
    @CreatedOperation
    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateBoardRequest) =
        service.create(callers.require(authentication), request.code.orEmpty(), request.name.orEmpty(), request.description).toResponse().let { board ->
            Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{code}").buildAndExpand(board.code).toUri(), board)
        }
}
