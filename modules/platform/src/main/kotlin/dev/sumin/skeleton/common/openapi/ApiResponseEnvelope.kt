package dev.sumin.skeleton.common.openapi

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiResponseEnvelope(
    val type: ApiEnvelopeType,
    val value: KClass<*> = Unit::class,
)

enum class ApiEnvelopeType {
    BASIC,
    VALUE,
    LIST,
    PAGE,
    CURSOR,
}
