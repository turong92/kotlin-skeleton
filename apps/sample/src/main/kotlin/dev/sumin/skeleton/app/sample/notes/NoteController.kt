package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.idempotency.IdempotentOperation
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

/** HTTP 변환만 — 규칙은 [NoteService]. 호출자는 `Authentication.name`(= 계정 id)이고, 인증 없는 호출은 보안 체인이 401 로 막는다. */
@Validated
@RestController
@RequestMapping("/api/v1/notes")
@Tag(name = "Notes")
class NoteController(private val service: NoteService) {
    @PostMapping
    @CreatedOperation
    @IdempotentOperation
    fun create(authentication: Authentication, @Valid @RequestBody request: CreateNoteRequest) =
        service.create(authentication.name, request).toResponse().let { note ->
            Response.created(
                ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(note.id).toUri(),
                note,
            )
        }

    @GetMapping
    fun list(
        authentication: Authentication,
        @Valid @ParameterObject @ModelAttribute pageQuery: PageQuery,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: NoteStatus?,
        @RequestParam(required = false) pinned: Boolean?,
    ): PageResponse<NoteResponse> {
        val page = service.list(authentication.name, pageQuery, q, status, pinned)
        return Response.ok(page.notes.map { it.toResponse() }, pageQuery.toPagination(page.totalElements))
    }

    @GetMapping("/summary")
    fun summary(authentication: Authentication) = Response.ok(service.summary(authentication.name))

    @GetMapping("/{id}")
    fun get(authentication: Authentication, @PathVariable id: String) =
        Response.ok(service.get(authentication.name, id).toResponse())

    @PutMapping("/{id}")
    fun replace(authentication: Authentication, @PathVariable id: String, @Valid @RequestBody request: ReplaceNoteRequest) =
        Response.ok(service.replace(authentication.name, id, request).toResponse())

    @DeleteMapping("/{id}")
    @NoContentOperation
    fun delete(authentication: Authentication, @PathVariable id: String) =
        service.delete(authentication.name, id).let { Response.noContent() }

    @PostMapping("/{id}/export")
    @AcceptedOperation
    fun export(authentication: Authentication, @PathVariable id: String) =
        Response.accepted(ExportStartedResponse(service.startExport(authentication.name, id).toString()))
}
