package dev.sumin.skeleton.app.workbench

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Boots the modules with a root configuration that scans nothing. The workbench application already lives in
 * `dev.sumin.skeleton.app.workbench`, so its scan never reaches module packages; this test keeps the stronger claim
 * honest: every module registers its controllers, filters and advice through AutoConfiguration, so an app in any
 * package (a monorepo app in `dev.sumin.app1`, a renamed project) needs no scan of module code.
 */
@SpringBootTest(classes = [ModuleSelfRegistrationIntegrationTest.NoScanApplication::class])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ModuleSelfRegistrationIntegrationTest {

    // @TestConfiguration is excluded from the app component scan, so other @SpringBootTest classes keep finding only WorkbenchApplication
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class NoScanApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `auth controller, trace filter and exception advice are registered without scanning any module package`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"wrong"}"""
        }.andExpect {
            status { isUnauthorized() }              // AuthController answered (404 if it were not registered)
            jsonPath("$.status") { value(401) }      // GlobalExceptionHandler shaped the ApiError
            jsonPath("$.code") { isNotEmpty() }
            jsonPath("$.traceId") { isNotEmpty() }   // TraceIdFilter populated the trace context
        }
    }
}
