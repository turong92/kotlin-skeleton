package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.UploadObjectRequest
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * `note-export` 잡: 노트를 마크다운으로 만들어 저장소(`<key-prefix>/<계정 id>/exports/<noteId>.md`)에 올리고 주인에게 알린다.
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
) : JobHandler {
    override val type = TYPE

    override fun handle(job: Job) {
        val payload = json.readTree(job.payloadJson)
        val owner = payload["ownerId"].asString()
        val noteId = UUID.fromString(payload["noteId"].asString())
        val note = notes.findByIdAndOwnerId(noteId, owner) ?: throw PermanentJobFailureException("note $noteId no longer exists")
        val objectStorage = storage.getIfAvailable() ?: throw PermanentJobFailureException("object storage is not configured")

        val key = "${keys.prefix(owner)}exports/$noteId.md"
        objectStorage.upload(
            UploadObjectRequest(
                key = ObjectKey(key),
                content = markdown(note).toByteArray(),
                contentType = "text/markdown; charset=utf-8",
                contentDisposition = "attachment; filename=\"note-${noteId.toString().take(8)}.md\"",
            ),
        )
        notifier.exported(note, job.id, key)
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
