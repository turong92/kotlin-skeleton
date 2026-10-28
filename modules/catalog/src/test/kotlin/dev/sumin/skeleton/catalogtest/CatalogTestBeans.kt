package dev.sumin.skeleton.catalogtest

import dev.sumin.skeleton.catalog.FakeCatalogStore
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.web.ClientIpMode
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * 웹 시험의 조립 - 메모리 저장소, 권한 · 감사 대역, 그리고 결제의 나라 라우팅(KR → 토스 → KRW, 그 밖 → 기본 결제사 Paddle → `provider-currencies` USD).
 * 결제 HTTP 전체를 세우지 않고 이 빈만 손으로 둔다 - 카탈로그가 읽는 것은 통화를 정하는 라우팅뿐이다.
 */
@TestConfiguration(proxyBeanMethods = false)
class CatalogTestBeans {
    @Bean fun catalogStore() = FakeCatalogStore()

    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }

    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { AUDIT += it }

    @Bean fun paymentRouter(): PaymentProviderRouter = PaymentProviderRouter(listOf(FakePushGateway()), PaymentProperties())

    @Bean fun routing(): PaymentCountryRouting {
        val properties = PaymentProperties(
            defaultProvider = "paddle",
            routing = PaymentProperties.Routing(countryProviders = mapOf("KR" to "toss"), providerCurrencies = mapOf("paddle" to "USD"), devCountrySwitch = true),
        )
        val ips = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.CLOUDFLARE, trustedProxies = listOf("127.0.0.1/32")))
        return PaymentCountryRouting({ properties }, ips, { listOf("toss", "paddle") }, { emptyList() }, { emptyList() }, { id -> mapOf("toss" to setOf("KRW"))[id].orEmpty() })
    }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val AUDIT = CopyOnWriteArrayList<OperatorAction>()
    }
}
