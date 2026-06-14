package dev.sumin.skeleton.crypto

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
