package dev.sumin.skeleton.authztest

import dev.sumin.skeleton.authz.AuthzRoleStore
import dev.sumin.skeleton.authz.FakeRoleStore
import dev.sumin.skeleton.authz.StoredRole
import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleHolders
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class AuthzWebTestApplication

class RecordingAudit : OperatorAuditLog {
    val actions = CopyOnWriteArrayList<OperatorAction>()
    override fun record(action: OperatorAction) {
        actions += action
    }
}

class StubHolders : RoleHolders {
    val counts = HashMap<String, Long>()
    val revoked = CopyOnWriteArrayList<Pair<String, String>>()
    override fun holdersOf(role: String): Long = counts[role] ?: 0L
    override fun revokeFromAll(role: String, by: String): Int { revoked += role to by; return 0 }
}

@TestConfiguration(proxyBeanMethods = false)
class AuthzWebFakes {
    @Bean fun fakeStore() = FakeRoleStore().apply {
        insert(StoredRole("ADMIN", "Full access", setOf("*"), protected = true))
        insert(StoredRole("OPS", null, setOf("authz.roles")))
        insert(StoredRole("CS", "Support", setOf("payment.read")))
    }

    @Bean fun store(store: FakeRoleStore): AuthzRoleStore = store
    @Bean fun audit() = RecordingAudit()
    @Bean fun auditLog(audit: RecordingAudit): OperatorAuditLog = audit
    @Bean fun holders() = StubHolders()
    @Bean fun roleHolders(holders: StubHolders): RoleHolders = holders

    @Bean fun paymentPermissions() = PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund")))
    @Bean fun accountPermissions() = PermissionDeclaration("account", listOf(DeclaredPermission("account.roles")))
}
