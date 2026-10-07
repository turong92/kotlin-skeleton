package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import org.slf4j.LoggerFactory

/**
 * 넘겨받기 실행기 — 모든 [AuthorAdoptionListener] 의 `pendingAccounts` 를 **계정 id 커서**로 끝까지 훑으며 계정마다 첫 프로필 줄을 보장하고([AuthorProfileService.ensureFirstProfileRow])
 * `adopt(계정, 프로필 id)` 로 옛 행을 옮긴 뒤 첫 프로필의 `adopted_at` 을 채운다 (스펙 §7.4).
 *
 * 계약 ([AuthorAdoptionListener] KDoc): 커서는 한 번 본 계정을 그 실행에서 다시 보지 않고 항상 앞으로 간다 — 던지거나 0 을 돌려주는(못 옮기는) 계정이
 * 뒤 계정을 굶기지 않고, 실행은 반드시 끝난다. 계정별 예외는 **삼키고** 그 계정은 다음 기동 때 다시 한다. 멱등이다 — 인스턴스 여럿이 동시에 돌아도 같다
 * (첫 프로필은 `primary_of` 유니크, `adopt` 는 `author_ref is null` 조건). 커서를 무시하는 구현(앞서 본 계정을 또 돌려줌)은 그 구현만 거기서 멈춘다.
 *
 * 로그: 구현 이름과 개수 · 예외 **종류**만 남긴다. 계정 id · 프로필 id 쌍은 물론 예외 메시지(SQL 에 id 가 실릴 수 있다)와 스택도 평소 로그에 남기지 않는다.
 */
class AuthorAdoption(
    private val listeners: () -> List<AuthorAdoptionListener>,
    private val service: AuthorProfileService,
    private val batchSize: Int = DEFAULT_BATCH,
) {
    init { require(batchSize > 0) { "batchSize must be > 0" } }

    /** 옮긴 행 수의 합 */
    fun run(): Int = listeners().sumOf { listener ->
        val moved = run(listener)
        if (moved > 0) log.info("author adoption: listener '{}' moved {} rows", listener.name, moved)
        moved
    }

    private fun run(listener: AuthorAdoptionListener): Int {
        var moved = 0
        var failed = 0
        var after: String? = null
        while (true) {
            val pending = try {
                listener.pendingAccounts(batchSize, after)
            } catch (e: Exception) {
                log.warn("author adoption: listener '{}' could not list pending accounts ({}); it will be retried at the next start", listener.name, e.javaClass.simpleName)
                break
            }
            if (pending.isEmpty()) break
            val last = pending.max()
            if (after != null && last <= after) {
                log.warn("author adoption: listener '{}' ignored the cursor; stopping it for this start", listener.name)
                break
            }
            for (accountId in pending) {
                val n = try {
                    listener.adopt(accountId, service.ensureFirstProfileRow(accountId).id)
                } catch (e: Exception) {
                    failed++
                    log.warn("author adoption: listener '{}' failed for one account ({}); it will be retried at the next start", listener.name, e.javaClass.simpleName)
                    continue
                }
                if (n > 0) {
                    moved += n
                    // 행은 이미 옮겼다 — 표시만 못 해도 옮긴 것으로 센다(adopted_at 은 GET 때 다시 채워진다)
                    try { service.markAdopted(accountId) } catch (e: Exception) {
                        log.warn("author adoption: listener '{}' could not record adopted_at for one account ({})", listener.name, e.javaClass.simpleName)
                    }
                }
            }
            after = last
        }
        if (failed > 0) log.warn("author adoption: listener '{}' failed for {} accounts; they stay for the next start", listener.name, failed)
        return moved
    }

    companion object {
        const val DEFAULT_BATCH = 100
        private val log = LoggerFactory.getLogger(AuthorAdoption::class.java)
    }
}
