package dev.sumin.skeleton.authorprofile.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.authorprofile.T0
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

/** 웹 시험 공통 도구 — 시험마다 새 계정을 만든다(컨텍스트 하나를 시험끼리 나눠 쓰므로 한도 · 이름이 서로 새지 않게) */
abstract class WebTestSupport {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository

    private val counter = AtomicInteger()

    /** 새 계정(계정 이름 없음)을 저장소에 넣고 그 계정으로 로그인한 호출자를 돌려준다 */
    fun caller(name: String? = null): TestingAuthenticationToken {
        val id = "acc_web_${counter.incrementAndGet()}_${System.nanoTime()}"
        val account = Account(
            id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), name, null, null, T0, T0,
            displayNameKey = name?.lowercase(), displayTag = null,
        )
        check(accounts.insert(account, emptyList()))
        return TestingAuthenticationToken(id, "n/a", "ROLE_USER")
    }

    fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.with(SecurityMockMvcRequestPostProcessors.authentication(who)) else builder)

    fun MockHttpServletRequestBuilder.json(body: String): MockHttpServletRequestBuilder = contentType(MediaType.APPLICATION_JSON).content(body)

    fun text(result: ResultActions): String = result.andReturn().response.contentAsString

    fun idOf(result: MvcResult): String = JsonPath.read(result.response.contentAsString, "$.value.id")
}
