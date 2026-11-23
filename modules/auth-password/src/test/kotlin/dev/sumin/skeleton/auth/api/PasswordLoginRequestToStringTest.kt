package dev.sumin.skeleton.auth.api

import kotlin.test.Test
import kotlin.test.assertFalse

class PasswordLoginRequestToStringTest {
    @Test
    fun `the login request hides the identifier and the password from toString`() {
        val text = PasswordLoginRequest(email = "a@b.co", password = "s3cret-value").toString()
        assertFalse("s3cret-value" in text || "a@b.co" in text, text)
    }
}
