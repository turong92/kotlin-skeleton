package dev.sumin.skeleton.common.enumcode

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.PathParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerMethod

class CodeEnumOpenApiCustomizerTest {
    @Test
    fun `mutates code enum properties in every allOf child schema`() {
        val statusSchema = StringSchema()._enum(listOf("CREATED", "PAID"))
        val schema = ObjectSchema().apply {
            allOf = listOf(
                ObjectSchema().addProperty("other", StringSchema()),
                ObjectSchema().addProperty("status", statusSchema),
            )
        }
        val docs = OpenAPI().components(
            Components().addSchemas("SampleDto", schema),
        )

        controllerCustomizer().customise(docs)

        assertEquals("integer", statusSchema.type)
        assertEquals("int32", statusSchema.format)
        assertEquals(listOf<Any>(10, 20), statusSchema.enum)
        assertTrue(statusSchema.description.orEmpty().contains("{code: 10, name: CREATED, label: Created}"))
    }

    @Test
    fun `operation parameter mutation uses actual handler method parameter type`() {
        val operation = Operation().addParametersItem(
            PathParameter()
                .name("tone")
                .schema(StringSchema()._enum(listOf("calm", "loud"))),
        )
        val handlerMethod = HandlerMethod(SampleController(), method("tone"))

        emptyCustomizer().customiseOperation(operation, handlerMethod)

        val schema = assertNotNull(operation.parameters.single().schema)
        assertEquals("string", schema.type)
        assertEquals(listOf("calm", "loud"), schema.enum)
        assertTrue(schema.description.orEmpty().contains("{code: calm, name: CALM, label: Calm, description: Low urgency.}"))
    }

    private fun controllerCustomizer(): CodeEnumOpenApiCustomizer {
        val context = GenericApplicationContext()
        context.registerBeanDefinition("sampleController", RootBeanDefinition(SampleController::class.java))
        context.refresh()
        return CodeEnumOpenApiCustomizer(context)
    }

    private fun emptyCustomizer(): CodeEnumOpenApiCustomizer {
        val context = GenericApplicationContext()
        context.refresh()
        return CodeEnumOpenApiCustomizer(context)
    }

    private fun method(name: String) =
        SampleController::class.java.declaredMethods.single { it.name == name }

    data class SampleDto(
        val status: SampleStatus,
    )

    enum class SampleStatus(
        override val code: Int,
        override val label: String,
    ) : IntCodeEnum {
        CREATED(10, "Created"),
        PAID(20, "Paid"),
    }

    enum class SampleTone(
        override val code: String,
        override val label: String,
        override val description: String? = null,
    ) : StringCodeEnum {
        CALM("calm", "Calm", "Low urgency."),
        LOUD("loud", "Loud"),
    }

    @Suppress("unused")
    @RestController
    private class SampleController {
        @GetMapping("/samples")
        fun sample(): SampleDto = error("not called")

        @GetMapping("/tones/{tone}")
        fun tone(
            @PathVariable("tone") tone: SampleTone,
        ): SampleDto = error("not called")
    }
}
