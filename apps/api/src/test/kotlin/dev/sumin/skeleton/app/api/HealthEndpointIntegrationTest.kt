package dev.sumin.skeleton.app.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 컨테이너 헬스체크용 정직한 신호: `/health` 는 인증 없이 열려 있고(401 이 아니다), 앱과 DB 가 떠 있을 때만 200 `{"status":"UP"}` 이다
 * (DB 가 죽으면 액추에이터가 503 — `scripts/test-deploy-contract.sh` 가 DB 컨테이너를 멈춰 확인한다). 세부 정보는 싣지 않고, 나머지 액추에이터는 열지 않는다.
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HealthEndpointIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var webEndpoints: org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier

    @Test
    fun `health is public, answers UP only when everything the app uses is up, and carries no details`() {
        // 스타터는 앱 + DB 뿐이라 200 이다. 찍은 프로젝트가 Redis 같은 것을 더했는데 시험 환경에 없으면 정직하게 503 이다 — 어느 쪽이든 401 이 아니고 세부 정보는 없다
        val result = mvc.get("/health").andReturn().response
        val up = result.status == 200
        kotlin.test.assertTrue(up || result.status == 503, "health must be 200 or 503, never 401: ${result.status}")
        kotlin.test.assertTrue(result.contentAsString.contains(if (up) "\"status\":\"UP\"" else "\"status\":\"DOWN\""), result.contentAsString)
        kotlin.test.assertFalse(result.contentAsString.contains("components") || result.contentAsString.contains("details"), result.contentAsString)
    }

    @Test
    fun `the rest of the actuator is not exposed - not merely guarded - so even a signed-in caller gets 404, and the exposed list is exactly health and info`() {
        // 익명의 4xx 는 노출 여부를 말해 주지 않는다 (노출됐어도 인증 체인이 401 을 낸다) — 로그인한 호출자로 물어 "핸들러가 없다(404)" 를 본다
        val token = com.jayway.jsonpath.JsonPath.read<String>(
            mvc.post("/api/v1/auth/login") { contentType = org.springframework.http.MediaType.APPLICATION_JSON; content = """{"email":"user@example.com","password":"password"}""" }
                .andReturn().response.contentAsString,
            "$.value.accessToken",
        )
        listOf("/env", "/beans", "/metrics", "/loggers", "/mappings", "/heapdump", "/threaddump", "/configprops", "/actuator/env", "/actuator/health").forEach { path ->
            mvc.get(path) { header("Authorization", "Bearer $token") }.andExpect { status { isNotFound() } }
        }
        mvc.get("/info") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        val exposed = webEndpoints.endpoints.map { it.endpointId.toLowerCaseString() }.toSet()
        kotlin.test.assertEquals(setOf("health", "info"), exposed, "the web-exposed actuator endpoints")
    }

    @Test
    fun `an anonymous probe gets nothing but health and an empty info`() {
        mvc.get("/env").andExpect { status { is4xxClientError() } }
        val info = mvc.get("/info").andReturn().response
        kotlin.test.assertEquals(200, info.status)
        kotlin.test.assertEquals("{}", info.contentAsString, "info is public and carries nothing today - a build/git/env contributor would show up here")
    }
}

/** 의존(DB 등)이 죽으면 `/health` 는 503 `{"status":"DOWN"}` 이고 세부 정보(지표의 상세 · 예외 메시지)를 싣지 않는다 — 배포 스크립트가 아니라 시험이 증명한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, DownHealthIndicatorConfig::class)
class HealthDownIntegrationTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `a failing dependency makes health 503 DOWN without details`() {
        val result = mvc.get("/health").andReturn().response
        kotlin.test.assertEquals(503, result.status)
        kotlin.test.assertTrue(result.contentAsString.contains("\"status\":\"DOWN\""), result.contentAsString)
        kotlin.test.assertFalse(result.contentAsString.contains("database-password-hunter2") || result.contentAsString.contains("details") || result.contentAsString.contains("components"), result.contentAsString)
    }
}

@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
class DownHealthIndicatorConfig {
    @org.springframework.context.annotation.Bean
    fun brokenDependency(): org.springframework.boot.health.contributor.HealthIndicator =
        org.springframework.boot.health.contributor.HealthIndicator { org.springframework.boot.health.contributor.Health.down().withDetail("secret", "database-password-hunter2").build() }
}
