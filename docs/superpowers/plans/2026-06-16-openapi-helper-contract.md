# OpenAPI Helper Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make generated OpenAPI describe skeleton response envelopes and `CodeEnum` wire-code schemas predictably, with one annotation escape hatch for ambiguous return types.

**Architecture:** Keep all reusable OpenAPI logic in `modules:platform`. Add a small response-envelope resolver/schema builder that is invoked from the existing `PlatformOpenApiAutoConfiguration`. Keep `CodeEnum` documentation in the existing enum-code package and verify generated JSON through `apps:api`.

**Tech Stack:** Kotlin, Spring Boot 4 auto-configuration, Springdoc OpenAPI 3, Swagger Core model classes, MockMvc JSON integration tests, JsonPath.

---

## File Structure

- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelope.kt`: one public annotation and envelope type enum.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupport.kt`: reflection resolver and schema factory for response envelopes.
- Modify `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt`: register envelope component schemas and apply operation response schemas.
- Create `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupportTest.kt`: resolver/schema unit tests.
- Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/OperationExampleController.kt`: add cursor and annotation-override sample endpoints.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`: assert generated response envelope shape.
- Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonCodeEnumController.kt`: add a string-code path parameter example.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/CodeEnumContractIntegrationTest.kt`: assert DTO, query, and path enum code schemas.
- Modify `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/enumcode/CodeEnumOpenApiCustomizer.kt`: make enum discovery and parameter schema mutation robust.
- Modify `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`: mark OpenAPI helper item complete.
- Create or update `docs/openapi.md`: document envelope inference, `@ApiResponseEnvelope`, and `CodeEnum`.

---

### Task 1: Platform Response Envelope Core

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelope.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupport.kt`
- Test: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupportTest.kt`

- [ ] **Step 1: Write failing resolver and schema tests**

Create `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupportTest.kt`:

```kotlin
package dev.sumin.skeleton.common.openapi

import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            ResolvedApiResponseEnvelope(ApiEnvelopeType.VALUE, SamplePayload::class.java),
            ApiResponseEnvelopeResolver.resolve(method("responseEntityValue")),
        )
    }

    @Test
    fun `annotation override wins when return type is ambiguous`() {
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
    fun `creates operation schema for page envelope`() {
        val schema = ApiResponseEnvelopeSchemas.operationSchema(
            ResolvedApiResponseEnvelope(ApiEnvelopeType.PAGE, SamplePayload::class.java),
        )

        val properties = schema.properties.orEmpty()
        assertTrue(properties.containsKey("values"))
        assertTrue(properties.containsKey("pagination"))
        assertTrue(properties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/SamplePayload",
            properties["values"]?.items?.`$ref`,
        )
        assertEquals(
            "#/components/schemas/PaginationMeta",
            properties["pagination"]?.`$ref`,
        )
        assertEquals(
            "#/components/schemas/ResponseMeta",
            properties["meta"]?.`$ref`,
        )
    }

    @Test
    fun `registers stable component schemas`() {
        val schemas = ApiResponseEnvelopeSchemas.componentSchemas()

        assertTrue(schemas.containsKey("BasicResponse"))
        assertTrue(schemas.containsKey("DataResponse"))
        assertTrue(schemas.containsKey("ListResponse"))
        assertTrue(schemas.containsKey("PageResponse"))
        assertTrue(schemas.containsKey("CursorResponse"))
        assertTrue(schemas.containsKey("CursorMeta"))
    }

    private fun method(name: String) =
        SampleController::class.java.declaredMethods.single { it.name == name }

    data class SamplePayload(val id: String)

    @Suppress("unused")
    private class SampleController {
        fun basic(): BasicResponse = BasicResponse()
        fun value(): DataResponse<SamplePayload> = error("not called")
        fun list(): ListResponse<SamplePayload> = error("not called")
        fun page(): PageResponse<SamplePayload> = error("not called")
        fun cursor(): CursorResponse<SamplePayload> = error("not called")
        fun responseEntityValue(): ResponseEntity<DataResponse<SamplePayload>> = error("not called")

        @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = SamplePayload::class)
        fun annotatedAmbiguous(): Any = error("not called")

        @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE)
        fun invalidAnnotation(): Any = error("not called")

        fun plain(): SamplePayload = error("not called")
    }
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ApiResponseEnvelopeSupportTest*'
```

Expected: FAIL with unresolved references such as `ApiResponseEnvelope`, `ApiEnvelopeType`, `ApiResponseEnvelopeResolver`, `ResolvedApiResponseEnvelope`, and `ApiResponseEnvelopeSchemas`.

- [ ] **Step 3: Add the annotation file**

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelope.kt`:

```kotlin
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
```

- [ ] **Step 4: Add resolver and schema support**

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupport.kt`:

```kotlin
package dev.sumin.skeleton.common.openapi

import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageResponse
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import org.springframework.http.ResponseEntity

data class ResolvedApiResponseEnvelope(
    val type: ApiEnvelopeType,
    val payloadClass: Class<*>?,
)

object ApiResponseEnvelopeResolver {
    fun resolve(method: Method): ResolvedApiResponseEnvelope? {
        method.getAnnotation(ApiResponseEnvelope::class.java)?.let { annotation ->
            val payloadClass = annotation.value.java.takeUnless { it == Unit::class.java }
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

    private fun firstTypeArgumentClass(type: Type): Class<*>? =
        (type as? ParameterizedType)
            ?.actualTypeArguments
            ?.firstOrNull()
            ?.let { rawClass(it) }
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
            .addProperty("nextCursor", io.swagger.v3.oas.models.media.StringSchema())
            .addProperty("hasNext", io.swagger.v3.oas.models.media.BooleanSchema())
            .required(listOf("hasNext"))

    private fun payloadRef(envelope: ResolvedApiResponseEnvelope): Schema<Any> =
        envelope.payloadClass
            ?.let { ref(it.simpleName) }
            ?: error("Payload class is required for ${envelope.type}")

    private fun ref(name: String): Schema<Any> =
        Schema<Any>().`$ref`("#/components/schemas/$name")
}
```

- [ ] **Step 5: Run platform test to verify GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ApiResponseEnvelopeSupportTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit platform response envelope core**

```bash
git add modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelope.kt \
  modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupport.kt \
  modules/platform/src/test/kotlin/dev/sumin/skeleton/common/openapi/ApiResponseEnvelopeSupportTest.kt
git commit -m "feat: add openapi response envelope support"
```

---

### Task 2: Wire Envelope Schemas Into Generated OpenAPI

**Files:**
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt`
- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/OperationExampleController.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`

- [ ] **Step 1: Add failing app OpenAPI assertions**

Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`.

Add these helper functions inside the test class:

```kotlin
    private fun responseSchema(
        docs: String,
        path: String,
        method: String,
        code: String,
    ): Map<String, Any?> {
        val schema = JsonPath.read<Map<String, Any?>>(docs, "$.paths['$path'].$method.responses['$code'].content['application/json'].schema")
        val ref = schema["\$ref"] as? String
        return if (ref == null) {
            schema
        } else {
            val schemaName = ref.substringAfterLast("/")
            JsonPath.read(docs, "$.components.schemas.$schemaName")
        }
    }

    private fun properties(schema: Map<String, Any?>): Map<String, Any?> =
        schema["properties"] as? Map<String, Any?>
            ?: error("Schema has no properties: $schema")
```

Append these assertions near the existing response-schema assertions in `OpenAPI docs are composed from platform and auth modules`:

```kotlin
        val helloEnvelope = responseSchema(docs, "/api/v1/hello", "get", "200")
        val helloEnvelopeProperties = properties(helloEnvelope)
        assertTrue(helloEnvelopeProperties.containsKey("value"))
        assertTrue(helloEnvelopeProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/HelloResponse",
            (helloEnvelopeProperties["value"] as Map<*, *>)["\$ref"],
        )

        val itemPageEnvelope = responseSchema(docs, "/api/v1/examples/items", "get", "200")
        val itemPageProperties = properties(itemPageEnvelope)
        assertTrue(itemPageProperties.containsKey("values"))
        assertTrue(itemPageProperties.containsKey("pagination"))
        assertTrue(itemPageProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/ExampleItemResponse",
            ((itemPageProperties["values"] as Map<*, *>)["items"] as Map<*, *>)["\$ref"],
        )
        assertEquals(
            "#/components/schemas/PaginationMeta",
            (itemPageProperties["pagination"] as Map<*, *>)["\$ref"],
        )

        val cursorEnvelope = responseSchema(docs, "/api/v1/examples/items/cursor", "get", "200")
        val cursorProperties = properties(cursorEnvelope)
        assertTrue(cursorProperties.containsKey("values"))
        assertTrue(cursorProperties.containsKey("cursor"))
        assertTrue(cursorProperties.containsKey("meta"))
        assertEquals(
            "#/components/schemas/CursorMeta",
            (cursorProperties["cursor"] as Map<*, *>)["\$ref"],
        )

        val annotatedEnvelope = responseSchema(docs, "/api/v1/examples/items/annotated", "get", "200")
        val annotatedProperties = properties(annotatedEnvelope)
        assertTrue(annotatedProperties.containsKey("values"))
        assertEquals(
            "#/components/schemas/ExampleItemResponse",
            ((annotatedProperties["values"] as Map<*, *>)["items"] as Map<*, *>)["\$ref"],
        )
```

Also assert the new component schema exists:

```kotlin
        assertEquals("object", JsonPath.read(docs, "$.components.schemas.CursorMeta.type"))
```

- [ ] **Step 2: Add sample cursor and annotated endpoints**

Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/OperationExampleController.kt`.

Add imports:

```kotlin
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
```

Add these methods before `private fun exampleItems()`:

```kotlin
    @GetMapping("/items/cursor")
    fun cursorItems(): CursorResponse<ExampleItemResponse> =
        Response.cursor(
            values = exampleItems().take(2),
            nextCursor = "item-3",
        )

    @GetMapping("/items/annotated")
    @ApiResponseEnvelope(
        type = ApiEnvelopeType.LIST,
        value = ExampleItemResponse::class,
    )
fun annotatedItems(): Any =
        Response.ok(values = exampleItems().take(2))
```

- [ ] **Step 3: Run app OpenAPI test to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*OpenApiDocumentationIntegrationTest*'
```

Expected: FAIL because the generated schemas are still Springdoc defaults and do not expose the expected operation-local envelope shape.

- [ ] **Step 4: Register component schemas and apply operation response schemas**

Modify `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt`.

In `platformOpenApiCustomizer`, after existing `components.addSchemas(...)` calls, add:

```kotlin
            ApiResponseEnvelopeSchemas.componentSchemas().forEach { (name, schema) ->
                components.addSchemas(name, schema)
            }
```

In `standardOperationCustomizer`, after `operation.applyStandardOperationResponse(handlerMethod)` and before `operation.normalizeJsonResponseContent()`, add:

```kotlin
            operation.applyResponseEnvelope(handlerMethod)
```

Add this private method inside `PlatformOpenApiAutoConfiguration`:

```kotlin
    private fun Operation.applyResponseEnvelope(handlerMethod: HandlerMethod) {
        val envelope = ApiResponseEnvelopeResolver.resolve(handlerMethod.method) ?: return
        if (handlerMethod.hasMethodAnnotation(NoContentOperation::class.java)) {
            return
        }
        val response = successResponse() ?: return
        val content = response.content ?: Content().also { response.content = it }
        content.addMediaType(
            "application/json",
            MediaType().schema(ApiResponseEnvelopeSchemas.operationSchema(envelope)),
        )
        content.remove("*/*")
    }

    private fun Operation.successResponse(): ApiResponse? {
        val currentResponses = responses ?: return null
        return listOf("200", "201", "202")
            .firstNotNullOfOrNull { code -> currentResponses[code] }
            ?: currentResponses.entries.firstOrNull { (code, _) -> code.startsWith("2") }?.value
    }
```

Confirm `Content`, `MediaType`, and `ApiResponse` imports already exist in the file. They should already be present.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ApiResponseEnvelopeSupportTest*' :apps:api:test --tests '*OpenApiDocumentationIntegrationTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit OpenAPI envelope integration**

```bash
git add modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt \
  apps/api/src/main/kotlin/dev/sumin/skeleton/api/OperationExampleController.kt \
  apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt
git commit -m "feat: document response envelopes in openapi"
```

---

### Task 3: Harden CodeEnum OpenAPI Schemas

**Files:**
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/enumcode/CodeEnumOpenApiCustomizer.kt`
- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonCodeEnumController.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/CodeEnumContractIntegrationTest.kt`

- [ ] **Step 1: Add string-code path enum sample**

Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonCodeEnumController.kt`.

Add import:

```kotlin
import dev.sumin.skeleton.common.enumcode.StringCodeEnum
import org.springframework.web.bind.annotation.PathVariable
```

Add this enum after `SkeletonSampleStatus`:

```kotlin
enum class SkeletonSampleTone(
    override val code: String,
    override val label: String,
    override val description: String? = null,
) : StringCodeEnum {
    CALM("calm", "Calm", "Low urgency."),
    LOUD("loud", "Loud"),
}
```

Add this response DTO after `SkeletonCodeEnumResponse`:

```kotlin
data class SkeletonToneResponse(
    val tone: SkeletonSampleTone,
    val toneInfo: CodeEnumDescriptor<String>,
)
```

Add this endpoint inside `SkeletonCodeEnumController`:

```kotlin
    @GetMapping("/tone/{tone}")
    fun tone(
        @PathVariable tone: SkeletonSampleTone,
    ) = Response.ok(
        SkeletonToneResponse(
            tone = tone,
            toneInfo = tone.toDescriptor(),
        ),
    )
```

- [ ] **Step 2: Add failing runtime and OpenAPI assertions**

Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/CodeEnumContractIntegrationTest.kt`.

Add this runtime test:

```kotlin
    @Test
    fun `string code enum can be read from path and returned as code plus descriptor`() {
        val token = loginAccessToken()

        mockMvc.get("/api/v1/skeleton/enums/tone/calm") {
            header("Authorization", "Bearer $token")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.tone") { value("calm") }
            jsonPath("$.value.toneInfo.code") { value("calm") }
            jsonPath("$.value.toneInfo.name") { value("CALM") }
            jsonPath("$.value.toneInfo.label") { value("Calm") }
            jsonPath("$.value.toneInfo.description") { value("Low urgency.") }
        }
    }
```

Extend `OpenAPI describes code enum fields with code name and label` with these assertions:

```kotlin
        assertEquals(
            listOf(10, 20, 90),
            JsonPath.read(docs, "$.components.schemas.SkeletonCodeEnumRequest.properties.status.enum"),
        )
        assertEquals(
            listOf(10, 20, 90),
            JsonPath.read(docs, "$.components.schemas.SkeletonCodeEnumResponse.properties.status.enum"),
        )
        assertEquals(
            "string",
            JsonPath.read(docs, "$.components.schemas.SkeletonToneResponse.properties.tone.type"),
        )
        assertEquals(
            listOf("calm", "loud"),
            JsonPath.read(docs, "$.components.schemas.SkeletonToneResponse.properties.tone.enum"),
        )
        val toneDescription = JsonPath.read<String>(
            docs,
            "$.components.schemas.SkeletonToneResponse.properties.tone.description",
        )
        assertTrue(toneDescription.contains("{code: calm, name: CALM, label: Calm, description: Low urgency.}"))
        assertEquals(
            listOf("calm", "loud"),
            JsonPath.read(docs, "$.paths['/api/v1/skeleton/enums/tone/{tone}'].get.parameters[0].schema.enum"),
        )
```

- [ ] **Step 3: Run code enum tests to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*CodeEnumContractIntegrationTest*'
```

Expected: The runtime path test may pass because the converter already supports `StringCodeEnum`. OpenAPI assertions should fail if path parameter or response DTO code schemas still use enum names or miss descriptions.

- [ ] **Step 4: Harden controller class discovery and schema mutation**

Modify `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/enumcode/CodeEnumOpenApiCustomizer.kt`.

Add import:

```kotlin
import org.springframework.util.ClassUtils
```

Replace each use of:

```kotlin
val userClass = controller.javaClass
```

with:

```kotlin
val userClass = ClassUtils.getUserClass(controller)
```

Modify `applyMatchedCodeEnumSchema` so it can match both Springdoc enum-name schemas and already-code-like schemas:

```kotlin
    private fun applyMatchedCodeEnumSchema(
        schema: Schema<*>,
        codeEnumClasses: Collection<Class<*>>,
    ) {
        val currentValues = schema.getEnum()?.map { it.toString() } ?: return
        val matchedEnumClass = codeEnumClasses.firstOrNull { enumClass ->
            val descriptors = CodeEnumResolver.descriptors(enumClass)
            val names = enumClass.enumConstants.map { (it as Enum<*>).name }
            val codes = descriptors.map { it.code.toString() }
            currentValues == names || currentValues == codes
        } ?: return

        applyCodeEnumSchema(schema, matchedEnumClass)
    }
```

Replace `childProperties` with this implementation so response DTO schemas are handled whether Springdoc emits direct properties or an `allOf` wrapper:

```kotlin
    @Suppress("UNCHECKED_CAST")
    private fun childProperties(schema: Schema<*>): MutableMap<String, Schema<Any>>? {
        schema.properties?.let {
            return it as? MutableMap<String, Schema<Any>>
        }
        return schema.allOf
            ?.asSequence()
            ?.mapNotNull { it.properties as? MutableMap<String, Schema<Any>> }
            ?.firstOrNull()
    }
```

Keep `applyCodeEnumSchema` as the single place that sets type, format, enum values, and descriptions.

- [ ] **Step 5: Run code enum tests to verify GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*CodeEnumContractIntegrationTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit CodeEnum OpenAPI hardening**

```bash
git add modules/platform/src/main/kotlin/dev/sumin/skeleton/common/enumcode/CodeEnumOpenApiCustomizer.kt \
  apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonCodeEnumController.kt \
  apps/api/src/test/kotlin/dev/sumin/skeleton/api/CodeEnumContractIntegrationTest.kt
git commit -m "feat: harden code enum openapi schemas"
```

---

### Task 4: Documentation, Roadmap, And Final Verification

**Files:**
- Create or modify: `docs/openapi.md`
- Modify: `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`

- [ ] **Step 1: Add OpenAPI usage docs**

If `docs/openapi.md` does not exist, create it with this content. If it exists, merge these sections into the existing document without duplicating headings.

````markdown
# OpenAPI

`modules:platform` contributes the skeleton OpenAPI contract when Springdoc is
present. Applications usually only need the Springdoc UI dependency in the app
module.

## Response Envelopes

Return the skeleton response wrappers from controllers:

```kotlin
fun getOrder(): DataResponse<OrderResponse>
fun listOrders(): ListResponse<OrderResponse>
fun pageOrders(): PageResponse<OrderResponse>
fun cursorOrders(): CursorResponse<OrderResponse>
fun updateConsent(): BasicResponse
```

OpenAPI generation documents the runtime JSON shape:

- `BasicResponse`: `meta`
- `DataResponse<T>`: `value`, `meta`
- `ListResponse<T>`: `values`, `meta`
- `PageResponse<T>`: `values`, `pagination`, `meta`
- `CursorResponse<T>`: `values`, `cursor`, `meta`

For ambiguous signatures, use the single override annotation:

```kotlin
@ApiResponseEnvelope(
    type = ApiEnvelopeType.PAGE,
    value = OrderResponse::class,
)
fun searchOrders(): ResponseEntity<PageResponse<OrderResponse>>
```

Prefer normal wrapper return types first. Use `@ApiResponseEnvelope` only when
generic inference cannot describe the response.

## CodeEnum

Use `CodeEnum` when the public API should communicate stable wire codes instead
of enum names.

```kotlin
enum class OrderStatus(
    override val code: Int,
    override val label: String,
    override val description: String? = null,
) : IntCodeEnum {
    CREATED(10, "Created"),
    PAID(20, "Paid"),
}
```

OpenAPI documents the code values, type, and descriptions for request DTOs,
response DTOs, query parameters, and path parameters.
````

- [ ] **Step 2: Mark roadmap item complete**

In `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`, change:

```markdown
- [ ] Add OpenAPI helpers for enum descriptions and response wrapper schemas.
```

to:

```markdown
- [x] Add OpenAPI helpers for enum descriptions and response wrapper schemas.
```

- [ ] **Step 3: Run focused verification**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ApiResponseEnvelopeSupportTest*' :apps:api:test --tests '*OpenApiDocumentationIntegrationTest*' --tests '*CodeEnumContractIntegrationTest*'
```

Expected: PASS.

- [ ] **Step 4: Run full affected verification with sufficient heap**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem GRADLE_OPTS='-Xmx6g -Dorg.gradle.jvmargs=-Xmx6g -Dkotlin.daemon.jvmargs=-Xmx4g' ./gradlew :modules:platform:test :apps:api:test --rerun-tasks
```

Expected: PASS. Use the heap options because the app module compiles many capability modules and can otherwise hit Kotlin compiler memory pressure.

- [ ] **Step 5: Check docs and git state**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` has no output. `git status --short` shows only the documentation and roadmap files before commit.

- [ ] **Step 6: Commit docs and roadmap**

```bash
git add docs/openapi.md docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md
git commit -m "docs: document openapi helper contract"
```

---

## Final Self-Review Checklist

- Spec coverage:
  - response envelope inference: Task 1 and Task 2
  - one annotation override: Task 1 and Task 2
  - stable envelope schemas: Task 1 and Task 2
  - `CodeEnum` code-value schemas: Task 3
  - docs and roadmap: Task 4
- Placeholder scan: this plan contains none of the forbidden placeholder phrases from the planning skill.
- Type consistency:
  - Annotation: `ApiResponseEnvelope`
  - Enum: `ApiEnvelopeType`
  - Resolver result: `ResolvedApiResponseEnvelope`
  - Schema object: `ApiResponseEnvelopeSchemas`
  - Resolver object: `ApiResponseEnvelopeResolver`
