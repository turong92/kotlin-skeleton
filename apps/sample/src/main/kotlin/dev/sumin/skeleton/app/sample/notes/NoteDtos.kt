package dev.sumin.skeleton.app.sample.notes

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** 요청 DTO — Bean Validation 이 `ApiError.errors[]` 의 `field`(= JSON 속성 이름)와 `code`(= 제약 이름)를 만든다 */
data class CreateNoteRequest(
    @field:NotBlank @field:Size(max = 80) val title: String = "",
    @field:Size(max = 5000) val body: String = "",
    val status: NoteStatus = NoteStatus.DRAFT,
    val pinned: Boolean = false,
)

/** PUT 은 통째로 바꾼다 — 보내지 않은 값은 기본값으로 돌아간다 */
data class ReplaceNoteRequest(
    @field:NotBlank @field:Size(max = 80) val title: String = "",
    @field:Size(max = 5000) val body: String = "",
    val status: NoteStatus = NoteStatus.DRAFT,
    val pinned: Boolean = false,
    @field:OwnAttachmentKey val attachmentKey: String? = null,
    @field:Size(max = 255) val attachmentName: String? = null,
)

data class NoteResponse(
    val id: String,
    val title: String,
    val body: String,
    val status: NoteStatus,
    val pinned: Boolean,
    val attachmentKey: String?,
    val attachmentName: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NoteSummaryResponse(
    val total: Long,
    val pinned: Long,
    val withAttachment: Long,
    val draft: Long,
    val active: Long,
    val archived: Long,
)

data class ExportStartedResponse(val jobId: String)

fun Note.toResponse() = NoteResponse(
    id = requireNotNull(id).toString(),
    title = title,
    body = body,
    status = status,
    pinned = pinned,
    attachmentKey = attachmentKey,
    attachmentName = attachmentName,
    createdAt = audit.createdAt,
    updatedAt = audit.updatedAt,
)
