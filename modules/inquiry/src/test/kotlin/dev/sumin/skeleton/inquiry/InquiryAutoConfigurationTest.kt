package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.inquiry.web.InquiryAdminController
import dev.sumin.skeleton.inquiry.web.InquiryController
import dev.sumin.skeleton.inquiry.web.InquiryWebAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

class InquiryAutoConfigurationTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InquiryAutoConfiguration::class.java, InquiryWebAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `defaults to an in-memory repository, a passing human check, the controller and an erasure hook`() {
        runner.run { context ->
            assertIs<InMemoryInquiryRepository>(context.getBean(InquiryRepository::class.java))
            assertEquals(true, context.getBean(InquiryHumanCheck::class.java).passes(null, null))
            context.getBean(InquiryController::class.java)
            assertEquals("inquiry", context.getBean(AccountErasureListener::class.java).name)
        }
    }

    @Test
    fun `an app can replace the repository and the human check`() {
        runner
            .withBean(InquiryRepository::class.java, { InMemoryInquiryRepository() })
            .withBean(InquiryHumanCheck::class.java, { InquiryHumanCheck { _, _ -> false } })
            .run { context ->
                assertEquals(1, context.getBeansOfType(InquiryRepository::class.java).size)
                assertEquals(false, context.getBean(InquiryHumanCheck::class.java).passes("x", null))
            }
    }

    @Test
    fun `the http endpoint can be switched off`() {
        runner.withPropertyValues("skeleton.inquiry.enabled=false").run { context ->
            assertEquals(0, context.getBeansOfType(InquiryController::class.java).size)
        }
    }

    @Test
    fun `the operator endpoints are off by default and need the opt-in plus the operator beans`() {
        runner.run { context -> assertEquals(0, context.getBeansOfType(InquiryAdminController::class.java).size) }
        runner.withPropertyValues("skeleton.inquiry.admin.enabled=true")
            .withBean(OperatorAccess::class.java, { object : OperatorAccess { override fun permissionsOf(ref: dev.sumin.skeleton.common.authz.OperatorRef) = emptySet<String>() } })
            .withBean(OperatorAuditLog::class.java, { OperatorAuditLog { } })
            .run { context -> context.getBean(InquiryAdminController::class.java) }
    }

    @Test
    fun `the module declares its two permissions`() {
        runner.run { context ->
            val declaration = context.getBean(PermissionDeclaration::class.java)
            assertEquals("inquiry", declaration.module)
            assertEquals(listOf("inquiry.read", "inquiry.handle"), declaration.permissions.map { it.key })
        }
    }
}
