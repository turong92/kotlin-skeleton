package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.FieldValidationException
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
        core.props.seed.accounts.forEachIndexed { index, seed ->
            val email = Emails.normalize(seed.email)
            if (core.accountByEmail(email) != null) return@forEachIndexed
            val now = core.time.now()
            val base = Account(
                id = seed.id?.trim()?.takeIf { it.isNotEmpty() } ?: core.newAccountId(), email = email, emailVerified = true, status = AccountStatus.ACTIVE,
                roles = core.props.defaultRoles + seed.roles, displayName = null, locale = ProfileRules.locale(seed.locale),
                timeZone = null, createdAt = now, updatedAt = now,
            )
            val identity = Identity(core.newIdentityId(), base.id, SignInMethods.PASSWORD, email, true, secret = core.hasher.hash(seed.password), createdAt = now)
            // 시드 이름은 운영자가 정한 값이라 예약어 검사를 하지 않는다 (규칙에 맞게만 고친다). 방식이 TAGGED 면 꼬리표가 붙는다
            // 시드 아이디는 운영자가 정한 값이라 예약어 검사를 하지 않는다 (형식은 본다 — 틀리면 기동이 멈춘다). 꺼짐이면 읽지 않는다
            val loginId = seed.loginId?.takeIf { core.props.loginId.enabled && it.isNotBlank() }?.let {
                try { core.acceptLoginId(it) } catch (e: FieldValidationException) {
                    // 기동이 멈추는 자리 — 어느 설정인지 말한다 (값은 말하지 않는다)
                    throw IllegalStateException("skeleton.account.seed.accounts[$index].login-id is not a valid login ID (${e.fieldCode}: ${e.message})", e)
                }
            }
            val placed = core.insertNamed(base.copy(loginId = loginId), listOf(identity), core.names.sanitize(seed.displayName, exemptReserved = true), strict = false) { core.accounts.insert(it, listOf(identity), core.loginIdHashOf(it.loginId)) }
            val account = placed.account
            if (placed.outcome == AccountCore.NamedInsert.INSERTED) {
                log.info("seeded local account {} roles={}", account.id, account.roles)
                core.events.publish(AccountEventType.SIGN_UP, account.id, detail = mapOf("method" to SignInMethods.PASSWORD, "seed" to "true"))
            } else {
                // 건너뛴 까닭을 남긴다 — 아이디 충돌이 조용히 계정 하나를 없애지 않게. 계정 id 만 찍는다 (아이디 · 주소는 찍지 않는다)
                log.warn("seed account {} was not created: {} (skeleton.account.seed.accounts[{}])", account.id, placed.outcome, index)
            }
        }
    }
}
