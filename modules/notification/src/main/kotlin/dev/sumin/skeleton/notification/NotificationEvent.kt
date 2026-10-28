package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.id.SortableIds
import java.time.Instant

/**
 * [link] 는 클릭하면 갈 **앱 안** 경로다 - `/` 로 시작하고 `//` 로 시작하지 않으며(프로토콜 상대 주소 · 열린 리다이렉트 막이) 1000자 이하.
 * 브라우저가 `/\` 를 `//` 처럼 읽으므로 역슬래시와 제어 문자도 거절한다.
 * [collapseKey] 가 있으면 받는 사람의 **안 읽은** 같은 열쇠 줄에 접힌다(내용은 새 것, 수 +1, 읽음 주소는 처음 이벤트 id). 255자 이하.
 * 둘 다 null 이면 지금까지와 같다.
 */
data class NotificationEvent(
    val topic: String,
    val type: String,
    val id: String = SortableIds.system.newId("evt_"),
    val recipientIds: Set<String> = emptySet(),
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    val title: String? = null,
    val message: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val link: String? = null,
    val collapseKey: String? = null,
) {
    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(recipientIds.none { it.isBlank() }) { "recipientIds must not contain blank values" }
        link?.let {
            require(isValidLink(it)) {
                "link must be an in-app path: a single leading '/', at most $MAX_LINK_LENGTH characters, no backslash or control character"
            }
        }
        collapseKey?.let {
            require(it.isNotBlank()) { "collapseKey must not be blank" }
            require(it.length <= MAX_COLLAPSE_KEY_LENGTH) { "collapseKey must be at most $MAX_COLLAPSE_KEY_LENGTH characters" }
        }
    }

    companion object {
        private const val MAX_LINK_LENGTH = 1000
        private const val MAX_COLLAPSE_KEY_LENGTH = 255

        /** [link] 로 쓸 수 있는 글자인가 - 남이 준 링크(보상 지급기 …)를 알림에 싣기 전에 거를 때 쓴다 */
        fun isValidLink(link: String): Boolean =
            link.length <= MAX_LINK_LENGTH && link.startsWith("/") && !link.startsWith("//") &&
                link.none { it == '\\' || it.isISOControl() }
    }
}

enum class NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}
