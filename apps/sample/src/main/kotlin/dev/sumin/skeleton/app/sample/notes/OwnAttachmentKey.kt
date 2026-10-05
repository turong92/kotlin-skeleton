package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.storage.StorageProperties
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 첨부 키는 호출자 자신의 저장소 접두사(`<key-prefix>/<계정 id>/`) 아래여야 한다 — storage 모듈이 키를 정하는 규칙과 같다.
 * 구조 규칙은 제약으로 선언한다(CLAUDE.md): 어긴 값은 400 `errors[{field: "attachmentKey"}]` 로 나간다.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [OwnAttachmentKeyValidator::class])
annotation class OwnAttachmentKey(
    val message: String = "attachmentKey must be an upload of the signed-in user",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** Spring 이 만드는 검증기라 빈을 주입받는다. 호출자는 요청 스레드의 SecurityContext 에서 읽는다 */
class OwnAttachmentKeyValidator(private val keys: OwnedKeys) : ConstraintValidator<OwnAttachmentKey, String?> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val caller = SecurityContextHolder.getContext().authentication?.name ?: return false
        return keys.isOwnedBy(caller, value)
    }
}

/** storage 모듈이 정하는 키 모양 `<key-prefix>/<계정 id>/…` 를 앱이 한 곳에서 다룬다 (같은 설정 `skeleton.storage.web.key-prefix`) */
@Component
class OwnedKeys(private val storage: StorageProperties) {
    fun prefix(owner: String): String = "${storage.web.keyPrefix.trim('/')}/$owner/"

    fun isOwnedBy(owner: String, key: String): Boolean {
        val prefix = prefix(owner)
        return key.startsWith(prefix) && key.length > prefix.length &&
            key.split('/').none { it == ".." || it == "." } && key.none { it.isISOControl() || it == '\\' }
    }
}
