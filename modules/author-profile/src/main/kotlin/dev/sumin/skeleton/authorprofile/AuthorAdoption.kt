package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import org.slf4j.LoggerFactory

/**
 * 넘겨받기 실행기 — 모든 [AuthorAdoptionListener] 의 `pendingAccounts` 를 돌며 계정마다 첫 프로필을 보장하고([AuthorProfileService.ensureFirstProfile])
 * `adopt(계정, 프로필 id)` 로 옛 행을 옮긴 뒤 첫 프로필의 `adopted_at` 을 채운다 (스펙 §7.4).
 *
 * 계약 ([AuthorAdoptionListener] KDoc): 한 바퀴에 진행(`adopt` 가 1 이상)이 없으면 그 구현은 거기서 멈춘다 — 던지거나 0 을 돌려주는 계정이 뒤 계정을 굶기지 않게,
 * 그리고 영원히 돌지 않게. 계정별 예외는 **삼키고** 그 계정은 다음 기동 때 다시 한다. 멱등이다 — 인스턴스 여럿이 동시에 돌아도 같다
 * (첫 프로필은 `primary_of` 유니크, `adopt` 는 `author_ref is null` 조건).
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
        var skipped = 0
        while (true) {
            val pending = try {
                listener.pendingAccounts(batchSize)
            } catch (e: Exception) {
                log.warn("author adoption: listener '{}' could not list pending accounts ({}); it will be retried at the next start", listener.name, e.javaClass.simpleName)
                break
            }
            if (pending.isEmpty()) break
            var progress = 0
            for (accountId in pending) {
                try {
                    val first = service.ensureFirstProfile(accountId)
                    val n = listener.adopt(accountId, first.id)
                    if (n > 0) {
                        service.markAdopted(accountId)
                        moved += n
                        progress++
                    }
                } catch (e: Exception) {
                    skipped++
                    log.warn("author adoption: listener '{}' skipped one account ({}); it will be retried at the next start", listener.name, e.javaClass.simpleName)
                }
            }
            if (progress == 0) break   // 진행이 없는 바퀴 — 같은 계정을 계속 보게 되므로 멈춘다
        }
        if (skipped > 0) log.warn("author adoption: listener '{}' left {} accounts for the next start", listener.name, skipped)
        return moved
    }

    companion object {
        const val DEFAULT_BATCH = 100
        private val log = LoggerFactory.getLogger(AuthorAdoption::class.java)
    }
}
