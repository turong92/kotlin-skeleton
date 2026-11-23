package dev.sumin.skeleton.auth.api

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [RequiredLoginIdentifierValidator::class])
annotation class RequiredLoginIdentifier(
    val message: String = "accountId, username, or email is required",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class RequiredLoginIdentifierValidator : ConstraintValidator<RequiredLoginIdentifier, PasswordLoginRequest> {
    override fun isValid(
        value: PasswordLoginRequest?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null) {
            return true
        }

        val hasIdentifier = listOf(value.accountId, value.username, value.email)
            .any { !it.isNullOrBlank() }
        if (hasIdentifier) {
            return true
        }

        context.disableDefaultConstraintViolation()
        context.buildConstraintViolationWithTemplate("accountId, username, or email is required")
            .addPropertyNode("identifier")
            .addConstraintViolation()
        return false
    }
}
