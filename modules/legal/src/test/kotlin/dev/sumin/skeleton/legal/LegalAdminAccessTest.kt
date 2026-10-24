package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.legaltest.LegalWebFakes
import dev.sumin.skeleton.legaltest.LegalWebTestApplication
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 계정마다 지금 가진 권한을 바꿀 수 있는 대역 — 토큰은 그대로 두고 회수를 흉내 낸다 */
class MutableOperatorAccess : OperatorAccess {
    val grants = ConcurrentHashMap<String, Set<String>>()
    override fun permissionsOf(ref: OperatorRef): Set<String> = grants[ref.accountId] ?: emptySet()
}

@TestConfiguration(proxyBeanMethods = false)
class MutableAccessBeans {
    @Bean fun mutableOperatorAccess() = MutableOperatorAccess()
}

@SpringBootTest(
    classes = [LegalWebTestApplication::class],
    properties = ["skeleton.legal.location=classpath:legal-web/", "skeleton.legal.facts.company-name=ACME", "skeleton.legal.facts.contact-email=a@b.c"],
)
@AutoConfigureMockMvc
@Import(LegalWebFakes::class, MutableAccessBeans::class)
class LegalAdminAccessTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: MutableOperatorAccess

    // 토큰의 역할은 모두 USER 뿐이다 - 판정은 OperatorAccess 가 한다
    private val staff = TestingAuthenticationToken("acc_staff", "n/a", "ROLE_USER")

    @Test
    fun `legal read is allowed by the permission alone, and refused right after it is revoked while the same token is still valid`() {
        access.grants["acc_staff"] = setOf("legal.read")
        mvc.perform(get("/api/v1/legal/admin/consents").principal(staff)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/legal/admin/documents").principal(staff)).andExpect(status().isOk)

        access.grants.remove("acc_staff")
        mvc.perform(get("/api/v1/legal/admin/consents").principal(staff)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        mvc.perform(get("/api/v1/legal/admin/documents").principal(staff)).andExpect(status().isForbidden)
    }

    @Test
    fun `another module's permission does not open the legal admin paths`() {
        access.grants["acc_staff"] = setOf("payment.read", "board.moderate")
        mvc.perform(get("/api/v1/legal/admin/consents").principal(staff)).andExpect(status().isForbidden)
    }

    @Test
    fun `anonymous is 401`() {
        mvc.perform(get("/api/v1/legal/admin/consents")).andExpect(status().isUnauthorized)
    }
}
