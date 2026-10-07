package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 기본값(`admin.enabled=false`)이면 관리자 경로가 없다 */
@SpringBootTest(classes = [AuthorProfileWebTestApplication::class])
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class AuthorProfileAdminDisabledTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `admin enabled defaults to false - no controller and no path`() {
        assertTrue(context.getBeanNamesForType(AuthorProfileAdminController::class.java).isEmpty())
        val admin = TestingAuthenticationToken("acc_x", "n/a", "ROLE_ADMIN")
        mvc.perform(get("/api/v1/admin/author-profiles/prf_x").with(authentication(admin))).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/author-profiles").param("accountId", "acc_x").with(authentication(admin))).andExpect(status().isNotFound)
    }
}
