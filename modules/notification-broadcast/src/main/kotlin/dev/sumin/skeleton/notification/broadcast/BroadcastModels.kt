package dev.sumin.skeleton.notification.broadcast

import java.time.Instant

/** 공지 상태 — `DRAFT → SENDING|SCHEDULED → SENT`, 취소 · 실패는 중간에서 갈라진다 (스펙 §4) */
enum class BroadcastStatus { DRAFT, SCHEDULED, SENDING, SENT, CANCELLED, FAILED }

data class BroadcastAudience(val kind: String, val param: String? = null)

/** 운영자가 쓰는 내용 — 만들기 · 고치기의 입력 */
data class BroadcastContent(
    val type: String,
    val title: String,
    val body: String?,
    val link: String?,
    /** 알림함 말고 고른 채널(알림함은 늘 간다). 비면 알림함만 */
    val channels: List<String>,
    val audience: BroadcastAudience,
)

/**
 * 공지 한 줄. [id] 는 알림 이벤트 id 로도 쓴다(`bc_…`) — 묶음마다 같은 id 라 알림함 pk 가 재저장을 막고, 전달 고리가 접두사로 자기 것을 안다.
 * [runToken] · [nextSeq] · [cursorId] 는 단계 작업이 이어 달리는 자리다(스펙 §4): 작업은 토큰과 순번이 이 줄과 같을 때만 움직인다.
 */
data class Broadcast(
    val id: String,
    val status: BroadcastStatus,
    val content: BroadcastContent,
    val scheduledAt: Instant?,
    val runToken: String?,
    val nextSeq: Int,
    val cursorId: String?,
    /** 시작 때 센 예상 대상 수 */
    val estimate: Long?,
    /** 지금까지 알림 묶음으로 넘긴 사람 수(광고 동의로 빠진 사람도 처리한 것으로 센다 - 진행률이 예상 수에 닿게) */
    val recipients: Long,
    val createdBy: String,
    val sentBy: String?,
    val cancelledBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sendRequestedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val cancelledAt: Instant?,
    val failureReason: String?,
) {
    val type: String get() = content.type
}

/** 채널 하나의 전달 숫자 — 공지 모듈은 채널을 모르고 전달 고리가 알려 준 만큼만 센다 */
data class ChannelStats(val queued: Long = 0, val delivered: Long = 0, val failed: Long = 0, val dropped: Long = 0, val opened: Long = 0)

enum class StatField { QUEUED, DELIVERED, FAILED, DROPPED, OPENED }

/** 큐에 싣는 단계 작업 하나 = 한 묶음. 토큰과 순번이 공지 줄과 다르면 아무것도 하지 않는다 */
data class BroadcastStep(val id: String, val token: String, val seq: Int)

/** `GET /{id}` 의 재료 — 공지 줄 + 알림함 셈(모르면 null) + 채널별 숫자 */
data class BroadcastDetail(val broadcast: Broadcast, val inbox: dev.sumin.skeleton.notification.InboxEventStats?, val channels: Map<String, ChannelStats>)

data class BroadcastTypeOption(val type: String, val advertising: Boolean, val push: Boolean)

data class BroadcastAudienceOption(val kind: String, val param: dev.sumin.skeleton.common.audience.AudienceParam)

data class BroadcastOptions(val types: List<BroadcastTypeOption>, val channels: List<String>, val audiences: List<BroadcastAudienceOption>)
