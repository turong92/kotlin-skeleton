package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Clock

/** 쓰기 요청(글 · 댓글 · 반응)을 계정별로 막는 지점. false 면 BOARD.RATE_LIMITED. 앱이 같은 타입의 빈을 만들면 기본 구현이 물러난다. */
fun interface BoardRateLimiter {
    fun tryAcquire(accountId: String, action: String): Boolean
}

object NoopBoardRateLimiter : BoardRateLimiter {
    override fun tryAcquire(accountId: String, action: String): Boolean = true
}

/** platform 의 [RateLimitStore] 위에 계정 · 동작별 고정 창 한도를 건다 — redis-rate-limit 이 저장소 빈을 내면 그 저장소를 쓴다. */
class StoreBoardRateLimiter(
    private val store: () -> RateLimitStore,
    private val properties: BoardProperties.RateLimit,
    private val clock: Clock = Clock.systemUTC(),
) : BoardRateLimiter {
    override fun tryAcquire(accountId: String, action: String): Boolean =
        store().consume(
            key = "board:$action:$accountId",
            capacity = properties.capacity,
            windowMillis = properties.window.toMillis(),
            now = clock.instant(),
        ).allowed
}
