package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import org.springframework.stereotype.Component

/**
 * 노트에서 일어난 일을 주인에게 알린다. 한 번 publish 하면 받은편지함(notification-jdbc)에 저장되고 SSE(notification-sse)로 실시간 전달된다.
 * 계약: 토픽 `notes`, 타입 `NOTE_CREATED | NOTE_UPDATED | NOTE_DELETED | NOTE_EXPORTED`, `payload.noteId`(딥링크용) · `payload.noteTitle`.
 */
@Component
class NoteNotifier(private val publisher: NotificationPublisher) {
    fun created(note: Note) = send(note.ownerId, "NOTE_CREATED", NotificationSeverity.SUCCESS, "노트를 만들었어요", "‘${note.title}’ 노트가 저장됐어요.", payloadOf(note))

    fun updated(note: Note) = send(note.ownerId, "NOTE_UPDATED", NotificationSeverity.INFO, "노트를 수정했어요", "‘${note.title}’ 노트의 변경 사항이 저장됐어요.", payloadOf(note))

    fun deleted(note: Note) = send(note.ownerId, "NOTE_DELETED", NotificationSeverity.INFO, "노트를 삭제했어요", "‘${note.title}’ 노트를 삭제했어요.", payloadOf(note))

    /** 같은 잡이 다시 돌아도 알림이 한 번만 쌓이도록 이벤트 id 를 잡 id 로 정한다 (받은편지함은 같은 id 를 한 번만 저장한다) */
    fun exported(note: Note, jobId: Long, exportKey: String) = send(
        note.ownerId, "NOTE_EXPORTED", NotificationSeverity.SUCCESS, "노트 내보내기 완료", "‘${note.title}’ 노트를 마크다운 파일로 내보냈어요.",
        payloadOf(note) + mapOf("jobId" to jobId.toString(), "exportKey" to exportKey),
        eventId = "note-export-$jobId",
    )

    private fun payloadOf(note: Note): Map<String, Any?> = mapOf("noteId" to note.id.toString(), "noteTitle" to note.title)

    private fun send(
        owner: String,
        type: String,
        severity: NotificationSeverity,
        title: String,
        message: String,
        payload: Map<String, Any?>,
        eventId: String? = null,
    ) {
        val event = NotificationEvent(
            topic = TOPIC, type = type, recipientIds = setOf(owner), severity = severity,
            title = title, message = message, payload = payload,
        )
        publisher.publish(if (eventId == null) event else event.copy(id = eventId))
    }

    companion object {
        const val TOPIC = "notes"
    }
}
