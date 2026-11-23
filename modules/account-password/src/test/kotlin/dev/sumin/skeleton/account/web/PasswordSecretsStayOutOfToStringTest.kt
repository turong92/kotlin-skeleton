package dev.sumin.skeleton.account.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 비밀번호 쪽 요청 · 응답 · 명령도 `toString` 에 비밀을 싣지 않는다 (Spring MVC 가 DEBUG · TRACE 에서 본문을 `toString` 으로 찍는다) */
class PasswordSecretsStayOutOfToStringTest {
    private val secret = "S3cr3t-Value-123"

    private fun assertHidden(value: Any) {
        val text = value.toString()
        assertFalse(secret in text, "${value.javaClass.simpleName}.toString() leaked a secret: $text")
        assertTrue(value.javaClass.simpleName in text, "toString should still say what it is")
    }

    @Test
    fun `password request bodies, the sign-up answer and the sign-up command hide passwords and tokens`() {
        assertHidden(SignUpRequest("a@b.co", secret))
        assertHidden(ResetPasswordRequest(secret, secret))
        assertHidden(ChangePasswordRequest(secret, secret))
        assertHidden(VerifyEmailRequest(secret, secret))
        assertHidden(ResendRequest(secret))
        assertHidden(SignUpResponse("VERIFICATION_SENT", secret))
        assertHidden(dev.sumin.skeleton.account.SignUpCommand("a@b.co", secret, null, null, null, null, null))
    }

    @Test
    fun `the 202 answers of sign-up resend and forgot are declared with the platform operation annotation so OpenAPI says 202 not 200`() {
        fun annotated(name: String) = AccountPasswordPublicController::class.java.methods.single { it.name == name }.isAnnotationPresent(dev.sumin.skeleton.common.openapi.AcceptedOperation::class.java)
        assertTrue(annotated("signUp"))
        assertTrue(annotated("resend"))
        assertTrue(annotated("forgot"))
    }
}
