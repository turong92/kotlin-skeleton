package dev.sumin.skeleton.common.enumcode

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.web.bind.annotation.RestController

class CodeEnumOpenApiCustomizer(
    private val applicationContext: ApplicationContext,
) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return
        val dtoClasses = controllerDtoClasses()
        val codeEnumClasses = controllerCodeEnumClasses()
        schemas.forEach { (name, schema) ->
            dtoClasses[name]?.let { dtoClass ->
                applyCodeEnumDescriptions(schema, dtoClass)
            }
        }
        openApi.paths?.values?.forEach { pathItem ->
            pathItem.readOperations().forEach { operation ->
                operation.parameters.orEmpty().forEach { parameter ->
                    parameter.schema?.let { schema -> applyMatchedCodeEnumSchema(schema, codeEnumClasses) }
                }
            }
        }
    }

    private fun controllerDtoClasses(): Map<String, Class<*>> {
        val result = linkedMapOf<String, Class<*>>()
        applicationContext.getBeansWithAnnotation(RestController::class.java).values.forEach { controller ->
            val userClass = controller.javaClass
            userClass.declaredMethods.forEach { method ->
                processType(method.genericReturnType, result)
                method.genericParameterTypes.forEach { processType(it, result) }
                method.parameterTypes.forEach { processClass(it, result) }
            }
        }
        return result
    }

    private fun controllerCodeEnumClasses(): Collection<Class<*>> {
        val result = linkedMapOf<String, Class<*>>()
        applicationContext.getBeansWithAnnotation(RestController::class.java).values.forEach { controller ->
            val userClass = controller.javaClass
            userClass.declaredMethods.forEach { method ->
                processCodeEnumType(method.genericReturnType, result)
                method.genericParameterTypes.forEach { processCodeEnumType(it, result) }
                method.parameterTypes.forEach { processCodeEnumClass(it, result) }
            }
        }
        return result.values
    }

    private fun processType(
        type: Type,
        result: MutableMap<String, Class<*>>,
    ) {
        when (type) {
            is Class<*> -> processClass(type, result)
            is ParameterizedType -> {
                (type.rawType as? Class<*>)?.let { processClass(it, result) }
                type.actualTypeArguments.forEach { processType(it, result) }
            }
        }
    }

    private fun processClass(
        clazz: Class<*>,
        result: MutableMap<String, Class<*>>,
    ) {
        if (!clazz.name.startsWith("dev.sumin.skeleton")) {
            return
        }
        if (clazz.isPrimitive || clazz.isEnum || clazz.isInterface || clazz.isAnnotation) {
            return
        }
        result.putIfAbsent(clazz.simpleName, clazz)
    }

    private fun processCodeEnumType(
        type: Type,
        result: MutableMap<String, Class<*>>,
    ) {
        when (type) {
            is Class<*> -> processCodeEnumClass(type, result)
            is ParameterizedType -> {
                (type.rawType as? Class<*>)?.let { processCodeEnumClass(it, result) }
                type.actualTypeArguments.forEach { processCodeEnumType(it, result) }
            }
        }
    }

    private fun processCodeEnumClass(
        clazz: Class<*>,
        result: MutableMap<String, Class<*>>,
    ) {
        if (CodeEnumResolver.isCodeEnum(clazz)) {
            result.putIfAbsent(clazz.name, clazz)
            return
        }
        if (!clazz.name.startsWith("dev.sumin.skeleton")) {
            return
        }
        if (clazz.isPrimitive || clazz.isInterface || clazz.isAnnotation) {
            return
        }
        clazz.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.type }
            .filter { CodeEnumResolver.isCodeEnum(it) }
            .forEach { result.putIfAbsent(it.name, it) }
    }

    private fun applyCodeEnumDescriptions(
        schema: Schema<*>,
        dtoClass: Class<*>,
    ) {
        val properties = childProperties(schema) ?: return
        dtoClass.declaredFields
            .filterNot { it.isSynthetic }
            .forEach { field ->
                val enumClass = field.type
                if (!CodeEnumResolver.isCodeEnum(enumClass)) {
                    return@forEach
                }
                val propertySchema = properties[field.name] ?: return@forEach
                applyCodeEnumSchema(propertySchema, enumClass)
            }
    }

    private fun applyMatchedCodeEnumSchema(
        schema: Schema<*>,
        codeEnumClasses: Collection<Class<*>>,
    ) {
        val matchedEnumClass = codeEnumClasses.firstOrNull { enumClass ->
            val names = enumClass.enumConstants
                .map { (it as Enum<*>).name }
            schema.getEnum()
                ?.map { it.toString() }
                ?.let { values -> values == names }
                ?: false
        } ?: return

        applyCodeEnumSchema(schema, matchedEnumClass)
    }

    private fun applyCodeEnumSchema(
        schema: Schema<*>,
        enumClass: Class<*>,
    ) {
        schema.description(mergeDescription(schema.description, CodeEnumResolver.describe(enumClass)))
        val descriptors = CodeEnumResolver.descriptors(enumClass)
        when (descriptors.firstOrNull()?.code) {
            is Number -> {
                schema.setTypes(setOf("integer"))
                schema.setType("integer")
                schema.format("int32")
            }
            is String -> {
                schema.setTypes(setOf("string"))
                schema.setType("string")
            }
        }
        @Suppress("UNCHECKED_CAST")
        (schema as Schema<Any>)._enum(descriptors.map { it.code })
    }

    @Suppress("UNCHECKED_CAST")
    private fun childProperties(schema: Schema<*>): MutableMap<String, Schema<Any>>? =
        when {
            schema.allOf != null && schema.allOf.size > 1 ->
                schema.allOf[1].properties as? MutableMap<String, Schema<Any>>
            else -> schema.properties as? MutableMap<String, Schema<Any>>
        }

    private fun mergeDescription(
        current: String?,
        codeEnumDescription: String,
    ): String =
        listOfNotNull(current?.takeIf { it.isNotBlank() }, codeEnumDescription)
            .joinToString("\n\n")
}
