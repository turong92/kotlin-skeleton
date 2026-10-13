package dev.sumin.skeleton.payment

import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * 결제사를 고른다. **후보는 늘 능력으로 먼저 거른 뒤** 통화 → 국가 → 기본 결제사 → 단일 결제사 순으로 라우팅한다(기본 결제사도 그 능력을 가졌을 때만 고른다) —
 * 능력만 구현한 게이트웨이(조회 전용 · 결제창 전용)가 있어도 다른 능력의 라우팅이 달라지지 않는다.
 *
 * - 단일 결제사 지름길(후보가 하나뿐일 때)은 `enabled=false` 를 보지 않는다. [gateway] 와 id 지정은 본다. 기존 동작이라 호환을 위해 그대로 둔다.
 * - id 를 지정하면 후보를 거르지 않고 그 결제사를 찾은 뒤 능력을 확인한다(없으면 `CAPABILITY_UNSUPPORTED`).
 */
class PaymentProviderRouter(
    providers: List<PaymentGateway>,
    private val properties: PaymentProperties,
) {
    private val providersById = providers.associateByUniqueProviderId()

    /** 이 라우터가 아는 결제사 id 전부(꺼진 것 포함) — 운영자의 결제 번호 검색이 `provider in (…)` 을 같이 걸어 유니크 인덱스를 타게 한다. */
    fun providerIds(): Set<String> = providersById.keys

    /** [capability] 를 가진 켜진 결제사의 id — 화면이 고를 수 있는 결제사 목록. */
    fun providerIds(capability: KClass<out PaymentGateway>): List<String> =
        providersById.filterValues { capability.isInstance(it) && it.route().enabled }.keys.sorted()

    fun resolve(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
    ): PaymentProvider = resolve(provider, amount, country, PaymentProvider::class)

    /**
     * 결제사 id 없이(또는 id 로) [capability] 를 가진 게이트웨이를 고른다 — 서버가 결제사를 고르는 흐름용.
     * 후보는 [capability] 를 구현한 게이트웨이뿐이다. 맞는 곳이 없으면 `PAYMENT.ROUTING_FAILED`, 여럿이면 `PAYMENT.ROUTING_FAILED`,
     * id 가 없거나 꺼져 있으면 `PAYMENT.PROVIDER_NOT_FOUND`, 그 결제사에 능력이 없으면 `PAYMENT.CAPABILITY_UNSUPPORTED`.
     */
    fun <T : PaymentGateway> resolve(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
        capability: KClass<T>,
    ): T {
        val chosen = resolveGateway(provider, amount, country, capability)
        return capability.safeCast(chosen)
            ?: throw PaymentCapabilityUnsupportedException(chosen.providerId.trim().lowercase(), capability.simpleName ?: "capability")
    }

    /** 그 결제사가 [capability] 를 구현하지 않으면 `PAYMENT.CAPABILITY_UNSUPPORTED`. */
    fun <T : PaymentGateway> gateway(
        providerId: String,
        capability: KClass<T>,
    ): T {
        val normalized = providerId.normalizeProviderId() ?: throw PaymentProviderNotFoundException(providerId)
        val found = enabledProvider(normalized) ?: throw PaymentProviderNotFoundException(normalized)
        return capability.safeCast(found)
            ?: throw PaymentCapabilityUnsupportedException(normalized, capability.simpleName ?: "capability")
    }

    private fun resolveGateway(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
        capability: KClass<out PaymentGateway>,
    ): PaymentGateway {
        provider.normalizeProviderId()?.let { explicit ->
            return enabledProvider(explicit) ?: throw PaymentProviderNotFoundException(explicit)
        }

        val candidates = providersById.values.filter { capability.isInstance(it) }

        amount?.normalizedCurrency?.let { currency ->
            findSingle(candidates, "currency $currency") { route ->
                route.currencies.contains(currency)
            }?.let { return it }
        }

        country.normalizeCountry()?.let { normalizedCountry ->
            findSingle(candidates, "country $normalizedCountry") { route ->
                route.countries.contains(normalizedCountry)
            }?.let { return it }
        }

        properties.defaultProvider.normalizeProviderId()?.let { defaultProvider ->
            // 기본 결제사도 후보(그 능력을 가진 것) 안에서만 고른다. 설정이 가리키는 결제사가 없거나 꺼져 있으면 설정 오류, 있는데 그 능력이 없으면 다음 단계로 넘긴다
            val found = enabledProvider(defaultProvider) ?: throw PaymentProviderNotFoundException(defaultProvider)
            if (capability.isInstance(found)) return found
        }

        if (candidates.size == 1) {
            return candidates.single()
        }

        throw PaymentRoutingException("No payment provider route matched the request.")
    }

    private fun enabledProvider(providerId: String): PaymentGateway? {
        val provider = providersById[providerId] ?: return null
        val route = provider.route()
        return provider.takeIf { route.enabled }
    }

    private fun findSingle(
        candidates: List<PaymentGateway>,
        reason: String,
        matches: (EffectiveRoute) -> Boolean,
    ): PaymentGateway? {
        val matched = candidates
            .filter { provider -> provider.route().enabled }
            .filter { provider -> matches(provider.route()) }

        if (matched.size > 1) {
            throw PaymentRoutingException("Multiple payment providers match $reason.")
        }

        return matched.singleOrNull()
    }

    private fun PaymentGateway.route(): EffectiveRoute {
        val configured = properties.providers[providerId.normalizeProviderId().orEmpty()]
        return EffectiveRoute(
            enabled = configured?.enabled ?: true,
            currencies = configured?.currencies?.normalizeUppercaseSet()
                ?: supportedCurrencies.normalizeUppercaseSet(),
            countries = configured?.countries?.normalizeUppercaseSet()
                ?: supportedCountries.normalizeUppercaseSet(),
        )
    }

    private fun List<PaymentGateway>.associateByUniqueProviderId(): Map<String, PaymentGateway> {
        val grouped = groupBy { provider ->
            requireNotNull(provider.providerId.normalizeProviderId()) {
                "Payment provider id must not be blank"
            }
        }
        val duplicate = grouped.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Duplicate payment provider id '${duplicate!!.key}'"
        }
        return grouped.mapValues { it.value.single() }
    }

    private fun Set<String>.normalizeUppercaseSet(): Set<String> =
        mapNotNull { value -> value.trim().takeIf { it.isNotBlank() }?.uppercase() }.toSet()

    private fun String?.normalizeProviderId(): String? =
        this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun String?.normalizeCountry(): String? =
        this?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

    private data class EffectiveRoute(
        val enabled: Boolean,
        val currencies: Set<String>,
        val countries: Set<String>,
    )
}
