package dev.sumin.skeleton.notification.push

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** 전달 수단 종류 — 값은 문자열이다(새 전달 수단이 같은 자리에 끼운다). 기본 둘: 웹 푸시와 FCM */
object PushKinds {
    const val WEBPUSH = "WEBPUSH"
    const val FCM = "FCM"
}

object PushPlatforms {
    const val WEB = "web"
    const val ANDROID = "android"
    const val IOS = "ios"
    val ALL: Set<String> = setOf(WEB, ANDROID, IOS)
}

enum class PushUrgency { NORMAL, HIGH }

/**
 * 기기 줄 하나(= 설치 하나). [address] 는 웹 푸시 endpoint 또는 FCM 토큰이고 [p256dh] · [authSecret] 은 웹 푸시의 구독 열쇠다 — **모두 비밀 취급**: [toString] 에 싣지 않는다.
 * [keyId] 는 구독할 때의 서버 공개 키 지문(없으면 null) — 지금 키와 다르면 그 줄에는 보내지 않는다.
 */
data class PushDevice(
    val id: String,
    val accountId: String,
    val installationId: String,
    val sessionId: String?,
    val kind: String,
    val platform: String,
    val address: String,
    val p256dh: String?,
    val authSecret: String?,
    val keyId: String?,
    val label: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val lastSuccessAt: Instant?,
) {
    override fun toString(): String = "PushDevice(id=$id, kind=$kind, platform=$platform)"
}

/** 등록 요청에서 종류와 상관없이 같은 모양으로 읽은 값 — 종류별 검사는 [PushSender.validate] */
data class PushRegistration(
    val kind: String,
    val platform: String,
    val address: String,
    val p256dh: String? = null,
    val authSecret: String? = null,
    val label: String? = null,
) {
    override fun toString(): String = "PushRegistration(kind=$kind, platform=$platform)"
}

/** [PushDeviceStore.register] 의 입력 — 저장소가 한 트랜잭션으로 「같은 주소의 옛 줄 지우기 → 넣기·갱신 → 상한 넘는 오래된 줄 지우기」를 한다 */
data class PushDeviceRegistration(
    val accountId: String,
    val installationId: String,
    val sessionId: String?,
    val registration: PushRegistration,
    val keyId: String?,
    val now: Instant,
    val maxDevicesPerAccount: Int,
)

/**
 * 푸시 하나의 내용. [expiresAt] 은 채널이 `createdAt + ttl` 로 채운다(앱의 [PushMessageFactory] 는 비워 둔다) — 보내는 쪽은 항상 채워진 것을 받는다.
 * [tag] 는 묶기 열쇠(같은 열쇠의 안 받은 푸시를 대신한다) — 웹 푸시 `Topic` · FCM `collapse_key` 로 쓴다.
 */
data class PushMessage(
    val eventId: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val link: String? = null,
    val tag: String? = null,
    val urgency: PushUrgency = PushUrgency.NORMAL,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    /** 클릭 보고 토큰 - 보내는 순간 처리기가 기기마다 채운다(큐에 저장되지 않는다). 전달 수단이 페이로드 `open` 으로 싣는다. 보고를 끈 서버는 null */
    val openToken: String? = null,
)

/** 작업 `notification.push` 의 내용 — 기기 하나에 푸시 하나. 받는 사람 id 를 같이 실어 기기 주인이 바뀐 뒤의 작업을 걸러 낸다 */
data class PushJob(
    val deviceId: String,
    val recipientId: String,
    val message: PushMessage,
    /** 광고성 종류의 푸시인가 — 광고 규칙(동의 재확인)을 얹는 모듈이 읽는다. 코어만으로는 늘 false */
    val advertising: Boolean = false,
)

/** [PushSender.send] 의 판정 — 재시도·정리 규칙을 한 곳(처리기)에 둔다 */
sealed interface PushResult {
    /** 푸시 서비스가 받았다 */
    data object Delivered : PushResult

    /** 이 주소는 더 쓸 수 없다(구독 해지·토큰 폐기) — 기기 줄을 지운다 */
    data object Gone : PushResult

    /** 잠시 후 다시 — 큐의 백오프로 재시도한다 */
    data class Retry(val after: java.time.Duration? = null) : PushResult

    /** 다시 해도 안 된다(우리 설정 실수 포함) — 줄은 남기고 작업은 죽은 작업이 된다. [reason] 에 비밀을 싣지 않는다 */
    data class Failed(val reason: String) : PushResult
}

internal object PushHashes {
    fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** 서버 공개 키 → 기기 줄에 적는 지문(앞 16자) */
    fun keyId(publicKey: String): String = sha256Hex(publicKey).take(16)
}
