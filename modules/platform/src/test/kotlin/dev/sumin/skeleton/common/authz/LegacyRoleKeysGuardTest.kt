package dev.sumin.skeleton.common.authz

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LegacyRoleKeysGuardTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthzAutoConfiguration::class.java))

    private val replacements = mapOf(
        "skeleton.account.admin.role" to "skeleton.authz.super-role",
        "skeleton.payment.admin.role" to "skeleton.authz.roles.<역할>: [payment.read, payment.refund, payment.operate]",
        "skeleton.legal.admin-role" to "skeleton.authz.roles.<역할>: [legal.read]",
        "skeleton.board.moderator-role" to "skeleton.authz.roles.<역할>: [board.moderate, board.manage]",
    )

    @Test
    fun `each of the four old keys with a value fails startup naming the old key and its new place`() {
        replacements.forEach { (oldKey, newPlace) ->
            runner.withPropertyValues("$oldKey=STAFF").run { context ->
                assertThat(context).`as`(oldKey).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining(oldKey).hasStackTraceContaining(newPlace)
            }
        }
    }

    @Test
    fun `without the old keys the context starts`() {
        runner.run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `a blank old key is not a value`() {
        runner.withPropertyValues("skeleton.account.admin.role=").run { assertThat(it).hasNotFailed() }
    }
}
