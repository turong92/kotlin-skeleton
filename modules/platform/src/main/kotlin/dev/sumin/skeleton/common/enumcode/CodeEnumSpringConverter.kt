package dev.sumin.skeleton.common.enumcode

import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.ConditionalGenericConverter
import org.springframework.core.convert.converter.GenericConverter

class CodeEnumSpringConverter : ConditionalGenericConverter {
    override fun getConvertibleTypes(): Set<GenericConverter.ConvertiblePair> =
        setOf(GenericConverter.ConvertiblePair(String::class.java, Enum::class.java))

    override fun matches(
        sourceType: TypeDescriptor,
        targetType: TypeDescriptor,
    ): Boolean =
        CodeEnumResolver.isCodeEnum(targetType.type)

    override fun convert(
        source: Any?,
        sourceType: TypeDescriptor,
        targetType: TypeDescriptor,
    ): Any? {
        val value = source?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CodeEnumResolver.fromRaw(targetType.type, value)
    }
}
