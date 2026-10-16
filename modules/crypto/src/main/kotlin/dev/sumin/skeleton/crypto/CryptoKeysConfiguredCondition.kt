package dev.sumin.skeleton.crypto

import dev.sumin.skeleton.common.project.ProjectRegistry
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

class CryptoKeysConfiguredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val keys = Binder.get(context.environment)
            .bind("skeleton.crypto.keys", Bindable.mapOf(String::class.java, String::class.java))
            .orElse(emptyMap()) ?: emptyMap()
        return keys.values.any { it.isNotBlank() }
    }
}

/** 프로젝트 모드: 공통 키가 있거나 어느 프로젝트든 자기 키를 적었을 때 */
class ProjectCryptoKeysConfiguredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean =
        CryptoKeysConfiguredCondition().matches(context, metadata) || ProjectRegistry.hasConfig(context.environment, "crypto.keys.")
}
