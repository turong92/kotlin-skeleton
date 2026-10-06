package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/** 주기마다 하는 일 — 기본은 [AccountPurgeService.purgeDue] 를 직접 부르고, `job-queue-jdbc` 가 있으면 잡을 넣는다 (여러 인스턴스 중 잡을 가져간 하나가 한다) */
fun interface AccountPurgeDispatch {
    fun dispatch()
}

/** 삭제 유예가 끝난 계정을 주기적으로 지운다 */
class AccountPurgeScheduler(private val interval: Duration, private val dispatch: AccountPurgeDispatch) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (interval.isZero || interval.isNegative) return
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "account-purge").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try { dispatch.dispatch() } catch (e: Exception) { log.warn("account purge run failed: {}", e.javaClass.simpleName) }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    override fun close() { executor?.shutdownNow() }
}

/** 로컬 · e2e 용 시드 계정을 기동 때 만든다 — 이미 있는 이메일은 건드리지 않는다. 확인된 ACTIVE 계정이고 `bootstrap` 과 무관하다 */
class AccountSeeder(private val core: AccountCore) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) = seed()

    fun seed() {
        core.props.seed.accounts.forEach { seed ->
            val email = Emails.normalize(seed.email)
            if (core.accountByEmail(email) != null) return@forEach
            val now = core.time.now()
            val account = Account(
                id = seed.id?.trim()?.takeIf { it.isNotEmpty() } ?: core.newAccountId(), email = email, emailVerified = true, status = AccountStatus.ACTIVE,
                roles = core.props.defaultRoles + seed.roles, displayName = ProfileRules.displayName(seed.displayName), locale = ProfileRules.locale(seed.locale),
                timeZone = null, createdAt = now, updatedAt = now,
            )
            val identity = Identity(core.newIdentityId(), account.id, SignInMethods.PASSWORD, email, true, secret = core.hasher.hash(seed.password), createdAt = now)
            if (core.accounts.insert(account, listOf(identity))) {
                log.info("seeded local account {} roles={}", account.id, account.roles)
                core.events.publish(AccountEventType.SIGN_UP, account.id, detail = mapOf("method" to SignInMethods.PASSWORD, "seed" to "true"))
            }
        }
    }
}
