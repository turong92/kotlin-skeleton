package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.apikey.ApiKeyScope
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.idempotency.IdempotentOperation
import dev.sumin.skeleton.workspace.CurrentWorkspace
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * HTTP 변환만 — 규칙은 [NoteService]. 호출자는 `Authentication.name`(= 계정 id)이고, 인증 없는 호출은 보안 체인이 401 로 막는다.
 * API 키로 열린 곳은 `@ApiKeyScope` 를 단 읽기(목록 · 요약 · 하나) 셋과 쓰기(만들기 · 고치기 · 지우기) 셋뿐이다 - 내보내기 같은 나머지는 키로 닿지 않는다(기본 거부).
 */
@Validated
@RestController
@RequestMapping("/api/v1/notes")
@Tag(name = "Notes")
class NoteController(private val service: NoteService, private val workspace: CurrentWorkspace) {
    /** 헤더 [CurrentWorkspace.HEADER] 가 있으면 그 워크스페이스의 노트(멤버가 아니면 404), 없으면 예전처럼 내 노트 */
    private fun scope(authentication: Authentication) = NoteScope(authentication.name, workspace.get())

    @PostMapping
    @ApiKeyScope(NoteApiKeyScopes.WRITE)
    @CreatedOperation
    @IdempotentOperation
    fun create(authentication: Authentication, @Valid @RequestBody request: CreateNoteRequest) =
        service.create(scope(authentication), request).toResponse().let { note ->
            Response.created(
                ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(note.id).toUri(),
                note,
            )
        }

    @GetMapping
    @ApiKeyScope(NoteApiKeyScopes.READ)
    fun list(
        authentication: Authentication,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: NoteStatus?,
        @RequestParam(required = false) pinned: Boolean?,
    ): PageResponse<NoteResponse> {
        val page = service.list(scope(authentication), pageQuery, q, status, pinned)
        return Response.ok(page.notes.map { it.toResponse() }, pageQuery.toPagination(page.totalElements))
    }

    @GetMapping("/summary")
    @ApiKeyScope(NoteApiKeyScopes.READ)
    fun summary(authentication: Authentication) = Response.ok(service.summary(scope(authentication)))

    @GetMapping("/{id}")
    @ApiKeyScope(NoteApiKeyScopes.READ)
    fun get(authentication: Authentication, @PathVariable id: String) =
        Response.ok(service.get(scope(authentication), id).toResponse())

    @PutMapping("/{id}")
    @ApiKeyScope(NoteApiKeyScopes.WRITE)
    fun replace(authentication: Authentication, @PathVariable id: String, @Valid @RequestBody request: ReplaceNoteRequest) =
        Response.ok(service.replace(scope(authentication), id, request).toResponse())

    @DeleteMapping("/{id}")
    @ApiKeyScope(NoteApiKeyScopes.WRITE)
    @NoContentOperation
    fun delete(authentication: Authentication, @PathVariable id: String) =
        service.delete(scope(authentication), id).let { Response.noContent() }

    @PostMapping("/{id}/export")
    @AcceptedOperation
    fun export(authentication: Authentication, @PathVariable id: String) =
        Response.accepted(ExportStartedResponse(service.startExport(scope(authentication), id).toString()))
}
