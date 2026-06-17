package dev.sumin.skeleton.auth

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    AuthAsyncDispatchIntegrationTest.AsyncDispatchProbeConfiguration::class,
)
class AuthAsyncDispatchIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `authenticated async dispatch is not re-authorized as a new business request`() {
        val mvcResult = mockMvc.get("/api/v1/test/async-dispatch") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.TEXT_PLAIN
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return JsonPath.read(response, "$.value.accessToken")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AsyncDispatchProbeConfiguration {
        @Bean
        fun asyncDispatchProbeController(): AsyncDispatchProbeController =
            AsyncDispatchProbeController()
    }

    @RestController
    class AsyncDispatchProbeController {
        @GetMapping("/api/v1/test/async-dispatch", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun dispatch(): DeferredResult<String> {
            val result = DeferredResult<String>()
            CompletableFuture.runAsync {
                result.setResult("ok")
            }
            return result
        }
    }
}
