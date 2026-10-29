package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/** 정의 만들기 · 고치기의 입력. 고칠 때 [code] 는 읽지 않는다 */
data class BadgeDefinitionInput(
    val code: String?,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
    val hidden: Boolean,
    val sortOrder: Int,
)

data class BadgeListItem(val badge: BadgeDefinition, val holders: Int)

/** 배지 정의 관리 — 운영자 화면과 보상 지급기(`BadgeRewardIssuer`)가 쓴다. 그림은 주소 문자열 하나뿐이다(올리기는 없다) */
class BadgeDefinitions(
    private val store: BadgeStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) {
    fun create(input: BadgeDefinitionInput): BadgeDefinition {
        val code = input.code?.trim().orEmpty()
        if (!CODE.matches(code)) throw invalid("code must match [a-z0-9._-]{1,64}")
        val now = time.now()
        val definition = BadgeDefinition(
            id = ids.newId("bdg_"),
            code = code,
            name = name(input),
            description = description(input),
            imageUrl = imageUrl(input.imageUrl),
            hidden = input.hidden,
            sortOrder = input.sortOrder,
            status = BadgeStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        if (!store.insertDefinition(definition)) throw BadgeException(BadgeErrorCode.CODE_TAKEN, "badge code '$code' is already taken")
        return definition
    }

    /** 코드 · 상태를 뺀 나머지를 바꾼다 */
    fun update(id: String, input: BadgeDefinitionInput): BadgeDefinition {
        val current = store.findDefinition(id) ?: throw notFound(id)
        val next = current.copy(
            name = name(input),
            description = description(input),
            imageUrl = imageUrl(input.imageUrl),
            hidden = input.hidden,
            sortOrder = input.sortOrder,
            updatedAt = time.now(),
        )
        if (!store.updateDefinition(next)) throw notFound(id)
        return next
    }

    fun setStatus(id: String, status: BadgeStatus): BadgeDefinition {
        if (!store.updateDefinitionStatus(id, status, time.now())) throw notFound(id)
        return store.findDefinition(id) ?: throw notFound(id)
    }

    fun find(id: String): BadgeDefinition? = store.findDefinition(id)

    fun findByCode(code: String): BadgeDefinition? = store.findDefinitionByCode(code)

    /** `sort_order` 순, 보유자 수와 함께 */
    fun list(): List<BadgeListItem> {
        val counts = store.holderCounts()
        return store.listDefinitions().map { BadgeListItem(it, counts[it.id] ?: 0) }
    }

    private fun name(input: BadgeDefinitionInput) = input.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: throw invalid("name is required (1-100 characters)")

    private fun description(input: BadgeDefinitionInput) = (input.description ?: "").trim().also { if (it.length > 500) throw invalid("description is at most 500 characters") }

    /** 그림 주소 — 비면 없음. `http(s)://` 또는 앱 안 경로(`/…`, `//` 아님)만 받는다 */
    private fun imageUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > 500 || !IMAGE.matches(value)) throw invalid("imageUrl must be an http(s) address or an app path of at most 500 characters")
        return value
    }

    private fun invalid(message: String) = BadgeException(BadgeErrorCode.CONFIG_INVALID, message)

    private fun notFound(id: String) = BadgeException(BadgeErrorCode.NOT_FOUND, "badge $id not found")

    companion object {
        val CODE = Regex("[a-z0-9._-]{1,64}")
        private val IMAGE = Regex("^(https?://|/(?!/))\\S+$")
    }
}
