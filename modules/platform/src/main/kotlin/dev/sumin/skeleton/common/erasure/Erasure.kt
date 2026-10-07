package dev.sumin.skeleton.common.erasure

import java.security.MessageDigest
import java.util.HexFormat

/** 계정을 지울 때 다른 모듈이 자기 데이터에서 그 계정을 지우거나 익명화하도록 건네는 요청 */
data class ErasureRequest(
    val accountId: String,
    /** 이 계정 자리에 남길 값 — 글 작성자 같은 칸을 이 값으로 바꾼다 ([AccountTombstone]). 계정 id 를 담지 않으므로 되돌릴 수 없다 */
    val tombstone: String,
    /**
     * true: 계정 행은 남는다(`deletion.mode=ANONYMIZE`) — 이 계정 id 로 이어진 줄을 지우지 않고 개인정보만 지워도 참조가 유지된다.
     * false: 행이 지워진다 — 계정 id 를 들고 있는 줄은 이 요청에서 지우거나 [tombstone] 으로 바꿔야 한다
     */
    val accountKept: Boolean = false,
)

/**
 * 계정이 완전히 지워질 때(삭제 유예가 끝난 뒤) 불리는 고리 — 빈으로 등록하면 `account` 의 `AccountPurgeService` 가 모아 부른다.
 * board(작성자를 "삭제된 사용자" 로) · notification-jdbc(받은편지함 삭제) 처럼 계정 id 를 들고 있는 모듈이 구현한다.
 * **멱등**이어야 한다 — 하나라도 던지면 그 계정은 지워지지 않고 다음 주기에 모든 고리가 다시 불린다.
 */
interface AccountErasureListener {
    /** 로그 · 실패 메시지에 쓰는 이름 (모듈 이름 권장) */
    val name: String

    fun erase(request: ErasureRequest)
}

/**
 * 데이터 내보내기 고리 — **인터페이스만** 있다 (연결된 엔드포인트도, 기본 구현도 없다). 앱이 법적 요구에 맞춰 구현하고 자기 엔드포인트에서 모은다.
 * 모듈이 자기 데이터를 [export] 로 내놓는 규약이다 (계정 모듈이 주는 계정 · 로그인 수단 정보는 `ProfileService.me`).
 */
interface AccountDataExporter {
    /** 내보내기 파일 안의 구역 이름 */
    val section: String

    fun export(accountId: String): Map<String, Any?>
}

/** 지워진 계정을 대신하는 값 — 계정마다 하나, 계정 id 에서 되돌릴 수 없다 (SHA-256 앞자리) */
object AccountTombstone {
    const val PREFIX = "deleted:"

    fun of(accountId: String): String =
        PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("tombstone/$accountId".toByteArray(Charsets.UTF_8))).take(16)

    fun isTombstone(value: String?): Boolean = value != null && value.startsWith(PREFIX)
}
