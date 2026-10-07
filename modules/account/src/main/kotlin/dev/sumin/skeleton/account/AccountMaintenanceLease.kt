package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

/**
 * 인스턴스 여럿이 같은 주기 일을 중복으로 하지 않게 하는 **짧은 임대** — [tryAcquire] 가 소유 표(null 이 아닌 값)를 돌려준 쪽만 [name] 의 일을 한다.
 * 임대는 [ttl] 이 지나면 저절로 풀린다 (일하던 인스턴스가 죽어도 영원히 막히지 않는다). `account-jdbc` 가 DB 한 줄로 구현한다.
 */
interface AccountMaintenanceLease {
    /** 가져갔으면 **소유 표**(이 임대에만 쓰이는 무작위 값), 누가 쥐고 있으면 null */
    fun tryAcquire(name: String, ttl: Duration): String?

    /**
     * 일이 끝났으면 ttl 을 기다리지 않고 푼다 — **[owner] 가 지금의 소유 표일 때만**. 임대가 일 도중 만료돼 다른 인스턴스가 가져갔다면
     * 늦게 끝난 옛 보유자의 해제가 새 보유자의 임대를 풀지 못한다 (그러면 일이 겹쳐 돈다)
     */
    fun release(name: String, owner: String)
}

/** 한 프로세스 안에서만 — 단일 인스턴스 · 시험 기본 */
class InMemoryAccountMaintenanceLease(private val time: TimeProvider) : AccountMaintenanceLease {
    private class Held(val until: Instant, val owner: String)

    private val held = HashMap<String, Held>()

    @Synchronized override fun tryAcquire(name: String, ttl: Duration): String? {
        val now = time.now()
        if (held[name]?.until?.isAfter(now) == true) return null
        return java.util.UUID.randomUUID().toString().also { held[name] = Held(now.plus(ttl), it) }
    }

    @Synchronized override fun release(name: String, owner: String) { if (held[name]?.owner == owner) held.remove(name) }
}

object MaintenanceLeases {
    /** 정리(삭제 유예 뒤 지우기 · 만료 청소) 한 번이 도는 동안 */
    const val PURGE_RUN = "account-purge-run"

    /** 주기 틱이 잡을 넣을 권리 — 한 주기에 하나만 */
    const val PURGE_DISPATCH = "account-purge-dispatch"

    /** 한 번 도는 데 걸리는 최대 시간 — 죽은 인스턴스가 이보다 오래 막지 않는다 */
    val RUN_TTL: Duration = Duration.ofMinutes(10)
}
