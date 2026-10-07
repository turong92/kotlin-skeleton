package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.authorprofile.AuthorProfileService
import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [AuthorProfileWebTestApplication::class], properties = ["skeleton.author-profile.http.enabled=false"])
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class AuthorProfileHttpDisabledTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `http enabled false leaves the service but no path`() {
        assertTrue(context.getBeansOfType(AuthorProfileService::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(AuthorProfileController::class.java).isEmpty())
        mvc.perform(get("/api/v1/author-profiles/config").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(org.springframework.security.authentication.TestingAuthenticationToken("acc_x", "n/a", "ROLE_USER"))))
            .andExpect(status().isNotFound)
    }
}
