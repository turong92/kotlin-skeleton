package dev.sumin.skeleton.common.enumcode

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.context.ApplicationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.MethodParameter
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerMethod

class CodeEnumOpenApiCustomizer(
    private val applicationContext: ApplicationContext,
) : OpenApiCustomizer {
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()

    override fun customise(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return
        val dtoClasses = controllerDtoClasses()
        schemas.forEach { (name, schema) ->
            dtoClasses[name]?.let { dtoClass ->
                applyCodeEnumDescriptions(schema, dtoClass)
            }
        }
    }

    fun customiseOperation(
        operation: Operation,
        handlerMethod: HandlerMethod,
    ) {
        handlerMethod.methodParameters.forEach { methodParameter ->
            methodParameter.initParameterNameDiscovery(parameterNameDiscoverer)
            val enumClass = methodParameter.parameterType
            if (!CodeEnumResolver.isCodeEnum(enumClass)) {
                return@forEach
            }
            val parameterLocation = parameterLocation(methodParameter) ?: return@forEach
            val parameterName = parameterName(methodParameter) ?: return@forEach
            operation.parameters
                .orEmpty()
                .filter { it.`in` == parameterLocation && it.name == parameterName }
                .forEach { parameter ->
                    val schema = parameter.schema ?: Schema<Any>().also { parameter.schema = it }
                    applyCodeEnumSchema(schema, enumClass)
                }
        }
    }

    private fun parameterLocation(methodParameter: MethodParameter): String? =
        when {
            methodParameter.hasParameterAnnotation(PathVariable::class.java) -> "path"
            methodParameter.hasParameterAnnotation(RequestParam::class.java) -> "query"
            else -> null
        }

    private fun parameterName(methodParameter: MethodParameter): String? {
        methodParameter.getParameterAnnotation(PathVariable::class.java)?.let { annotation ->
            return explicitName(annotation.name, annotation.value)
                ?: discoveredParameterName(methodParameter)
        }
        methodParameter.getParameterAnnotation(RequestParam::class.java)?.let { annotation ->
            return explicitName(annotation.name, annotation.value)
                ?: discoveredParameterName(methodParameter)
        }
        return null
    }

    private fun explicitName(vararg candidates: String): String? =
        candidates.firstOrNull { it.isNotBlank() }

    private fun discoveredParameterName(methodParameter: MethodParameter): String? =
        methodParameter.parameterName
            ?: methodParameter.executable.parameters
                .getOrNull(methodParameter.parameterIndex)
                ?.takeIf { it.isNamePresent }
                ?.name

    private fun controllerDtoClasses(): Map<String, Class<*>> {
        val result = linkedMapOf<String, Class<*>>()
        applicationContext.getBeansWithAnnotation(RestController::class.java).values.forEach { controller ->
            val userClass = AopProxyUtils.ultimateTargetClass(controller)
            userClass.declaredMethods.forEach { method ->
                processType(method.genericReturnType, result)
                method.genericParameterTypes.forEach { processType(it, result) }
                method.parameterTypes.forEach { processClass(it, result) }
            }
        }
        return result
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

    private fun applyCodeEnumDescriptions(
        schema: Schema<*>,
        dtoClass: Class<*>,
    ) {
        val propertyMaps = childProperties(schema).toList()
        dtoClass.declaredFields
            .filterNot { it.isSynthetic }
            .forEach { field ->
                val enumClass = field.type
                if (!CodeEnumResolver.isCodeEnum(enumClass)) {
                    return@forEach
                }
                propertyMaps
                    .mapNotNull { it[field.name] }
                    .forEach { propertySchema -> applyCodeEnumSchema(propertySchema, enumClass) }
            }
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
    private fun childProperties(schema: Schema<*>): Sequence<MutableMap<String, Schema<Any>>> = sequence {
        schema.properties?.let {
            yield(it as MutableMap<String, Schema<Any>>)
        }
        schema.allOf
            .orEmpty()
            .mapNotNull { it.properties as? MutableMap<String, Schema<Any>> }
            .forEach { yield(it) }
    }

    private fun mergeDescription(
        current: String?,
        codeEnumDescription: String,
    ): String =
        listOfNotNull(current?.takeIf { it.isNotBlank() }, codeEnumDescription)
            .joinToString("\n\n")
}
