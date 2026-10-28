package dev.sumin.skeleton.authznoopt

import dev.sumin.skeleton.authz.AuthzRoleStore
import dev.sumin.skeleton.authz.RoleService
import dev.sumin.skeleton.authz.StoredRole
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@SpringBootApplication
class NoSecurityApp

@TestConfiguration(proxyBeanMethods = false)
class Ports {
    @Bean fun store(): AuthzRoleStore = object : AuthzRoleStore {
        private val rows = linkedMapOf("ADMIN" to StoredRole("ADMIN", null, setOf("*"), protected = true), "CS" to StoredRole("CS", null, emptySet()))
        override fun list() = rows.values.toList()
        override fun find(name: String) = rows[name]
        override fun insert(role: StoredRole) = rows.putIfAbsent(role.name, role) == null
        override fun update(name: String, description: String?, permissions: Set<String>, includes: Set<String>?) = false
        override fun delete(name: String) = rows.remove(name) != null
    }
}

/** spring-security-core 도 계정 모듈도 없는 앱 — 서비스는 서고(holders 없음 → null, 삭제 검사 없음), HTTP 컨트롤러는 등록되지 않는다 */
@SpringBootTest(classes = [NoSecurityApp::class], properties = ["skeleton.authz.admin.enabled=true", "skeleton.authz.roles.ADMIN=*"])
@Import(Ports::class)
class AuthzWithoutSecurityTest {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var service: RoleService

    @Test
    fun `starts without security and without account`() {
        assertTrue(context.getBeanNamesForType(PermissionDeclaration::class.java).isNotEmpty())
        assertTrue(!context.containsBean("authzAdminController"))
        assertNull(service.list().first { it.name == "CS" }.holders)
        service.delete(OperatorRef("acc_admin", setOf("ADMIN")), "CS")
        assertEquals(listOf("ADMIN"), service.list().map { it.name })
    }
}
