package dev.sumin.skeleton.json

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPayloadRegistryTest {
    private val codec = JacksonJsonCodec()

    @Test
    fun `registry migrates versioned json document to latest dto`() {
        val registry = JsonPayloadRegistry(
            codec = codec,
            definitions = listOf(PaymentResultDefinition),
            migrators = listOf(PaymentResultV1ToV2Migrator(codec)),
        )
        val saved = VersionedJsonDocument(
            type = "payment.provider-result",
            version = 1,
            payload = codec.toDocument(PaymentResultV1(paymentKey = "pay_1", amount = 1000)),
        )

        val latest = registry.readLatest(saved, PaymentResultV2::class)

        assertEquals(PaymentResultV2(paymentKey = "pay_1", amount = 1000, currency = "KRW"), latest)
    }

    @Test
    fun `registry writes dto with current version envelope`() {
        val registry = JsonPayloadRegistry(
            codec = codec,
            definitions = listOf(PaymentResultDefinition),
            migrators = emptyList(),
        )

        val document = registry.writeLatest(
            type = "payment.provider-result",
            payload = PaymentResultV2(paymentKey = "pay_1", amount = 1000, currency = "USD"),
        )

        assertEquals("payment.provider-result", document.type)
        assertEquals(2, document.version)
        assertEquals("USD", document.payload.textAt("/currency"))
    }

    data class PaymentResultV1(
        val paymentKey: String,
        val amount: Long,
    )

    data class PaymentResultV2(
        val paymentKey: String,
        val amount: Long,
        val currency: String,
    )

    object PaymentResultDefinition : JsonPayloadDefinition<PaymentResultV2> {
        override val type: String = "payment.provider-result"
        override val currentVersion: Int = 2
        override val payloadClass: KClass<PaymentResultV2> = PaymentResultV2::class
    }

    class PaymentResultV1ToV2Migrator(
        private val codec: JsonCodec,
    ) : JsonPayloadMigrator {
        override val type: String = "payment.provider-result"
        override val fromVersion: Int = 1
        override val toVersion: Int = 2

        override fun migrate(payload: JsonDocument): JsonDocument {
            val v1 = codec.fromDocument(payload, PaymentResultV1::class)
            return codec.toDocument(
                PaymentResultV2(
                    paymentKey = v1.paymentKey,
                    amount = v1.amount,
                    currency = "KRW",
                ),
            )
        }
    }
}
