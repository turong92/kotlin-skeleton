package dev.sumin.skeleton.event.kafka

import dev.sumin.skeleton.json.JacksonJsonCodec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KafkaEventPayloadSerializerTest {
    private val serializer = KafkaEventPayloadSerializer(JacksonJsonCodec())

    @Test
    fun `serializes event payload with the shared json canonical form`() {
        val bytes = serializer.serialize(
            mapOf(
                "z" to 3,
                "a" to mapOf("b" to true, "a" to "first"),
            ),
        )

        assertThat(String(bytes)).isEqualTo("""{"a":{"a":"first","b":true},"z":3}""")
    }

    @Test
    fun `serializes null payload as json null`() {
        val bytes = serializer.serialize(null)

        assertThat(String(bytes)).isEqualTo("null")
    }
}
