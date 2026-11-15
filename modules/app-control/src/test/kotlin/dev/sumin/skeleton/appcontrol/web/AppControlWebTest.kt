package dev.sumin.skeleton.appcontrol.web

import dev.sumin.skeleton.appcontrol.AppControlStore
import dev.sumin.skeleton.appcontrol.FakeAppControlStore
import dev.sumin.skeleton.appcontrol.MutableTime
import dev.sumin.skeleton.appcontrol.RequireFeature
import dev.sumin.skeleton.appcontroltest.AppControlWebTestApplication
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequireFeature("gated-class")
@RequestMapping("/test/class")
class GatedClassController {
    @GetMapping fun hello() = mapOf("ok" to true)
}

@RestController
@RequestMapping("/test")
class PlainController {
    @GetMapping("/open") fun open() = mapOf("ok" to true)

    @RequireFeature("gated-method") @GetMapping("/method") fun method() = mapOf("ok" to true)

    @RequireFeature("legacy-on", defaultEnabled = true) @GetMapping("/legacy") fun legacy() = mapOf("ok" to true)

    @GetMapping("/healthy-not") fun lookalike() = mapOf("ok" to true)

    @GetMapping("/unscoped") fun unscoped() = mapOf("ok" to true)
}

@TestConfiguration(proxyBeanMethods = false)
class AppControlTestBeans {
    @Bean fun store(): AppControlStore = FakeAppControlStore()
    @Bean fun time(): TimeProvider = MutableTime()
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { ACTIONS += it }
    @Bean fun gatedClass() = GatedClassController()
    @Bean fun plain() = PlainController()
    @Bean fun unscopedPaths() = dev.sumin.skeleton.common.project.ProjectUnscopedPaths { listOf("/test/unscoped") }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val ACTIONS = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 문(어노테이션) · 점검 필터 · 운영자 경로(권한 · 기록) · 클라이언트가 읽는 설정 - 저장소 · 시계만 가짜, 요청은 진짜 MVC 를 지난다 */
@SpringBootTest(
    classes = [AppControlWebTestApplication::class],
    properties = ["skeleton.app-control.enabled=true", "skeleton.app-control.config.enabled=true", "skeleton.app-control.admin.enabled=true", "skeleton.app-control.cache-ttl=PT5S"],
)
@AutoConfigureMockMvc
@Import(AppControlTestBeans::class)
class AppControlWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: AppControlStore
    @Autowired lateinit var time: TimeProvider

    private val admin = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val viewer = TestingAuthenticationToken("acc_view", "n/a", "ROLE_USER")
    private val nobody = TestingAuthenticationToken("acc_none", "n/a", "ROLE_USER")
    private val base = "/api/v1/admin/app-control"

    @BeforeEach
    fun reset() {
        AppControlTestBeans.GRANTS.clear()
        AppControlTestBeans.GRANTS["acc_op"] = setOf("app-control.read", "app-control.write")
        AppControlTestBeans.GRANTS["acc_view"] = setOf("app-control.read")
        AppControlTestBeans.ACTIONS.clear()
        (store as FakeAppControlStore).rows.clear()
        // 캐시를 지난 시각으로 보내 이전 시험의 값이 남지 않게 한다
        (time as MutableTime).instant = (time as MutableTime).instant.plusSeconds(3600)
    }

    private fun putAs(auth: TestingAuthenticationToken?, path: String, body: String) =
        mvc.perform(put("$base$path").apply { auth?.let { principal(it) } }.contentType(MediaType.APPLICATION_JSON).content(body))

    private fun flag(key: String, enabled: Boolean, visible: Boolean = false) =
        putAs(admin, "/flags/$key", """{"enabled":$enabled,"clientVisible":$visible}""").andExpect(status().isOk)

    // ---- 기능 스위치 문 ----

    @Test
    fun `a gated method is a 404 while its flag is off and works once it is on, a gated class the same`() {
        mvc.perform(get("/test/method")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("APP_CONTROL.FEATURE_DISABLED"))
        mvc.perform(get("/test/class")).andExpect(status().isNotFound)
        mvc.perform(get("/test/open")).andExpect(status().isOk)

        flag("gated-method", true)
        flag("gated-class", true)
        mvc.perform(get("/test/method")).andExpect(status().isOk)
        mvc.perform(get("/test/class")).andExpect(status().isOk)

        flag("gated-method", false)
        mvc.perform(get("/test/method")).andExpect(status().isNotFound)
    }

    @Test
    fun `a gate that defaults to on stays open until an operator turns the flag off`() {
        mvc.perform(get("/test/legacy")).andExpect(status().isOk)
        flag("legacy-on", false)
        mvc.perform(get("/test/legacy")).andExpect(status().isNotFound)
    }

    // ---- 점검 모드 ----

    private fun maintenanceOn(body: String = """{"enabled":true,"message":"Moving the database"}""") =
        putAs(admin, "/maintenance", body).andExpect(status().isOk)

    @Test
    fun `maintenance answers 503 with Retry-After and the message, and lifts when switched off`() {
        mvc.perform(get("/test/open")).andExpect(status().isOk)

        maintenanceOn()
        mvc.perform(get("/test/open"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Retry-After", "300"))
            .andExpect(jsonPath("$.code").value("APP_CONTROL.MAINTENANCE"))
            .andExpect(jsonPath("$.detail").value("Moving the database"))
        mvc.perform(get("/test/method")).andExpect(status().isServiceUnavailable)

        putAs(admin, "/maintenance", """{"enabled":false}""").andExpect(status().isOk)
        mvc.perform(get("/test/open")).andExpect(status().isOk)
    }

    @Test
    fun `Retry-After counts down to the expected end when there is one`() {
        val ends = (time as MutableTime).instant.plusSeconds(1800)
        maintenanceOn("""{"enabled":true,"endsAt":"$ends"}""")

        mvc.perform(get("/test/open")).andExpect(status().isServiceUnavailable).andExpect(header().string("Retry-After", "1800"))
    }

    @Test
    fun `health, login, the operator paths and the app config stay open during maintenance, look-alike paths do not`() {
        maintenanceOn()

        mvc.perform(get("/health")).andExpect { assertEquals(false, it.response.status == 503) }
        mvc.perform(get("/api/v1/auth/login")).andExpect { assertEquals(false, it.response.status == 503) }
        mvc.perform(get(base).principal(viewer)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/app/config")).andExpect(status().isOk).andExpect(jsonPath("$.value.maintenance.enabled").value(true))
        mvc.perform(get("/test/healthy-not")).andExpect(status().isServiceUnavailable)
        mvc.perform(get("/%74est/open")).andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `a pass-through path does not even read the state - a health probe has no project in a multi-project server`() {
        val reads = (store as FakeAppControlStore).reads
        (time as MutableTime).instant = time.now().plusSeconds(3600)

        mvc.perform(get("/health")).andReturn()
        mvc.perform(get("/info")).andReturn()

        assertEquals(reads, (store as FakeAppControlStore).reads)
    }

    // ---- 운영자 경로 ----

    @Test
    fun `anonymous is 401, no permission 403, read alone cannot change anything`() {
        mvc.perform(get(base)).andExpect(status().isUnauthorized)
        mvc.perform(get(base).principal(nobody)).andExpect(status().isForbidden)
        mvc.perform(get(base).principal(viewer)).andExpect(status().isOk)

        putAs(null, "/flags/beta", """{"enabled":true}""").andExpect(status().isUnauthorized)
        putAs(viewer, "/flags/beta", """{"enabled":true}""").andExpect(status().isForbidden)
        putAs(viewer, "/maintenance", """{"enabled":true}""").andExpect(status().isForbidden)
        putAs(viewer, "/platforms/ios", """{"minVersion":"1.0"}""").andExpect(status().isForbidden)
        mvc.perform(delete("$base/flags/beta").principal(viewer)).andExpect(status().isForbidden)
        mvc.perform(delete("$base/platforms/ios").principal(viewer)).andExpect(status().isForbidden)

        assertEquals(0, (store as FakeAppControlStore).rows.size)
        assertEquals(0, AppControlTestBeans.ACTIONS.size)
    }

    @Test
    fun `every change is recorded with who did it`() {
        flag("beta", true, visible = true)
        putAs(admin, "/maintenance", """{"enabled":false}""").andExpect(status().isOk)
        putAs(admin, "/platforms/ios", """{"minVersion":"1.9","storeUrl":"https://apps.apple.com/app/id1"}""").andExpect(status().isOk)
        mvc.perform(patch("$base/platforms/ios").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""{"recommendedVersion":"1.10"}""")).andExpect(status().isOk)
        mvc.perform(delete("$base/platforms/ios").principal(admin)).andExpect(status().isNoContent)
        mvc.perform(delete("$base/flags/beta").principal(admin)).andExpect(status().isNoContent)

        assertEquals(
            listOf("app-control.flag.updated", "app-control.maintenance.updated", "app-control.platform.updated", "app-control.platform.updated", "app-control.platform.deleted", "app-control.flag.deleted"),
            AppControlTestBeans.ACTIONS.map { it.action },
        )
        assertEquals(setOf("acc_op"), AppControlTestBeans.ACTIONS.map { it.actorId }.toSet())
    }

    @Test
    fun `bad input is a 400 with the field, a missing flag to delete is 404`() {
        putAs(admin, "/flags/Bad Key", """{"enabled":true}""").andExpect(status().isBadRequest)
        putAs(admin, "/flags/beta", """{"clientVisible":true}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("enabled"))
        putAs(admin, "/platforms/ios", """{"minVersion":"1.10","recommendedVersion":"1.9"}""").andExpect(status().isBadRequest)
        putAs(admin, "/platforms/ios", """{}""").andExpect(status().isBadRequest)
        putAs(admin, "/platforms/ios", """{"minVersion":"2.0"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("storeUrl"))
        mvc.perform(delete("$base/platforms/ghost").principal(admin)).andExpect(status().isNotFound)
        mvc.perform(delete("$base/flags/ghost").principal(admin)).andExpect(status().isNotFound)
    }

    @Test
    fun `the operator view lists everything including flags the clients never see`() {
        flag("hidden", true)
        flag("shown", false, visible = true)
        putAs(admin, "/platforms/android", """{"minVersion":"2.0","storeUrl":"https://play.google.com/store/apps/details?id=x"}""").andExpect(status().isOk)

        mvc.perform(get(base).principal(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.flags.length()").value(2))
            .andExpect(jsonPath("$.value.flags[?(@.key=='hidden')].updatedBy").value("acc_op"))
            .andExpect(jsonPath("$.value.platforms[0].platform").value("android"))
            .andExpect(jsonPath("$.value.maintenance.enabled").value(false))
    }

    // ---- 클라이언트가 읽는 설정 ----

    @Test
    fun `the app config is anonymous and carries only what a client may see`() {
        flag("hidden", true)
        flag("shown", true, visible = true)
        flag("shown-off", false, visible = true)
        putAs(admin, "/platforms/ios", """{"minVersion":"1.9","recommendedVersion":"1.10","storeUrl":"https://apps.apple.com/app/id1"}""").andExpect(status().isOk)

        mvc.perform(get("/api/v1/app/config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.flags.shown").value(true))
            .andExpect(jsonPath("$.value.flags.shown-off").value(false))
            .andExpect(jsonPath("$.value.flags.hidden").doesNotExist())
            .andExpect(jsonPath("$.value.maintenance.enabled").value(false))
            .andExpect(jsonPath("$.value.platforms.ios.minVersion").value("1.9"))
            .andExpect(jsonPath("$.value.platforms.ios.recommendedVersion").value("1.10"))
            .andExpect(jsonPath("$.value.platforms.ios.storeUrl").value("https://apps.apple.com/app/id1"))
            .andExpect(jsonPath("$.value.serverTime").exists())
            .andExpect(jsonPath("$.value.platforms.ios.updatedBy").doesNotExist())
    }

    @Test
    fun `a patch keeps what it does not name and read alone cannot patch`() {
        putAs(admin, "/platforms/ios", """{"minVersion":"1.9","storeUrl":"https://apps.apple.com/app/id1"}""").andExpect(status().isOk)

        mvc.perform(patch("$base/platforms/ios").principal(viewer).contentType(MediaType.APPLICATION_JSON).content("""{"recommendedVersion":"1.10"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(patch("$base/platforms/ios").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""{"recommendedVersion":"1.10"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.minVersion").value("1.9"))
            .andExpect(jsonPath("$.value.recommendedVersion").value("1.10"))
            .andExpect(jsonPath("$.value.storeUrl").value("https://apps.apple.com/app/id1"))
        mvc.perform(patch("$base/platforms/ios").principal(admin).contentType(MediaType.APPLICATION_JSON).content("""{}""")).andExpect(status().isBadRequest)
    }

    @Test
    fun `an expected end that has passed is left out of the app config`() {
        val ends = (time as MutableTime).instant.plusSeconds(600)
        maintenanceOn("""{"enabled":true,"message":"Soon","endsAt":"$ends"}""")
        mvc.perform(get("/api/v1/app/config")).andExpect(jsonPath("$.value.maintenance.endsAt").value(ends.toString()))

        (time as MutableTime).instant = ends.plusSeconds(1)

        mvc.perform(get("/api/v1/app/config"))
            .andExpect(jsonPath("$.value.maintenance.enabled").value(true))
            .andExpect(jsonPath("$.value.maintenance.endsAt").doesNotExist())
    }

    @Test
    fun `a path another module keeps free of projects also passes during maintenance`() {
        maintenanceOn()

        mvc.perform(get("/test/unscoped")).andExpect(status().isOk)
    }

    @Test
    fun `with nothing set the app config is neutral`() {
        mvc.perform(get("/api/v1/app/config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.maintenance.enabled").value(false))
            .andExpect(jsonPath("$.value.flags").isEmpty)
            .andExpect(jsonPath("$.value.platforms").isEmpty)
    }
}
