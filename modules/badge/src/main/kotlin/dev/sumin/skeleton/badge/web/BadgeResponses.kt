package dev.sumin.skeleton.badge.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.badge.AwardHistoryRow
import dev.sumin.skeleton.badge.BadgeAction
import dev.sumin.skeleton.badge.BadgeDefinition
import dev.sumin.skeleton.badge.BadgeListItem
import dev.sumin.skeleton.badge.BadgeSource
import dev.sumin.skeleton.badge.BadgeStatus
import dev.sumin.skeleton.badge.EarnedBadge
import dev.sumin.skeleton.badge.MyBadges
import dev.sumin.skeleton.badge.PublicBadge
import java.time.Instant

/**
 * 로그인 없이 읽는 프로필 배지 한 개 — **이 넷뿐이다**(보유 줄 id · 정의 id · 받은 시각 · 출처 · 회수 상태 · 개수를 담지 않는다). 그림이 없어도 `imageUrl` 키는 나간다.
 * 이 타입에 필드를 더하기 전에 모듈 문서의 「공개 프로필」 절을 읽는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PublicBadgeResponse(val code: String, val name: String, val description: String, val imageUrl: String?)

data class PublicBadgesResponse(val badges: List<PublicBadgeResponse>)

fun PublicBadge.toResponse() = PublicBadgeResponse(code, name, description, imageUrl)

/** 내 배지의 받은 것 한 개 */
data class EarnedBadgeResponse(
    val awardId: String,
    val code: String,
    val name: String,
    val description: String,
    val imageUrl: String?,
    val hidden: Boolean,
    val archived: Boolean,
    val visible: Boolean,
    val awardedAt: Instant,
)

/** 내 배지의 아직인 것 한 개 - 숨은 정의는 [hidden]=true 만 나가고 코드 · 이름 · 설명 · 그림은 null 이다 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class LockedBadgeResponse(
    val hidden: Boolean,
    val code: String?,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
)

data class MyBadgesResponse(val earned: List<EarnedBadgeResponse>, val locked: List<LockedBadgeResponse>)

fun EarnedBadge.toResponse() = EarnedBadgeResponse(
    award.id, definition.code, definition.name, definition.description, definition.imageUrl, definition.hidden, definition.status == BadgeStatus.ARCHIVED, award.visible, award.awardedAt,
)

fun BadgeDefinition.toLockedResponse() =
    if (hidden) LockedBadgeResponse(true, null, null, null, null) else LockedBadgeResponse(false, code, name, description, imageUrl)

fun MyBadges.toResponse() = MyBadgesResponse(earned.map { it.toResponse() }, locked.map { it.toLockedResponse() })

data class VisibleRequest(val visible: Boolean? = null)

data class VisibleResponse(val awardId: String, val visible: Boolean)

data class BadgeDefinitionResponse(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val imageUrl: String?,
    val hidden: Boolean,
    val sortOrder: Int,
    val status: BadgeStatus,
    val holders: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun BadgeDefinition.toResponse(holders: Int = 0) = BadgeDefinitionResponse(id, code, name, description, imageUrl, hidden, sortOrder, status, holders, createdAt, updatedAt)

fun BadgeListItem.toResponse() = badge.toResponse(holders)

data class BadgeDefinitionRequest(
    val code: String? = null,
    val name: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val hidden: Boolean? = null,
    val sortOrder: Int? = null,
)

data class StatusRequest(val status: String? = null)

data class AwardRequest(val accountIds: List<String>? = null, val reason: String? = null)

data class AwardResultResponse(val awarded: List<String>, val alreadyHeld: List<String>)

data class RevokeRequest(val reason: String? = null)

data class AwardEventResponse(
    val id: String,
    val awardId: String,
    val badgeId: String,
    val badgeCode: String?,
    val badgeName: String?,
    val accountId: String,
    /** 사람이 알아볼 계정 이름(이메일, 없으면 아이디). 이름을 낼 수 없으면 null - 화면은 [accountId] 를 보인다 */
    val accountLabel: String?,
    val action: BadgeAction,
    val source: BadgeSource,
    val sourceRef: String?,
    val actorId: String?,
    val reason: String?,
    val createdAt: Instant,
)

fun AwardHistoryRow.toResponse(accountLabel: String?) = AwardEventResponse(
    event.id, event.awardId, event.badgeId, badge?.code, badge?.name, event.accountId, accountLabel, event.action, event.source, event.sourceRef, event.actorId, event.reason, event.createdAt,
)
