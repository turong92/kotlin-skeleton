package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.project.ProjectContext
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 사람 × 알림 종류(`type`) 끄기 저장소 - 끈 것만 기억한다. 받은편지함 + 실시간 전달 한 길만 막는다(채널별 끄기는 없다).
 * 기본은 인메모리, `notification-jdbc` 가 표(`notification_mutes`)로 바꾼다. 어느 종류를 끌 수 있는지는 [NotificationPreferencesProperties] 가 정한다.
 */
interface NotificationMuteRepository {
    /** 이 사람이 끈 종류 전부 (끌 수 있는 종류인지는 보지 않는다 - 거르는 것은 호출자 몫) */
    fun mutedTypes(recipientId: String): Set<String>

    /** [recipientIds] 중 [type] 을 끈 사람 */
    fun mutedAmong(
        type: String,
        recipientIds: Set<String>,
    ): Set<String>

    fun setMuted(
        recipientId: String,
        type: String,
        muted: Boolean,
    )
}

/** 프로젝트 모드: 같은 계정 id 가 프로젝트 사이에서 겹쳐도 끄기가 섞이지 않는다 (인메모리 받은편지함과 같은 규칙) */
class InMemoryNotificationMuteRepository : NotificationMuteRepository {
    private val mutes: MutableSet<MuteKey> = ConcurrentHashMap.newKeySet()

    override fun mutedTypes(recipientId: String): Set<String> {
        val project = project()
        val id = recipientId.trim()
        return mutes.filter { it.project == project && it.recipientId == id }.mapTo(linkedSetOf()) { it.type }
    }

    override fun mutedAmong(
        type: String,
        recipientIds: Set<String>,
    ): Set<String> {
        val project = project()
        return recipientIds.filterTo(linkedSetOf()) { MuteKey(project, it.trim(), type) in mutes }
    }

    override fun setMuted(
        recipientId: String,
        type: String,
        muted: Boolean,
    ) {
        val key = MuteKey(project(), recipientId.trim(), type)
        if (muted) mutes += key else mutes -= key
    }

    private data class MuteKey(
        val project: String?,
        val recipientId: String,
        val type: String,
    )

    private fun project(): String? = ProjectContext.currentOrNull()?.id
}

/**
 * 끌 수 있는 알림 종류는 **제품이 정한다** - 기본은 빈 목록(끌 수 있는 것 없음)이라 이 기능은 꺼진 것과 같다.
 * 목록이 비면 브로커는 끄기를 조회조차 하지 않고, 목록에서 빠진 종류의 끄기 줄은 무시된다(보안 · 결제 알림을 앱이 끌 수 없게 둔다).
 * ```yaml
 * skeleton:
 *   notification:
 *     preferences:
 *       types: [NOTE_UPDATED, comment.created]   # 사용자가 끌 수 있는 type 목록 (설정 화면에 이 순서로 나온다)
 * ```
 */
@ConfigurationProperties("skeleton.notification.preferences")
data class NotificationPreferencesProperties(
    val types: List<String> = emptyList(),
) {
    /** 앞뒤 공백을 떼고 빈 글자와 중복을 뺀, 설정 순서 그대로의 목록 */
    val mutableTypes: List<String> = types.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
