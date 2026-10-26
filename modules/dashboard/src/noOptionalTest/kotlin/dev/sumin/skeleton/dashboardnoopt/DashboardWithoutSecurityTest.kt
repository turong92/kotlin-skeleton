package dev.sumin.skeleton.dashboardnoopt

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.dashboard.DashboardAssembler
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootApplication
class NoSecurityApp

/** spring-security-core 가 없는 앱 — 조립 부품과 권한 선언은 서고, 관리 API 컨트롤러는 스위치를 켜도 등록되지 않는다 */
@SpringBootTest(classes = [NoSecurityApp::class], properties = ["skeleton.dashboard.admin.enabled=true"])
class DashboardWithoutSecurityTest {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `starts without security and registers no controller`() {
        assertTrue(context.getBeanNamesForType(PermissionDeclaration::class.java).isNotEmpty())
        assertTrue(context.getBeanNamesForType(DashboardAssembler::class.java).isNotEmpty())
        assertTrue(!context.containsBean("dashboardAdminController"))
    }
}
