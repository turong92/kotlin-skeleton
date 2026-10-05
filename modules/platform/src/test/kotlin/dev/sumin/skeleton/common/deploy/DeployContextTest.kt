package dev.sumin.skeleton.common.deploy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class DeployContextTest {
    @Test
    fun `unset switch means no environment and nothing is protected by it`() {
        val context = DeployContext.from(MockEnvironment())

        assertNull(context.env)
        assertFalse(context.protectedEnv)
        assertFalse(context.protectedBy(emptyList()))
    }

    @Test
    fun `blank switch counts as unset`() {
        assertNull(DeployContext.from(MockEnvironment().withProperty("skeleton.env", "  ")).env)
    }

    @Test
    fun `values are case-insensitive and trimmed`() {
        assertEquals(DeployEnv.PROD, DeployContext.from(MockEnvironment().withProperty("skeleton.env", " Prod ")).env)
        assertEquals(DeployEnv.STAGE, DeployContext.from(MockEnvironment().withProperty("skeleton.env", "stage")).env)
        assertEquals(DeployEnv.LOCAL, DeployContext.from(MockEnvironment().withProperty("skeleton.env", "LOCAL")).env)
    }

    @Test
    fun `stage and prod are protected, local is not`() {
        assertTrue(DeployContext(DeployEnv.PROD, emptySet()).protectedEnv)
        assertTrue(DeployContext(DeployEnv.STAGE, emptySet()).protectedEnv)
        assertFalse(DeployContext(DeployEnv.LOCAL, emptySet()).protectedEnv)
    }

    @Test
    fun `a guard's own protected profiles keep working when the switch is unset`() {
        val context = DeployContext(null, setOf("prod"))

        assertTrue(context.protectedBy(listOf("prod", "staging")))
        assertFalse(context.protectedBy(listOf("staging")))
        assertEquals(listOf("prod"), context.protectedBecause(listOf("prod", "staging")))
    }

    @Test
    fun `the switch protects even with no matching profile and the reason names it`() {
        val context = DeployContext(DeployEnv.PROD, setOf("local"))

        assertTrue(context.protectedBy(listOf("prod")))
        assertEquals(listOf("skeleton.env=prod"), context.protectedBecause(listOf("prod")))
    }

    @Test
    fun `an unknown value fails startup and never echoes the value`() {
        val failure = assertFailsWith<DeployGuardViolationException> {
            DeployContext.from(MockEnvironment().withProperty("skeleton.env", "porduction-secret-typo"))
        }

        val message = failure.message.orEmpty()
        assertTrue("SKELETON_ENV" in message, message)
        assertTrue("local | stage | prod" in message, message)
        assertFalse("porduction" in message, message)
    }

    @Test
    fun `active profiles are read from the environment`() {
        val environment = MockEnvironment().withProperty("skeleton.env", "stage")
        environment.setActiveProfiles("staging", "extra")

        assertEquals(setOf("staging", "extra"), DeployContext.from(environment).activeProfiles)
    }
}
