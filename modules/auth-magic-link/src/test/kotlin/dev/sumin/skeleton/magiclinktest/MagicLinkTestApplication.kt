package dev.sumin.skeleton.magiclinktest

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.notifyAlreadyRegistered
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class MagicLinkTestApplication

class MutableTime(@Volatile var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}

class Mails : AccountMailer {
    val sent = CopyOnWriteArrayList<AccountMail>()
    override fun send(mail: AccountMail) { sent += mail }
    fun tokenOf(mail: AccountMail) = mail.link!!.substringAfter("token=")
}

@TestConfiguration(proxyBeanMethods = false)
class MagicLinkTestBeans {
    @Bean fun mails(): Mails = Mails()
    @Bean fun time(): MutableTime = MutableTime()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
    @Bean fun testAccounts(core: dev.sumin.skeleton.account.AccountCore, accounts: dev.sumin.skeleton.account.AccountRepository, time: MutableTime): TestAccounts = TestAccounts(core, accounts, time)
}

/**
 * 비밀번호 없이 계정 하나 만들기 — 가입(`account-password`)을 거치지 않고 확인된 ACTIVE 계정과 `magic_link` 수단을 저장소에 직접 넣는다.
 * [signUpAttemptOn] 은 같은 주소로 누가 가입을 시도했을 때 `account-password` 의 가입이 부르는 그 함수(`notifyAlreadyRegistered`)를 그대로 부른다.
 */
class TestAccounts(private val core: dev.sumin.skeleton.account.AccountCore, private val accounts: dev.sumin.skeleton.account.AccountRepository, private val time: TimeProvider) {
    fun create(email: String): dev.sumin.skeleton.account.Account {
        val now = time.now()
        val id = core.newAccountId()
        val account = dev.sumin.skeleton.account.Account(id, email, true, dev.sumin.skeleton.account.AccountStatus.ACTIVE, core.props.defaultRoles, null, null, null, now, now)
        check(accounts.insert(account, listOf(dev.sumin.skeleton.account.Identity(core.newIdentityId(), id, dev.sumin.skeleton.account.SignInMethods.MAGIC_LINK, email, true, createdAt = now))))
        return accounts.findById(id)!!
    }

    fun signUpAttemptOn(email: String, credentialLinks: Boolean = true) =
        core.notifyAlreadyRegistered(accounts.findByEmail(email)!!, allowed = true, credentialLinks = credentialLinks)
}
