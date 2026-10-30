package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 이 앱의 도메인 에러 코드 — 코드는 `NOTES.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class NotesErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("NOTES.NOT_FOUND", HttpStatus.NOT_FOUND, "Note not found"),
    TEAM_ATTACHMENT_UNSUPPORTED("NOTES.TEAM_ATTACHMENT_UNSUPPORTED", HttpStatus.BAD_REQUEST, "Team notes cannot carry attachments"),
}

/** 없는 노트와 남의 노트는 구별하지 않는다 — 둘 다 404 (남의 노트의 존재를 알려 주지 않는다) */
class NoteNotFoundException(id: Any) : ApplicationException(
    message = "Note not found: $id",
    errorCode = NotesErrorCode.NOT_FOUND,
)

/** 첨부 키 검사가 계정 기준이라 팀 노트의 첨부는 지원하지 않는다 - 서버가 막는다(화면이 아니라) */
class TeamNoteAttachmentException : ApplicationException(
    message = "Team notes cannot carry attachments",
    errorCode = NotesErrorCode.TEAM_ATTACHMENT_UNSUPPORTED,
)
