package dev.sumin.skeleton.common.enumcode

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.SerializationConfig
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.ValueSerializerModifier

class CodeEnumJacksonModule : SimpleModule("skeleton-code-enum") {
    init {
        setSerializerModifier(CodeEnumSerializerModifier())
        setDeserializerModifier(CodeEnumDeserializerModifier())
    }
}

private class CodeEnumSerializerModifier : ValueSerializerModifier() {
    override fun modifyEnumSerializer(
        config: SerializationConfig,
        valueType: JavaType,
        beanDesc: BeanDescription.Supplier,
        serializer: ValueSerializer<*>,
    ): ValueSerializer<*> =
        if (CodeEnumResolver.isCodeEnum(valueType.rawClass)) {
            CodeEnumValueSerializer
        } else {
            serializer
        }
}

private object CodeEnumValueSerializer : ValueSerializer<CodeEnum<*>>() {
    override fun serialize(
        value: CodeEnum<*>,
        generator: JsonGenerator,
        context: SerializationContext,
    ) {
        when (val code = value.code) {
            is Int -> generator.writeNumber(code)
            is Long -> generator.writeNumber(code)
            is Short -> generator.writeNumber(code)
            is String -> generator.writeString(code)
            is Boolean -> generator.writeBoolean(code)
            else -> generator.writePOJO(code)
        }
    }
}

private class CodeEnumDeserializerModifier : ValueDeserializerModifier() {
    override fun modifyEnumDeserializer(
        config: DeserializationConfig,
        type: JavaType,
        beanDesc: BeanDescription.Supplier,
        deserializer: ValueDeserializer<*>,
    ): ValueDeserializer<*> =
        if (CodeEnumResolver.isCodeEnum(type.rawClass)) {
            CodeEnumValueDeserializer(type.rawClass)
        } else {
            deserializer
        }
}

private class CodeEnumValueDeserializer(
    private val enumClass: Class<*>,
) : ValueDeserializer<Any>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Any {
        val raw = when (parser.currentToken()) {
            JsonToken.VALUE_NUMBER_INT -> parser.intValue
            JsonToken.VALUE_STRING -> parser.valueAsString
            JsonToken.VALUE_NULL -> null
            else -> throw CodeEnumException("Unsupported token '${parser.currentToken()}' for enum ${enumClass.simpleName}.")
        }
        return try {
            CodeEnumResolver.fromRaw(enumClass, raw)
        } catch (ex: CodeEnumException) {
            context.reportInputMismatch(enumClass, ex.message)
        }
    }
}
