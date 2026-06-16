package dev.sumin.skeleton.common.openapi

import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageResponse
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.springframework.http.ResponseEntity

data class ResolvedApiResponseEnvelope(
    val type: ApiEnvelopeType,
    val payloadClass: Class<*>?,
)

object ApiResponseEnvelopeResolver {
    fun resolve(method: Method): ResolvedApiResponseEnvelope? {
        method.getAnnotation(ApiResponseEnvelope::class.java)?.let { annotation ->
            val payloadClass = when (annotation.type) {
                ApiEnvelopeType.BASIC -> null
                else -> annotation.value.java.takeUnless { it == Unit::class.java }
            }
            if (annotation.type != ApiEnvelopeType.BASIC && payloadClass == null) {
                error(
                    "@ApiResponseEnvelope on ${method.declaringClass.simpleName}.${method.name} " +
                        "requires value for ${annotation.type}",
                )
            }
            return ResolvedApiResponseEnvelope(annotation.type, payloadClass)
        }

        return resolveType(method.genericReturnType)
    }

    private fun resolveType(type: Type): ResolvedApiResponseEnvelope? {
        val unwrappedType = unwrapResponseEntity(type)
        val rawType = rawClass(unwrappedType) ?: return null
        val payloadClass = firstTypeArgumentClass(unwrappedType)

        return when (rawType) {
            BasicResponse::class.java -> ResolvedApiResponseEnvelope(ApiEnvelopeType.BASIC, null)
            DataResponse::class.java -> payloadClass?.let { ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, it) }
            ListResponse::class.java -> payloadClass?.let { ResolvedApiResponseEnvelope(ApiEnvelopeType.LIST, it) }
            PageResponse::class.java -> payloadClass?.let { ResolvedApiResponseEnvelope(ApiEnvelopeType.PAGE, it) }
            CursorResponse::class.java -> payloadClass?.let { ResolvedApiResponseEnvelope(ApiEnvelopeType.CURSOR, it) }
            else -> null
        }
    }

    private fun unwrapResponseEntity(type: Type): Type =
        when {
            rawClass(type) == ResponseEntity::class.java && type is ParameterizedType ->
                type.actualTypeArguments.first()
            else -> type
        }

    private fun rawClass(type: Type): Class<*>? =
        when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*>
            else -> null
        }

    private fun firstTypeArgumentClass(type: Type): Class<*>? {
        val argument = (type as? ParameterizedType)
            ?.actualTypeArguments
            ?.firstOrNull()
        return argument as? Class<*>
    }
}

object ApiResponseEnvelopeSchemas {
    fun componentSchemas(): Map<String, Schema<Any>> =
        linkedMapOf(
            "BasicResponse" to basicSchema(),
            "DataResponse" to dataSchema(Schema<Any>().description("Payload value.")),
            "ListResponse" to listSchema(Schema<Any>().description("Payload item.")),
            "PageResponse" to pageSchema(Schema<Any>().description("Payload item.")),
            "CursorResponse" to cursorSchema(Schema<Any>().description("Payload item.")),
            "CursorMeta" to cursorMetaSchema(),
        )

    fun operationSchema(envelope: ResolvedApiResponseEnvelope): Schema<Any> =
        when (envelope.type) {
            ApiEnvelopeType.BASIC -> basicSchema()
            ApiEnvelopeType.VALUE -> dataSchema(payloadRef(envelope))
            ApiEnvelopeType.LIST -> listSchema(payloadRef(envelope))
            ApiEnvelopeType.PAGE -> pageSchema(payloadRef(envelope))
            ApiEnvelopeType.CURSOR -> cursorSchema(payloadRef(envelope))
        }

    private fun basicSchema(): Schema<Any> =
        ObjectSchema()
            .description("Standard successful response without a payload.")
            .addProperty("meta", ref("ResponseMeta"))
            .required(listOf("meta"))

    private fun dataSchema(valueSchema: Schema<Any>): Schema<Any> =
        ObjectSchema()
            .description("Standard successful response with one payload value.")
            .addProperty("value", valueSchema)
            .addProperty("meta", ref("ResponseMeta"))
            .required(listOf("value", "meta"))

    private fun listSchema(itemSchema: Schema<Any>): Schema<Any> =
        ObjectSchema()
            .description("Standard successful response with a list payload.")
            .addProperty("values", ArraySchema().items(itemSchema))
            .addProperty("meta", ref("ResponseMeta"))
            .required(listOf("values", "meta"))

    private fun pageSchema(itemSchema: Schema<Any>): Schema<Any> =
        ObjectSchema()
            .description("Standard successful response with an offset page payload.")
            .addProperty("values", ArraySchema().items(itemSchema))
            .addProperty("pagination", ref("PaginationMeta"))
            .addProperty("meta", ref("ResponseMeta"))
            .required(listOf("values", "pagination", "meta"))

    private fun cursorSchema(itemSchema: Schema<Any>): Schema<Any> =
        ObjectSchema()
            .description("Standard successful response with a cursor page payload.")
            .addProperty("values", ArraySchema().items(itemSchema))
            .addProperty("cursor", ref("CursorMeta"))
            .addProperty("meta", ref("ResponseMeta"))
            .required(listOf("values", "cursor", "meta"))

    private fun cursorMetaSchema(): Schema<Any> =
        ObjectSchema()
            .description("Cursor pagination metadata.")
            .addProperty("nextCursor", io.swagger.v3.oas.models.media.StringSchema().nullable(true))
            .addProperty("hasNext", io.swagger.v3.oas.models.media.BooleanSchema())
            .required(listOf("hasNext"))

    private fun payloadRef(envelope: ResolvedApiResponseEnvelope): Schema<Any> =
        envelope.payloadClass
            ?.let { ref(it.simpleName) }
            ?: error("Payload class is required for ${envelope.type}")

    private fun ref(name: String): Schema<Any> =
        Schema<Any>().`$ref`("#/components/schemas/$name")
}
