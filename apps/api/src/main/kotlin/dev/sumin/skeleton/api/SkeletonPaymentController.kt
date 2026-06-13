package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProvider
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentRoutingException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class SkeletonPaymentRouteResponse(
    val provider: String,
    val requestedProvider: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    val country: String? = null,
    val available: Boolean,
    val source: String,
)

@RestController
@RequestMapping("/api/v1/skeleton/payments")
class SkeletonPaymentController(
    private val paymentProviderRouter: PaymentProviderRouter,
    private val paymentProperties: PaymentProperties,
    paymentProviders: List<PaymentProvider>,
) {
    private val providerBeansById = paymentProviders.associateBy { it.providerId.normalizedProviderId() }

    @GetMapping("/route")
    fun route(
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) amount: Long?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) country: String?,
    ) = paymentAmount(amount, currency).let { paymentAmount ->
        val selection = resolveAvailableProvider(provider, paymentAmount, country)
            ?: resolveConfiguredProvider(provider, paymentAmount, country)
        Response.ok(
            SkeletonPaymentRouteResponse(
                provider = selection.provider,
                requestedProvider = provider?.trim()?.takeIf { it.isNotBlank() },
                amount = paymentAmount?.amount,
                currency = paymentAmount?.normalizedCurrency ?: currency.normalizedUppercase(),
                country = country.normalizedUppercase(),
                available = selection.available,
                source = selection.source,
            ),
        )
    }

    private fun resolveAvailableProvider(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
    ): RouteSelection? =
        try {
            paymentProviderRouter.resolve(
                provider = provider,
                amount = amount,
                country = country,
            ).let { selected ->
                RouteSelection(
                    provider = selected.providerId,
                    available = true,
                    source = "bean",
                )
            }
        } catch (_: PaymentProviderNotFoundException) {
            null
        } catch (_: PaymentRoutingException) {
            null
        }

    private fun resolveConfiguredProvider(
        provider: String?,
        amount: PaymentAmount?,
        country: String?,
    ): RouteSelection {
        val routes = paymentProperties.providers
            .mapKeys { (providerId, _) -> providerId.normalizedProviderId() }
            .filterKeys { it.isNotBlank() }

        provider.normalizedProviderId().takeIf { it.isNotBlank() }?.let { explicit ->
            val route = routes[explicit]
            if (route?.enabled == false) throw PaymentProviderNotFoundException(explicit)
            if (route != null || providerBeansById.containsKey(explicit)) return configuredSelection(explicit)
            throw PaymentProviderNotFoundException(explicit)
        }

        amount?.normalizedCurrency?.let { currency ->
            findConfiguredRoute("currency $currency", routes) { route ->
                route.currencies.normalizedUppercaseSet().contains(currency)
            }?.let { return configuredSelection(it) }
        }

        country.normalizedUppercase()?.let { normalizedCountry ->
            findConfiguredRoute("country $normalizedCountry", routes) { route ->
                route.countries.normalizedUppercaseSet().contains(normalizedCountry)
            }?.let { return configuredSelection(it) }
        }

        paymentProperties.defaultProvider.normalizedProviderId().takeIf { it.isNotBlank() }?.let { defaultProvider ->
            val route = routes[defaultProvider]
            if (route?.enabled == false) throw PaymentProviderNotFoundException(defaultProvider)
            if (route != null || providerBeansById.containsKey(defaultProvider)) return configuredSelection(defaultProvider)
        }

        throw PaymentRoutingException("No payment provider route matched the request.")
    }

    private fun findConfiguredRoute(
        reason: String,
        routes: Map<String, PaymentProperties.Provider>,
        matches: (PaymentProperties.Provider) -> Boolean,
    ): String? {
        val matched = routes
            .filterValues { route -> route.enabled }
            .filterValues(matches)
            .keys
            .toList()
        if (matched.size > 1) {
            throw PaymentRoutingException("Multiple payment providers match $reason.")
        }
        return matched.singleOrNull()
    }

    private fun configuredSelection(provider: String): RouteSelection =
        RouteSelection(
            provider = provider,
            available = providerBeansById.containsKey(provider),
            source = "configuration",
        )

    private fun paymentAmount(
        amount: Long?,
        currency: String?,
    ): PaymentAmount? {
        if (amount == null) return null
        val normalizedCurrency = currency?.trim()?.takeIf { it.isNotBlank() }
            ?: throw PaymentRoutingException("currency is required when amount is provided.")
        return PaymentAmount(amount = amount, currency = normalizedCurrency)
    }

    private fun String?.normalizedUppercase(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }?.uppercase()

    private fun String?.normalizedProviderId(): String =
        this?.trim()?.lowercase().orEmpty()

    private fun Set<String>.normalizedUppercaseSet(): Set<String> =
        mapNotNull { value -> value.trim().takeIf { it.isNotBlank() }?.uppercase() }.toSet()

    private data class RouteSelection(
        val provider: String,
        val available: Boolean,
        val source: String,
    )
}
