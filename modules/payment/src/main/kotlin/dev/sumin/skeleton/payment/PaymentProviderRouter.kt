package dev.sumin.skeleton.payment

class PaymentProviderRouter(
    providers: List<PaymentProvider>,
    private val properties: PaymentProperties,
) {
    private val providersById = providers.associateByUniqueProviderId()

    fun resolve(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
    ): PaymentProvider {
        provider.normalizeProviderId()?.let { explicit ->
            return enabledProvider(explicit) ?: throw PaymentProviderNotFoundException(explicit)
        }

        amount?.normalizedCurrency?.let { currency ->
            findSingle("currency $currency") { route ->
                route.currencies.contains(currency)
            }?.let { return it }
        }

        country.normalizeCountry()?.let { normalizedCountry ->
            findSingle("country $normalizedCountry") { route ->
                route.countries.contains(normalizedCountry)
            }?.let { return it }
        }

        properties.defaultProvider.normalizeProviderId()?.let { defaultProvider ->
            return enabledProvider(defaultProvider) ?: throw PaymentProviderNotFoundException(defaultProvider)
        }

        if (providersById.size == 1) {
            return providersById.values.single()
        }

        throw PaymentRoutingException("No payment provider route matched the request.")
    }

    private fun enabledProvider(providerId: String): PaymentProvider? {
        val provider = providersById[providerId] ?: return null
        val route = provider.route()
        return provider.takeIf { route.enabled }
    }

    private fun findSingle(
        reason: String,
        matches: (EffectiveRoute) -> Boolean,
    ): PaymentProvider? {
        val matched = providersById.values
            .filter { provider -> provider.route().enabled }
            .filter { provider -> matches(provider.route()) }

        if (matched.size > 1) {
            throw PaymentRoutingException("Multiple payment providers match $reason.")
        }

        return matched.singleOrNull()
    }

    private fun PaymentProvider.route(): EffectiveRoute {
        val configured = properties.providers[providerId.normalizeProviderId().orEmpty()]
        return EffectiveRoute(
            enabled = configured?.enabled ?: true,
            currencies = configured?.currencies?.normalizeUppercaseSet()
                ?: supportedCurrencies.normalizeUppercaseSet(),
            countries = configured?.countries?.normalizeUppercaseSet()
                ?: supportedCountries.normalizeUppercaseSet(),
        )
    }

    private fun List<PaymentProvider>.associateByUniqueProviderId(): Map<String, PaymentProvider> {
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
