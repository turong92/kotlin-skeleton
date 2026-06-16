package dev.sumin.skeleton.common.openapi

import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageResponse
import io.swagger.v3.oas.models.media.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.ResponseEntity

class ApiResponseEnvelopeSupportTest {
    @Test
    fun `infers direct skeleton response wrappers`() {
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.BASIC, null),
            ApiResponseEnvelopeResolver.resolve(method("basic")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("value")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.LIST, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("list")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.PAGE, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("page")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.CURSOR, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("cursor")),
        )
    }

    @Test
    fun `infers response entity wrapped skeleton responses`() {
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.BASIC, null),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityBasic")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityValue")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.LIST, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityList")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.PAGE, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityPage")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.CURSOR, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityCursor")),
        )
    }

    @Test
    fun `annotation override wins when return type is ambiguous`() {
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.BASIC, null),
            ApiResponseEnvelopeResolver.resolve(method("annotatedBasicWithValue")),
        )
        assertEquals(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.LIST, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("annotatedAmbiguous")),
        )
    }

    @Test
    fun `invalid annotation without payload fails fast`() {
        val exception = assertFailsWith<IllegalStateException> {
            ApiResponseEnvelopeResolver.resolve(method("invalidAnnotation"))
        }

        assertTrue(exception.message.orEmpty().contains("invalidAnnotation"))
        assertTrue(exception.message.orEmpty().contains("VALUE"))
    }

    @Test
    fun `returns null for non envelope return types`() {
        assertNull(ApiResponseEnvelopeResolver.resolve(method("plain")))
    }

    @Test
    fun `returns null for unsupported nested generic payloads`() {
        assertNull(ApiResponseEnvelopeResolver.resolve(method("nestedGenericValue")))
    }

    @Test
    fun `creates operation schemas for all envelope types`() {
        val basicSchema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.BASIC, null),
        )
        assertSchemaShape(basicSchema, "meta")
        assertRefProperty(basicSchema, "meta", "ResponseMeta")

        val valueSchema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, SamplePayload::class.java),
        )
        assertSchemaShape(valueSchema, "value", "meta")
        assertRefProperty(valueSchema, "value", "SamplePayload")
        assertRefProperty(valueSchema, "meta", "ResponseMeta")

        val listSchema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.LIST, SamplePayload::class.java),
        )
        assertSchemaShape(listSchema, "values", "meta")
        assertArrayItemRefProperty(listSchema, "values", "SamplePayload")
        assertRefProperty(listSchema, "meta", "ResponseMeta")

        val pageSchema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.PAGE, SamplePayload::class.java),
        )
        assertSchemaShape(pageSchema, "values", "pagination", "meta")
        assertArrayItemRefProperty(pageSchema, "values", "SamplePayload")
        assertRefProperty(pageSchema, "pagination", "PaginationMeta")
        assertRefProperty(pageSchema, "meta", "ResponseMeta")

        val cursorSchema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.CURSOR, SamplePayload::class.java),
        )
        assertSchemaShape(cursorSchema, "values", "cursor", "meta")
        assertArrayItemRefProperty(cursorSchema, "values", "SamplePayload")
        assertRefProperty(cursorSchema, "cursor", "CursorMeta")
        assertRefProperty(cursorSchema, "meta", "ResponseMeta")
    }

    @Test
    fun `uses generated response schema payload ref when available`() {
        val schema = ApiResponseEnvelopeSchemas.operationSchema(
            envelope = ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, SamplePayload::class.java),
            generatedResponseSchema = Schema<Any>().`$ref`("#/components/schemas/DataResponseCustomPayload"),
        )

        assertRefProperty(schema, "value", "CustomPayload")
        assertRefProperty(schema, "meta", "ResponseMeta")
    }

    @Test
    fun `registers stable component schema shapes`() {
        val schemas = ApiResponseEnvelopeSchemas.componentSchemas()

        assertEquals(
            setOf("BasicResponse", "DataResponse", "ListResponse", "PageResponse", "CursorResponse", "CursorMeta"),
            schemas.keys,
        )

        val basicSchema = assertNotNull(schemas["BasicResponse"])
        assertSchemaShape(basicSchema, "meta")
        assertRefProperty(basicSchema, "meta", "ResponseMeta")

        val dataSchema = assertNotNull(schemas["DataResponse"])
        assertSchemaShape(dataSchema, "value", "meta")
        assertEquals("Payload value.", property(dataSchema, "value").description)
        assertRefProperty(dataSchema, "meta", "ResponseMeta")

        val listSchema = assertNotNull(schemas["ListResponse"])
        assertSchemaShape(listSchema, "values", "meta")
        assertArrayItemDescription(listSchema, "values", "Payload item.")
        assertRefProperty(listSchema, "meta", "ResponseMeta")

        val pageSchema = assertNotNull(schemas["PageResponse"])
        assertSchemaShape(pageSchema, "values", "pagination", "meta")
        assertArrayItemDescription(pageSchema, "values", "Payload item.")
        assertRefProperty(pageSchema, "pagination", "PaginationMeta")
        assertRefProperty(pageSchema, "meta", "ResponseMeta")

        val cursorSchema = assertNotNull(schemas["CursorResponse"])
        assertSchemaShape(cursorSchema, "values", "cursor", "meta")
        assertArrayItemDescription(cursorSchema, "values", "Payload item.")
        assertRefProperty(cursorSchema, "cursor", "CursorMeta")
        assertRefProperty(cursorSchema, "meta", "ResponseMeta")

        val cursorMetaSchema = assertNotNull(schemas["CursorMeta"])
        assertSchemaShape(cursorMetaSchema, "nextCursor", "hasNext", requiredProperties = listOf("hasNext"))
        assertEquals("string", property(cursorMetaSchema, "nextCursor").type)
        assertEquals(true, property(cursorMetaSchema, "nextCursor").nullable)
        assertEquals("boolean", property(cursorMetaSchema, "hasNext").type)
    }

    private fun method(name: String) =
        SampleController::class.java.declaredMethods.single { it.name == name }

    private fun assertSchemaShape(
        schema: Schema<*>,
        vararg propertyNames: String,
        requiredProperties: List<String> = propertyNames.toList(),
    ) {
        assertEquals(propertyNames.toSet(), schema.properties.orEmpty().keys.toSet())
        assertEquals(requiredProperties, schema.required.orEmpty())
    }

    private fun assertRefProperty(
        schema: Schema<*>,
        propertyName: String,
        componentName: String,
    ) {
        assertEquals("#/components/schemas/$componentName", property(schema, propertyName).`$ref`)
    }

    private fun assertArrayItemRefProperty(
        schema: Schema<*>,
        propertyName: String,
        componentName: String,
    ) {
        assertEquals("#/components/schemas/$componentName", property(schema, propertyName).items?.`$ref`)
    }

    private fun assertArrayItemDescription(
        schema: Schema<*>,
        propertyName: String,
        description: String,
    ) {
        assertEquals(description, property(schema, propertyName).items?.description)
    }

    private fun property(
        schema: Schema<*>,
        propertyName: String,
    ): Schema<*> =
        assertNotNull(schema.properties.orEmpty()[propertyName], "Expected property $propertyName")

    data class SamplePayload(val id: String)

    @Suppress("unused")
    private class SampleController {
        fun basic(): BasicResponse = BasicResponse()
        fun value(): DataResponse<SamplePayload> = error("not called")
        fun list(): ListResponse<SamplePayload> = error("not called")
        fun page(): PageResponse<SamplePayload> = error("not called")
        fun cursor(): CursorResponse<SamplePayload> = error("not called")
        fun nestedGenericValue(): DataResponse<List<SamplePayload>> = error("not called")
        fun responseEntityBasic(): ResponseEntity<BasicResponse> = error("not called")
        fun responseEntityValue(): ResponseEntity<DataResponse<SamplePayload>> = error("not called")
        fun responseEntityList(): ResponseEntity<ListResponse<SamplePayload>> = error("not called")
        fun responseEntityPage(): ResponseEntity<PageResponse<SamplePayload>> = error("not called")
        fun responseEntityCursor(): ResponseEntity<CursorResponse<SamplePayload>> = error("not called")

        @ApiResponseEnvelope(type = ApiEnvelopeType.BASIC, value = SamplePayload::class)
        fun annotatedBasicWithValue(): Any = error("not called")

        @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = SamplePayload::class)
        fun annotatedAmbiguous(): Any = error("not called")

        @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE)
        fun invalidAnnotation(): Any = error("not called")

        fun plain(): SamplePayload = error("not called")
    }
}
