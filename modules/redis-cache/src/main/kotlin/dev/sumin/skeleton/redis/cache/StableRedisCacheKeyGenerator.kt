package dev.sumin.skeleton.redis.cache

import java.lang.reflect.Array
import java.lang.reflect.Method
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.util.ClassUtils

class StableRedisCacheKeyGenerator : KeyGenerator {
    override fun generate(
        target: Any,
        method: Method,
        vararg params: Any?,
    ): Any =
        (listOf(ClassUtils.getUserClass(target).name, method.name) + params.map(::stablePart))
            .joinToString("::")

    private fun stablePart(value: Any?): String =
        when {
            value == null -> "null"
            value is CharSequence -> value.toString()
            value is Number -> value.toString()
            value is Boolean -> value.toString()
            value is Enum<*> -> value.name
            value.javaClass.isArray -> arrayPart(value)
            value is Iterable<*> -> value.joinToString(",", "[", "]") { stablePart(it) }
            value is Map<*, *> -> mapPart(value)
            else -> value.toString()
        }

    private fun arrayPart(value: Any): String =
        (0 until Array.getLength(value))
            .joinToString(",", "[", "]") { index -> stablePart(Array.get(value, index)) }

    private fun mapPart(value: Map<*, *>): String =
        value.entries
            .map { (key, mapValue) -> stablePart(key) to stablePart(mapValue) }
            .sortedBy { (key, _) -> key }
            .joinToString(",", "{", "}") { (key, mapValue) -> "$key=$mapValue" }
}
