package dev.sumin.skeleton.account.signin

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SignInMethodCodeTest {
    private fun method(c: String) = object : SignInMethod { override val code = c }

    @Test
    fun `a one letter provider code such as x is a valid sign-in method code`() {
        assertNotNull(SignInMethodRegistry(listOf(method("x"))).find("x"))
    }

    @Test
    fun `codes still start with a lowercase letter, stay lowercase and fit the 32 column`() {
        for (bad in listOf("X", "1x", "_x", "a-b", "", "a".repeat(33))) assertFailsWith<IllegalArgumentException>(bad) { SignInMethodRegistry(listOf(method(bad))) }
        SignInMethodRegistry(listOf(method("a".repeat(32))))
    }
}
