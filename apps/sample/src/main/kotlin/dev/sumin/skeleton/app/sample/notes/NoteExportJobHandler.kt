package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.workspace.Workspaces
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * `note-export` 잡: 노트를 마크다운으로 만들어 저장소(`<key-prefix>/<계정 id>/exports/<noteId>.md`)에 올리고 요청한 사람에게 알린다.
 * 잡은 다시 돌 수 있으므로 멱등이다 — 같은 키를 덮어쓰고, 알림 이벤트 id 는 잡 id 라 받은편지함에 한 번만 쌓인다.
 * 노트가 이미 사라졌거나 저장소가 없으면 다시 해도 소용없으니 [PermanentJobFailureException] 으로 바로 DEAD.
 */
@Component
class NoteExportJobHandler(
    private val notes: NoteRepository,
    private val storage: ObjectProvider<ObjectStorage>,
    private val keys: OwnedKeys,
    private val notifier: NoteNotifier,
    private val json: ObjectMapper,
    private val workspaces: Workspaces,
) : JobHandler {
    override val type = TYPE

    override fun handle(job: Job) {
        val payload = json.readTree(job.payloadJson)
        val requester = payload["requesterId"].asString()
        val noteId = UUID.fromString(payload["noteId"].asString())
        val workspaceId = payload["workspaceId"]?.takeIf { !it.isNull }?.asString()
        // 팀 노트는 요청한 사람이 지금도 멤버일 때만(그 사이 추방됐으면 끝낸다), 내 노트는 예전처럼 주인 것만
        val note = (if (workspaceId == null) notes.findByIdAndOwnerIdAndWorkspaceIdIsNull(noteId, requester) else notes.findByIdAndWorkspaceId(noteId, workspaceId)?.takeIf { workspaces.membershipOf(requester, workspaceId) != null })
            ?: throw PermanentJobFailureException("note $noteId no longer exists or is not readable by $requester")
        val objectStorage = storage.getIfAvailable() ?: throw PermanentJobFailureException("object storage is not configured")

        val key = "${keys.prefix(requester)}exports/$noteId.md"
        objectStorage.upload(
            UploadObjectRequest(
                key = ObjectKey(key),
                content = markdown(note).toByteArray(),
                contentType = "text/markdown; charset=utf-8",
                contentDisposition = "attachment; filename=\"note-${noteId.toString().take(8)}.md\"",
            ),
        )
        notifier.exported(note, job.id, key, requester)
    }

    private fun markdown(note: Note): String = buildString {
        appendLine("# ${note.title}")
        appendLine()
        if (note.body.isNotBlank()) { appendLine(note.body); appendLine() }
        appendLine("---")
        appendLine("상태: ${note.status} · 고정: ${if (note.pinned) "예" else "아니오"}")
        note.attachmentName?.let { appendLine("첨부: $it") }
        appendLine("만든 시각: ${note.audit.createdAt} · 수정한 시각: ${note.audit.updatedAt}")
    }

    companion object {
        const val TYPE = "note-export"
    }
}
