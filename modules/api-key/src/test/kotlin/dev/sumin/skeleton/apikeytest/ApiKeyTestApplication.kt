package dev.sumin.skeleton.apikeytest

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.apikey.FakeApiKeyStore
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.apikey.ApiKeyScope
import dev.sumin.skeleton.common.apikey.ApiKeyScopeDeclaration
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 모듈 패키지 밖에 둔다 - 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class ApiKeyTestApplication

class Mails : AccountMailer {
    val sent = CopyOnWriteArrayList<AccountMail>()
    override fun send(mail: AccountMail) { sent += mail }
    fun lastCode(): String = sent.last { it.kind == dev.sumin.skeleton.account.mail.MailKind.VERIFY_CODE }.vars.getValue("code")
}

class Events : AccountEventListener {
    val all = CopyOnWriteArrayList<AccountEvent>()
    override fun on(event: AccountEvent) { all += event }
}

data class Who(val name: String, val roles: List<String>)

/** 앱의 컨트롤러 흉내 - 읽기 · 쓰기에만 표시가 있고, 표시 없는 경로와 운영 경로는 키로 닿지 않아야 한다 */
@RestController
@RequestMapping("/api/v1/probe")
class ProbeController {
    @GetMapping("/read") @ApiKeyScope("notes.read")
    fun read(authentication: Authentication): DataResponse<Who> = Response.ok(Who(authentication.name, authentication.authorities.mapNotNull { it.authority }))

    @PostMapping("/write") @ApiKeyScope("notes.write")
    fun write(authentication: Authentication): DataResponse<Who> = Response.ok(Who(authentication.name, emptyList()))

    @GetMapping("/unmarked")
    fun unmarked(authentication: Authentication): DataResponse<Who> = Response.ok(Who(authentication.name, emptyList()))
}

@TestConfiguration(proxyBeanMethods = false)
class ApiKeyTestBeans {
    @Bean fun fakeApiKeyStore(): FakeApiKeyStore = FakeApiKeyStore()
    @Bean fun mails(): Mails = Mails()
    @Bean fun events(): Events = Events()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
    @Bean fun probeController(): ProbeController = ProbeController()
    @Bean fun notesScopes(): ApiKeyScopeDeclaration = ApiKeyScopeDeclaration("notes", listOf("notes.read", "notes.write"))
    @Bean fun billingScopes(): ApiKeyScopeDeclaration = ApiKeyScopeDeclaration("billing", listOf("billing.read"))
}
