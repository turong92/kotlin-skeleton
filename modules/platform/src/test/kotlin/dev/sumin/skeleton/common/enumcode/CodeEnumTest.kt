package dev.sumin.skeleton.common.enumcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

class CodeEnumTest {
    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .addModule(CodeEnumJacksonModule())
        .build()

    @Test
    fun `resolves int and string code enums from wire values`() {
        assertEquals(SampleStatus.PAID, CodeEnumResolver.fromRaw(SampleStatus::class, 20))
        assertEquals(SampleStatus.PAID, CodeEnumResolver.fromRaw(SampleStatus::class, "20"))
        assertEquals(SampleTone.CALM, CodeEnumResolver.fromRaw(SampleTone::class, "calm"))
    }

    @Test
    fun `rejects unknown and duplicate codes with useful messages`() {
        val unknown = assertFailsWith<CodeEnumException> {
            CodeEnumResolver.fromRaw(SampleStatus::class, 999)
        }
        assertEquals("Unknown code '999' for enum SampleStatus.", unknown.message)

        val duplicate = assertFailsWith<CodeEnumException> {
            CodeEnumResolver.descriptors(DuplicateStatus::class)
        }
        assertEquals("Duplicate code '1' in enum DuplicateStatus.", duplicate.message)
    }

    @Test
    fun `describes enum constants for response dto and swagger`() {
        assertEquals(
            listOf<CodeEnumDescriptor<Any>>(
                CodeEnumDescriptor(code = 10, name = "CREATED", label = "Created", description = "Created but unpaid."),
                CodeEnumDescriptor(code = 20, name = "PAID", label = "Paid", description = null),
            ),
            CodeEnumResolver.descriptors(SampleStatus::class),
        )
        assertEquals("20(PAID)", SampleStatus.PAID.codeName)
    }

    @Test
    fun `serializes and deserializes code enums through shared jackson module`() {
        val json = mapper.writeValueAsString(SampleRequest(SampleStatus.PAID, SampleTone.CALM))

        assertEquals("""{"status":20,"tone":"calm"}""", json)
        assertEquals(
            SampleRequest(SampleStatus.PAID, SampleTone.CALM),
            mapper.readValue<SampleRequest>("""{"status":"20","tone":"calm"}"""),
        )
    }

    data class SampleRequest(
        val status: SampleStatus,
        val tone: SampleTone,
    )

    enum class SampleStatus(
        override val code: Int,
        override val label: String,
        override val description: String? = null,
    ) : IntCodeEnum {
        CREATED(10, "Created", "Created but unpaid."),
        PAID(20, "Paid"),
    }

    enum class SampleTone(
        override val code: String,
        override val label: String,
    ) : StringCodeEnum {
        CALM("calm", "Calm"),
        FAST("fast", "Fast"),
    }

    enum class DuplicateStatus(
        override val code: Int,
        override val label: String,
    ) : IntCodeEnum {
        FIRST(1, "First"),
        ALSO_FIRST(1, "Also first"),
    }
}
