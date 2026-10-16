package dev.sumin.skeleton.captcha.turnstile

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

/** 프로젝트 모드: 비밀 키와 기대 hostname 이 프로젝트마다다 — A 의 위젯 토큰을 B 의 키로 검증하지 않는다 */
class TurnstileProjectsTest {
    private class FakeHttp : ExternalHttpClient {
        val forms = mutableListOf<Map<String, String>>()
        var hostname = "a.test"

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> postForm(clientName: String, path: String, form: Map<String, String>, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> {
            forms += form
            return Mono.fromCallable { TurnstileSiteverifyResponse(success = true, hostname = hostname) as T }
        }
        override fun <T : Any> get(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> post(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> put(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> patch(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> delete(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
    }

    private val http = FakeHttp()
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, TurnstileAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { http })
        .withPropertyValues(
            "skeleton.captcha-turnstile.enabled=true",
            "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa",
            "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb",
        )

    @Test
    fun `each project verifies with its own secret key and expected hostname`() {
        runner.withPropertyValues(
            "skeleton.projects.a.config.captcha-turnstile.secret-key=secret-a", "skeleton.projects.a.config.captcha-turnstile.expected-hostname=a.test",
            "skeleton.projects.b.config.captcha-turnstile.secret-key=secret-b", "skeleton.projects.b.config.captcha-turnstile.expected-hostname=b.test",
        ).run { ctx ->
            val verifier = ctx.getBean(TurnstileVerifier::class.java)
            val (a, b) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(ProjectContext.runIn(a) { verifier.verify("tok") }.success)
            assertEquals("secret-a", http.forms.last()["secret"])
            // 같은 토큰이 B 로 오면 B 의 비밀 키로 B 의 hostname 을 기대한다 — 응답이 a.test 면 실패
            val inB = ProjectContext.runIn(b) { verifier.verify("tok") }
            assertEquals("secret-b", http.forms.last()["secret"])
            assertEquals(listOf("hostname-mismatch"), inB.errorCodes)
            assertFailsWith<NoProjectContextException> { verifier.verify("tok") }
        }
    }

    @Test
    fun `a project without a secret key stops the start and the message names it`() {
        runner.withPropertyValues("skeleton.projects.a.config.captcha-turnstile.secret-key=secret-a").run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "project 'b'" in it && "secret-key" in it }, messages.toString())
        }
    }
}
