package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * 결제사를 고른다. **후보는 늘 능력으로 먼저 거른 뒤** 통화 → 국가 → 기본 결제사 → 단일 결제사 순으로 라우팅한다(기본 결제사도 그 능력을 가졌을 때만 고른다) —
 * 능력만 구현한 게이트웨이(조회 전용 · 결제창 전용)가 있어도 다른 능력의 라우팅이 달라지지 않는다.
 *
 * - 단일 결제사 지름길(후보가 하나뿐일 때)은 `enabled=false` 를 보지 않는다. [gateway] 와 id 지정은 본다. 기존 동작이라 호환을 위해 그대로 둔다.
 * - id 를 지정하면 후보를 거르지 않고 그 결제사를 찾은 뒤 능력을 확인한다(없으면 `CAPABILITY_UNSUPPORTED`).
 */
class PaymentProviderRouter internal constructor(
    private val routesOf: () -> PaymentRoutes,
    private val allRoutes: () -> List<PaymentRoutes>,
) {
    constructor(providers: List<PaymentGateway>, properties: PaymentProperties) : this(PaymentRoutes(null, null, providers, properties))

    private constructor(routes: PaymentRoutes) : this({ routes }, { listOf(routes) })

    private val providersById: Map<String, PaymentGateway> get() = routesOf().providersById
    private val properties: PaymentProperties get() = routesOf().properties

    /** 지금 프로젝트(없으면 서버 전체)의 결제 설정 — 웹훅 비밀 조각 · 나라 지도 · 기본 결제사처럼 프로젝트마다 다른 값은 이것으로 읽는다. */
    fun properties(): PaymentProperties = properties

    /** 켜진 모든 프로젝트의 라우팅 상태(프로젝트 없는 앱이면 하나) — 기동 때 검사용. */
    fun allRoutes(): List<PaymentRoutes> = allRoutes.invoke()

    /** 어느 프로젝트에든 [capability] 를 가진 결제사가 하나라도 있는가 — 기동 때 "빌링 능력이 있는 결제사가 있는가" 검사용. */
    fun anyGateway(capability: KClass<out PaymentGateway>): Boolean = allRoutes().any { routes -> routes.providersById.values.any(capability::isInstance) }

    /** 이 라우터가 아는 결제사 id 전부(꺼진 것 포함) — 운영자의 결제 번호 검색이 `provider in (…)` 을 같이 걸어 유니크 인덱스를 타게 한다. */
    fun providerIds(): Set<String> = providersById.keys

    /** [capability] 를 가진 켜진 결제사의 id — 화면이 고를 수 있는 결제사 목록. */
    fun providerIds(capability: KClass<out PaymentGateway>): List<String> =
        providersById.filterValues { capability.isInstance(it) && it.route().enabled }.keys.sorted()

    /** [capability] 를 가진 켜진 게이트웨이 자체(id 순). */
    fun <T : PaymentGateway> gateways(capability: KClass<T>): List<T> =
        providersById.toSortedMap().values.filter { it.route().enabled }.mapNotNull { capability.safeCast(it) }

    /** 이 결제사가 [country] 를 **실효 설정**으로 받는가 - 운영자의 `skeleton.payment.providers.<id>.countries` 덮어쓰기가 모듈 기본보다 먼저다. */
    fun servesCountry(providerId: String, country: String): Boolean =
        providerId.normalizeProviderId()?.let { providersById[it] }?.route()?.countries?.contains(country.trim().uppercase()) == true

    /**
     * 구매자가 고를 수 있는 결제창 결제사 - [country] 를 실효 설정으로 받는 켜진 결제사. 서버가 고를 때 통화를 바꿔 결제하는 결제사([PaymentTransactionGateway] - Paddle)는 뺀다:
     * 견적 통화(원화)를 받지 않아 구매자가 고르면 늘 거절되기 때문이다(그 결제사는 서버가 나라로 고른다).
     */
    fun choosableProviders(country: String): List<String> =
        gateways(PaymentCheckoutGateway::class).filter { it !is PaymentTransactionGateway && servesCountry(it.providerId, country) }.map { it.providerId.trim().lowercase() }

    /** 이 결제사가 [currency] 를 실효 설정으로 받는가(덮어쓰기 `providers.<id>.currencies` 가 먼저). */
    fun servesCurrency(providerId: String, currency: String): Boolean =
        providerId.normalizeProviderId()?.let { providersById[it] }?.route()?.currencies?.contains(currency.trim().uppercase()) == true

    /**
     * 서버가 나라로 정한 결제사가 이 통화의 결제를 받을 수 있는가 - 받는 통화를 **적지 않은** 결제사(가격이 통화를 정하는 Paddle)는 거르지 않는다.
     * 구매자가 고른 결제사는 더 엄격한 [servesCurrency] 로 본다(목록에 없으면 안 받는다).
     */
    fun takesCurrency(providerId: String, currency: String): Boolean = currenciesOf(providerId).let { it.isEmpty() || currency.trim().uppercase() in it }

    /**
     * `routing.provider-currencies` 가 적은 통화를 그 결제사가 받는지 기동 때 본다(켜진 모든 프로젝트) - 결제사가 받는 통화 목록(실효)을 적어 뒀는데 거기 없는 통화를 적으면 [IllegalStateException].
     * 받는 통화를 적지 않은 결제사(Paddle)나 이 라우터에 없는 결제사는 거르지 않는다. 키의 대소문자는 가리지 않는다.
     */
    fun requireProviderCurrenciesServed() {
        allRoutes().forEach { routes ->
            routes.properties.routing.providerCurrencies.forEach { (key, currency) ->
                val id = key.trim().lowercase()
                val gateway = routes.providersById[id] ?: return@forEach
                val configured = routes.properties.providers[id]?.currencies ?: gateway.supportedCurrencies
                val accepted = configured.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.uppercase() }.toSet()
                check(accepted.isEmpty() || currency in accepted) {
                    "skeleton.payment.routing.provider-currencies.$key=$currency is not a currency the provider '$id' takes (${accepted.sorted().joinToString()})"
                }
            }
        }
    }

    /** 이 결제사가 **실효 설정으로** 받는 통화(대문자) - 모르는 결제사는 빈 집합. */
    fun currenciesOf(providerId: String): Set<String> =
        providerId.normalizeProviderId()?.let { providersById[it] }?.route()?.currencies.orEmpty()

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

    companion object {
        /**
         * 결제사 비밀은 프로젝트마다 **자기 것**이어야 한다(프로젝트마다 결제사 계정 · 키를 따로): 결제사를 켠 프로젝트가 비밀을 자기 `config` 에 적지 않고 공통 설정에서 물려받았거나,
         * 서비스 중인 두 프로젝트의 비밀이 같으면 기동 실패 — 한 계정의 상점으로 다른 프로젝트 주문이 결제되고 웹훅이 엉뚱한 스키마로 가는 것을 막는다.
         * 서명 없는 결제사의 웹훅 주소 비밀 조각(`payment.webhook.path-token`)도 같은 규칙이다.
         */
        private fun requireOwnSecrets(registry: ProjectRegistry, sources: List<PaymentGatewaySource>, made: Set<Pair<String, PaymentGatewaySource>>, routes: dev.sumin.skeleton.common.project.ProjectScoped<PaymentRoutes>) {
            val values = mutableMapOf<String, MutableList<Pair<String, String>>>()   // 설정 키 → (프로젝트, 값)
            fun check(project: Project, key: String, trackSameness: Boolean = true) {
                val own = registry.ownValue(project, key)
                val effective = own ?: registry.sharedValue(key)
                if (effective == null) return
                check(own != null) {
                    "project '${project.id}': skeleton.$key is inherited from the shared settings — every project needs its own payment provider account and keys " +
                        "(set skeleton.projects.${project.id}.config.$key; one provider account must not serve two projects)"
                }
                if (trackSameness) values.getOrPut(key) { mutableListOf() } += project.id to effective
            }
            registry.active.forEach { project ->
                val enabled = sources.filter { project.id to it in made }
                enabled.flatMap { it.secretKeys }.distinct().forEach { check(project, it) }
                enabled.flatMap { it.ownKeys }.distinct().forEach { check(project, it, trackSameness = false) }
                if (routes.of(project).gateways.any { it is PaymentWebhookGateway && !it.signed }) check(project, "payment.webhook.path-token")
            }
            values.forEach { (key, owners) ->
                owners.groupBy({ it.second }, { it.first }).values.firstOrNull { it.size > 1 }?.let { ids ->
                    error("Projects ${ids.joinToString { "'$it'" }} use the same skeleton.$key — every project needs its own payment provider account and keys (skeleton.projects.<id>.config.$key)")
                }
            }
        }

        /**
         * 프로젝트 모드: 프로젝트마다 자기 설정(`skeleton.payment.{default-provider, providers, routing.country-providers, webhook.path-token}` — 나머지는 공통)과
         * 자기 결제사 게이트웨이(각 결제사 모듈의 [PaymentGatewaySource] 가 그 프로젝트의 키로 만든다)를 가진 라우팅 상태를 만든다.
         * [shared] 는 모든 프로젝트가 같이 쓰는 게이트웨이(앱이 직접 둔 빈). 서비스 중인 프로젝트는 지금 만들어 보므로 잘못된 설정은 프로젝트 이름이 담긴 기동 실패다.
         * 부를 때는 지금 프로젝트(`ProjectContext`)의 것으로 넘어간다 — 프로젝트 문맥이 없으면 `NoProjectContextException`.
         */
        fun forProjects(registry: ProjectRegistry, shared: List<PaymentGateway>, sources: List<PaymentGatewaySource>): PaymentProviderRouter {
            val made = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, PaymentGatewaySource>>()
            val routes = registry.scopedFrom("skeleton.payment", PaymentProperties::class.java) { project, properties ->
                val settings = object : PaymentProviderSettings {
                    override val projectId = project.id
                    override fun <T : Any> bind(prefix: String, type: Class<T>): T = registry.bind(project, prefix, type)
                    override fun <R> inProject(block: () -> R): R = ProjectContext.runIn(project, block)
                }
                PaymentRoutes(project.id, project, shared + sources.mapNotNull { source -> source.create(settings)?.also { made += project.id to source } }, properties)
            }
            registry.active.forEach { project ->
                try {
                    routes.of(project)
                } catch (ex: RuntimeException) {
                    throw IllegalStateException("Payment of project '${project.id}' cannot start: ${generateSequence<Throwable>(ex) { it.cause }.last().message}", ex)
                }
            }
            requireOwnSecrets(registry, sources, made, routes)
            return PaymentProviderRouter({ routes.get() }, { registry.active.map { routes.of(it) } })
        }
    }
}

/** 한 프로젝트(또는 프로젝트 없는 앱 전체)의 결제 라우팅 상태 — 그 프로젝트의 결제 설정과 결제사 게이트웨이 */
class PaymentRoutes internal constructor(
    val projectId: String?,
    val project: Project?,
    providers: List<PaymentGateway>,
    val properties: PaymentProperties,
) {
    internal val providersById: Map<String, PaymentGateway> = run {
        val grouped = providers.groupBy { provider ->
            requireNotNull(provider.providerId.trim().lowercase().takeIf { it.isNotBlank() }) {
                "Payment provider id must not be blank"
            }
        }
        val duplicate = grouped.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Duplicate payment provider id '${duplicate!!.key}'"
        }
        grouped.mapValues { it.value.single() }
    }

    val gateways: Collection<PaymentGateway> get() = providersById.values
}
