package dev.sumin.skeleton.routing.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.RequestRoutingProperties
import dev.sumin.skeleton.routing.RequestRoutingProperties.Inbound
import dev.sumin.skeleton.routing.RouteTargets
import dev.sumin.skeleton.routing.RoutingPermissions
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.json.JsonMapper

/** 진짜 보안 체인 뒤에 선 필터 — 손님의 표시는 말없이 사라지고, 직원의 표시만 허용 목록 검사를 거쳐 MDC 로 간다 */
@SpringJUnitWebConfig(RouteInboundFilterTest.Config::class)
class RouteInboundFilterTest(@Autowired private val context: WebApplicationContext) {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    class Config {
        @Bean
        fun chain(http: HttpSecurity): SecurityFilterChain {
            http.csrf { it.disable() }
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
            return http.build()
        }

        @Bean
        fun probe() = Probe()
    }

    @RestController
    class Probe {
        @GetMapping("/probe")
        fun probe(): String = MDC.get("route") ?: "none"
    }

    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private var openUntil: Instant? = null
    private val window = object : LocalRouteWindow {
        override fun openUntil(): Instant? = openUntil
    }
    private val access = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = if ("ADMIN" in ref.tokenRoles) setOf(RoutingPermissions.USE) else emptySet()
    }
    private val logs = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(RouteInboundFilter::class.java) as Logger

    @BeforeTest
    fun attachLog() {
        logs.start()
        logger.addAppender(logs)
    }

    @AfterTest
    fun detachLog() {
        logger.detachAppender(logs)
        MDC.clear()
    }

    private fun mvc(inbound: Inbound = Inbound.EDGE): MockMvc {
        val properties = RequestRoutingProperties(
            enabled = true,
            inbound = inbound,
            services = mapOf(
                "orders" to RequestRoutingProperties.Service(
                    mapOf(
                        "candidate" to RequestRoutingProperties.Target("https://candidate.a.run.app"),
                        "alice" to RequestRoutingProperties.Target("https://alice.example.com", local = true),
                    ),
                ),
            ),
        )
        val filter = RouteInboundFilter(properties, RouteTargets(properties), window, access, TimeProvider { now }, RoutingErrorWriter(JsonMapper.builder().build()))
        return MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .addFilters<DefaultMockMvcBuilder>(filter)
            .build()
    }

    private fun probe(route: String, inbound: Inbound = Inbound.EDGE, who: String? = null, role: String = "ADMIN"): ResultActions {
        val request = get("/probe").header("baggage", "userId=7,route=$route")
        if (who != null) request.with(user(who).roles(role))
        return mvc(inbound).perform(request)
    }

    @Test
    fun customerRouteIsDroppedSilently() {
        probe("orders:alice").andExpect(status().isOk).andExpect(content().string("none"))
        probe("orders:candidate", who = "bob", role = "USER").andExpect(status().isOk).andExpect(content().string("none"))
        // 손님이 주소 모양을 실어도 400 이 아니라 흔적 없이 버려진다
        probe("orders:https://evil.example.com").andExpect(status().isOk).andExpect(content().string("none"))
        probe("orders:nobody", who = "bob", role = "USER").andExpect(status().isOk).andExpect(content().string("none"))
    }

    @Test
    fun staffRouteIsKept() {
        probe("orders:candidate", who = "alice").andExpect(status().isOk).andExpect(content().string("orders:candidate"))
    }

    @Test
    fun staffUrlLikeTargetIs400() {
        probe("orders:https://evil.example.com", who = "alice")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROUTING.MALFORMED"))
        probe("orders:../x", who = "alice").andExpect(status().isBadRequest)
    }

    @Test
    fun unknownTargetOfKnownServiceIs400() {
        probe("orders:nobody", who = "alice")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROUTING.UNKNOWN_TARGET"))
    }

    @Test
    fun unknownServiceIsPassedOn() {
        probe("payments:candidate", who = "alice").andExpect(status().isOk).andExpect(content().string("payments:candidate"))
    }

    @Test
    fun localClosedIs403() {
        probe("orders:alice", who = "alice").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ROUTING.LOCAL_CLOSED"))
        openUntil = now // 시각이 지난 창도 닫힌 것이다
        probe("orders:alice", who = "alice").andExpect(status().isForbidden)
    }

    @Test
    fun localOpenIsLogged() {
        openUntil = now.plusSeconds(600)

        probe("orders:alice", who = "alice").andExpect(status().isOk).andExpect(content().string("orders:alice"))

        val warn = logs.list.single { it.level == Level.WARN }
        assertTrue("alice" in warn.formattedMessage && "orders:alice" in warn.formattedMessage, warn.formattedMessage)
    }

    @Test
    fun internalTrustsWithoutAuth() {
        probe("orders:candidate", inbound = Inbound.INTERNAL).andExpect(status().isOk).andExpect(content().string("orders:candidate"))
        probe("orders:nobody", inbound = Inbound.INTERNAL).andExpect(status().isBadRequest)
    }

    @Test
    fun mdcClearedAfterRequest() {
        probe("orders:candidate", who = "alice").andExpect(status().isOk)

        assertNull(MDC.get("route"))
        assertEquals(0, logs.list.count { it.level == Level.WARN })
    }
}
