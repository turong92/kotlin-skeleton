package dev.sumin.skeleton.common.enumcode

import kotlin.reflect.KClass

interface CodeEnum<C : Any> {
    val code: C
    val label: String
    val description: String?
        get() = null
}

interface IntCodeEnum : CodeEnum<Int>

interface StringCodeEnum : CodeEnum<String>

data class CodeEnumDescriptor<C : Any>(
    val code: C,
    val name: String,
    val label: String,
    val description: String? = null,
)

class CodeEnumException(message: String) : IllegalArgumentException(message)

val CodeEnum<*>.enumName: String
    get() = (this as? Enum<*>)?.name
        ?: throw CodeEnumException("CodeEnum '${this::class.qualifiedName}' must be an enum constant.")

val CodeEnum<*>.codeName: String
    get() = "${code}($enumName)"

fun <C : Any> CodeEnum<C>.toDescriptor(): CodeEnumDescriptor<C> =
    CodeEnumDescriptor(
        code = code,
        name = enumName,
        label = label,
        description = description,
    )

object CodeEnumResolver {
    fun <E> fromRaw(enumClass: KClass<E>, raw: Any?): E where E : Enum<E>, E : CodeEnum<*> =
        enumClass.java.cast(fromRaw(enumClass.java, raw))

    fun fromRaw(enumClass: Class<*>, raw: Any?): Any {
        val constants = constants(enumClass)
        val normalized = raw ?: throw CodeEnumException("Code for enum ${enumClass.simpleName} must not be null.")
        validateUniqueCodes(enumClass, constants)
        return constants.firstOrNull { matches(it.code, normalized) }
            ?: throw CodeEnumException("Unknown code '${normalized}' for enum ${enumClass.simpleName}.")
    }

    fun <E> descriptors(enumClass: KClass<E>): List<CodeEnumDescriptor<Any>> where E : Enum<E>, E : CodeEnum<*> =
        descriptors(enumClass.java)

    fun descriptors(enumClass: Class<*>): List<CodeEnumDescriptor<Any>> {
        val constants = constants(enumClass)
        validateUniqueCodes(enumClass, constants)
        return constants.map {
            @Suppress("UNCHECKED_CAST")
            (it as CodeEnum<Any>).toDescriptor()
        }
    }

    fun describe(enumClass: Class<*>): String =
        descriptors(enumClass).joinToString(prefix = "${enumClass.simpleName}: ") { descriptor ->
            buildString {
                append("{code: ").append(descriptor.code)
                    .append(", name: ").append(descriptor.name)
                    .append(", label: ").append(descriptor.label)
                descriptor.description?.let { append(", description: ").append(it) }
                append("}")
            }
        }

    fun isCodeEnum(enumClass: Class<*>): Boolean =
        enumClass.isEnum && CodeEnum::class.java.isAssignableFrom(enumClass)

    private fun constants(enumClass: Class<*>): List<CodeEnum<*>> {
        if (!isCodeEnum(enumClass)) {
            throw CodeEnumException("Class '${enumClass.name}' is not a CodeEnum enum.")
        }
        return enumClass.enumConstants
            .orEmpty()
            .map { it as CodeEnum<*> }
    }

    private fun validateUniqueCodes(enumClass: Class<*>, constants: List<CodeEnum<*>>) {
        val seen = linkedSetOf<Any>()
        constants.forEach { constant ->
            if (!seen.add(constant.code)) {
                throw CodeEnumException("Duplicate code '${constant.code}' in enum ${enumClass.simpleName}.")
            }
        }
    }

    private fun matches(code: Any, raw: Any): Boolean =
        when (code) {
            is Int -> when (raw) {
                is Number -> code == raw.toInt()
                is String -> raw.toIntOrNull() == code
                else -> false
            }
            is Long -> when (raw) {
                is Number -> code == raw.toLong()
                is String -> raw.toLongOrNull() == code
                else -> false
            }
            is Short -> when (raw) {
                is Number -> code == raw.toShort()
                is String -> raw.toShortOrNull() == code
                else -> false
            }
            is String -> raw.toString() == code
            else -> raw == code || raw.toString() == code.toString()
        }
}
