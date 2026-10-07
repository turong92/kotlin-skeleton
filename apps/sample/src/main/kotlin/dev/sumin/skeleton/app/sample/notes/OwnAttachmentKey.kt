package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.storage.StorageKeyOwnership
import dev.sumin.skeleton.storage.StorageProperties
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 첨부 키는 호출자 자신이 올린 것이어야 한다 — 소유 판정은 storage 모듈의 [StorageKeyOwnership] 이 한다(`key-owner` 방식에 맞는 구현: 접두사 비교든 OPAQUE 의 MAC 이든 앱은 모른다).
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
class OwnAttachmentKeyValidator(private val ownership: StorageKeyOwnership) : ConstraintValidator<OwnAttachmentKey, String?> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val caller = SecurityContextHolder.getContext().authentication?.name ?: return false
        return ownership.owns(caller, value)
    }
}

/**
 * 앱이 직접 쓰는 키 하나 — 내보내기 결과(`<key-prefix>/<계정 id>/exports/…`, [NoteExportJobHandler]). 업로드 키와 달리 서버가 정하는 이름이라 모양을 여기서 정한다.
 * 이 키는 노트 주인에게만 알림 payload 로 가고 게시판에 실리지 않는다. `ACCOUNT_ID` 꼴 키는 `OPAQUE` 에서도 소유가 확인되므로(docs/modules/storage.md) 내려받기가 그대로 된다.
 */
@Component
class OwnedKeys(private val storage: StorageProperties) {
    fun prefix(owner: String): String = "${storage.web.keyPrefix.trim('/')}/$owner/"
}
