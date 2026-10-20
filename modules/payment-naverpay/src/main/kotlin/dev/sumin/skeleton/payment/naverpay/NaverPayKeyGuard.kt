package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.ReturnOrigins
import java.net.URI
import java.time.Duration

/**
 * 키를 잘못 끼우는 실수를 기동 때 막는다. 메시지에는 **속성 · 환경변수 이름만** 적고 키 값은 싣지 않는다.
 * 네이버페이는 키 모양으로 개발과 운영을 가르지 않는다 - **`mode` 설정**이 API 서버 · 결제창 주소를 함께 가른다([NaverPayMode]). 개발 키와 운영 키가 같은 설정에 섞이면 안 되므로 모드를 일부러 고르게 한다.
 *
 * 기동 실패: 반대 모드의 네이버페이 호스트를 `base-url`(보호 환경에서는 `window-base-url` 도)에 적음 / 로컬 계열에서 운영 모드 / 보호 환경(stage · prod)에서 개발 모드(허락 스위치가 없으면) / 보호 환경에서 클라이언트 아이디 · 시크릿 · 체인 아이디가 비어 있음 /
 * 장부가 있는데 보호 환경에서 돌아올 주소(`return-url`)가 http(s) 절대 주소가 아님 / 장부가 있는데 `reconcile.first-delay` · `refund.settle-delay` 가 응답 제한보다 길지 않음.
 * 경고: 아이디 · 시크릿 · 체인 아이디 없음(보호 환경 밖) · 허락한 개발 모드 · 장부가 있는데 돌아올 주소 없음(보호 환경 밖) · 보호 환경에서 네이버페이 주소가 아닌 `base-url`(주소 값은 적지 않는다) ·
 * 보호 환경에서 http 로 돌아오는 주소 · 장부가 있는데 작업 워커가 꺼짐 · 장부가 있는데 응답 제한이 문서의 60초보다 짧음.
 *
 * @param ledgerPresent 장부가 있는가(가드가 돌 때 읽는다 - 빈 생성 순서에 기대지 않는다)
 */
class NaverPayKeyGuard(
    private val properties: NaverPayProperties,
    private val ledgerPresent: () -> Boolean = { false },
    private val jobQueueWorkerRunning: () -> Boolean = { true },
    private val responseTimeout: () -> Duration = { Duration.ofSeconds(5) },
    private val reconcileFirstDelay: () -> Duration = { PaymentProperties.Reconcile().firstDelay },
    private val refundSettleDelay: () -> Duration = { PaymentProperties.Refund().settleDelay },
) : DeployGuard {
    override val name: String = "payment-naverpay"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (isLocalLike(context) && properties.mode == NaverPayMode.PRODUCTION) {
            add("$MODE_PROPERTY is production mode but this is a local or test environment: real money must not move from here, use development mode with the development keys")
        }
        if (isOtherModeHost(properties.baseUrl)) {
            add("$BASE_URL_PROPERTY is the NaverPay host of the other mode (this is ${properties.mode.name.lowercase()} mode): the requests carrying the client secret would go to the wrong environment; fix the mode or leave the address empty")
        }
        if (context.protectedEnv && isOtherModeHost(properties.windowBaseUrl)) {
            add("$WINDOW_BASE_URL_PROPERTY is the NaverPay window host of the other mode (this is ${properties.mode.name.lowercase()} mode): the buyer would pay in the wrong environment; fix the mode or leave the address empty")
        }
        if (context.protectedEnv && !properties.allowDevelopmentModeInProtectedEnv && properties.mode == NaverPayMode.DEVELOPMENT) {
            add("$MODE_PROPERTY is development mode in a protected environment (skeleton.env=stage|prod): set production with the production keys, or set $ALLOW_PROPERTY=true to run a development-mode deployment on purpose")
        }
        if (context.protectedEnv) {
            if (properties.clientSecret.isBlank()) add("$SECRET_PROPERTY ($SECRET_ENV) is empty but ${ENABLED} in a protected environment (skeleton.env=stage|prod): the NaverPay provider would not exist and payments would not work; set the key or turn the module off")
            if (properties.clientId.isBlank()) add("$CLIENT_ID_PROPERTY ($CLIENT_ID_ENV) is empty but ${ENABLED} in a protected environment (skeleton.env=stage|prod): the NaverPay provider would not exist and payments would not work; set the id or turn the module off")
            if (properties.chainId.isBlank()) add("$CHAIN_ID_PROPERTY ($CHAIN_ID_ENV) is empty but ${ENABLED} in a protected environment (skeleton.env=stage|prod): the NaverPay provider would not exist and payments would not work; set the id or turn the module off")
        }
        if (properties.recurrent.partial) {
            add("$RECURRENT_PROPERTY has only some of client-id, client-secret and chain-id set: the subscription capability needs all three (they differ from the one-time values), so it would silently not exist")
        }
        if (context.protectedEnv && ledgerPresent() && !isWebUrl(properties.returnUrl)) {
            add("$RETURN_PROPERTY ($RETURN_ENV) is not an absolute http(s) address but the payment ledger is present: NaverPay returns the buyer there, so the payment window cannot open")
        }
        if (context.protectedEnv) {
            malformedOrigins().forEach { add("$ALLOWED_PROPERTY has an entry that is not a bare origin (scheme://host[:port], no path, query or user info): $it - it would be ignored and the buyer would keep returning to the fixed address") }
            properties.allowedReturnOrigins.filter { ReturnOrigins.originOf(it)?.startsWith("https://") == false }.forEach { add("$ALLOWED_PROPERTY has a non-https origin in a protected environment (skeleton.env=stage|prod): $it - the buyer would come back with the payment token over an unencrypted connection") }
        }
        if (ledgerPresent()) {
            val limit = responseTimeout()
            val delay = reconcileFirstDelay()
            if (delay <= limit) {
                add("$FIRST_DELAY_PROPERTY (${delay.seconds}s) must be longer than $RESPONSE_TIMEOUT_PROPERTY (${limit.seconds}s) while the payment ledger is present: the status lookup would overlap an apply that is still in flight")
            }
            val settle = refundSettleDelay()
            if (settle <= limit) {
                add("$SETTLE_DELAY_PROPERTY (${settle.seconds}s) must be longer than $RESPONSE_TIMEOUT_PROPERTY (${limit.seconds}s) while the payment ledger is present: the refund settle job could judge a refund 'not applied' while its request is still in flight")
            }
        }
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) {
            malformedOrigins().forEach { add("$ALLOWED_PROPERTY has an entry that is not a bare origin (scheme://host[:port], no path, query or user info): $it - it is ignored and the buyer keeps returning to the fixed address") }
        }
        val ledger = ledgerPresent()
        if (properties.recurrent.configured && properties.recurrent.clientId.trim() == properties.clientId.trim() && properties.recurrent.chainId.trim() == properties.chainId.trim()) {
            add("$RECURRENT_PROPERTY has the same client id and chain id as the one-time values: NaverPay issues different ones for recurrent payments, so the recurrent calls may be refused")
        }

        if (!context.protectedEnv) {
            if (properties.clientSecret.isBlank()) add("$ENABLED but $SECRET_PROPERTY ($SECRET_ENV) is empty: the NaverPay provider bean is not created and payment routing will not find it")
            if (properties.clientId.isBlank()) add("$ENABLED but $CLIENT_ID_PROPERTY ($CLIENT_ID_ENV) is empty: the NaverPay provider bean is not created and payment routing will not find it")
            if (properties.chainId.isBlank()) add("$ENABLED but $CHAIN_ID_PROPERTY ($CHAIN_ID_ENV) is empty: the NaverPay provider bean is not created and payment routing will not find it")
        }
        if (ledger && !isWebUrl(properties.returnUrl) && !context.protectedEnv) {
            add("$RETURN_PROPERTY ($RETURN_ENV) is not an absolute http(s) address but the payment ledger is present: the payment window cannot open (GET /config will report it unavailable)")
        }
        if (context.protectedEnv && properties.baseUrl.isNotBlank() && !isNaverPayApiHost(properties.baseUrl)) {
            add("$BASE_URL_PROPERTY is not a NaverPay API host: payment requests (carrying the client secret) would go elsewhere; leave it empty unless a proxy is intended")
        }
        if (context.protectedEnv && properties.returnUrl.isNotBlank() && !properties.returnUrl.trim().startsWith("https://", ignoreCase = true)) {
            add("$RETURN_PROPERTY is not an https address in a protected environment: the buyer comes back with the payment id over an unencrypted connection")
        }
        if (context.protectedEnv && properties.allowDevelopmentModeInProtectedEnv && properties.mode == NaverPayMode.DEVELOPMENT) {
            add("$MODE_PROPERTY is development mode in a protected environment because $ALLOW_PROPERTY=true: no real money moves, and the page keeps showing the test-payment notice")
        }
        if (ledger && !jobQueueWorkerRunning()) {
            add("the payment ledger is present but the job queue worker is off (skeleton.job-queue.enabled / skeleton.job-queue.worker-enabled): status lookups, fulfilment and webhook processing will not run")
        }
        if (ledger && responseTimeout() < MIN_APPLY_RESPONSE_TIMEOUT) {
            add("$RESPONSE_TIMEOUT_PROPERTY is shorter than ${MIN_APPLY_RESPONSE_TIMEOUT.seconds}s while the payment ledger is present: NaverPay documents up to 60 seconds for an apply or a cancel, so a normal one could end as 'unknown' and wait for a lookup")
        }
    }

    private fun malformedOrigins(): List<String> = properties.allowedReturnOrigins.filter { ReturnOrigins.originOf(it) == null }

    private fun isWebUrl(raw: String): Boolean {
        val uri = runCatching { URI.create(raw.trim()) }.getOrNull() ?: return false
        return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null
    }

    /** 문서의 두 API 주소(개발 · 운영)인가 - 앞뒤 공백 · 끝의 `/` 는 봐주고, 호스트 이름이 조금이라도 다르면 아니다. */
    private fun isNaverPayApiHost(baseUrl: String): Boolean {
        val uri = runCatching { URI.create(baseUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            NaverPayMode.entries.any { uri.host.equals(URI.create(it.apiBaseUrl).host, ignoreCase = true) } &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawPath.trim('/').isEmpty() &&
            uri.rawQuery == null
    }

    /** 비어 있지 않은 주소가 **반대 모드**의 네이버페이 호스트(API 또는 결제창)인가 - 호스트 이름만 본다. 다른 주소(프록시 · 가짜 서버)는 여기서 말하지 않는다. */
    private fun isOtherModeHost(address: String): Boolean {
        val host = runCatching { URI.create(address.trim()).host }.getOrNull() ?: return false
        val other = NaverPayMode.entries.filter { it != properties.mode }
        return other.any { host.equals(URI.create(it.apiBaseUrl).host, ignoreCase = true) || host.equals(URI.create(it.windowBaseUrl).host, ignoreCase = true) }
    }

    private fun isLocalLike(context: DeployContext): Boolean =
        context.env == DeployEnv.LOCAL || context.activeProfiles.any { it in LOCAL_PROFILES }

    private companion object {
        const val ENABLED = "skeleton.payment-naverpay.enabled=true"
        const val MODE_PROPERTY = "skeleton.payment-naverpay.mode"
        const val SECRET_PROPERTY = "skeleton.payment-naverpay.client-secret"
        const val CLIENT_ID_PROPERTY = "skeleton.payment-naverpay.client-id"
        const val CHAIN_ID_PROPERTY = "skeleton.payment-naverpay.chain-id"
        const val RETURN_PROPERTY = "skeleton.payment-naverpay.return-url"
        const val RECURRENT_PROPERTY = "skeleton.payment-naverpay.recurrent.*"
        const val ALLOWED_PROPERTY = "skeleton.payment-naverpay.allowed-return-origins"
        const val BASE_URL_PROPERTY = "skeleton.payment-naverpay.base-url"
        const val WINDOW_BASE_URL_PROPERTY = "skeleton.payment-naverpay.window-base-url"
        const val FIRST_DELAY_PROPERTY = "skeleton.payment.reconcile.first-delay"
        const val SETTLE_DELAY_PROPERTY = "skeleton.payment.refund.settle-delay"
        const val RESPONSE_TIMEOUT_PROPERTY = "skeleton.http.clients.payment-naverpay.response-timeout"
        const val ALLOW_PROPERTY = "skeleton.payment-naverpay.allow-development-mode-in-protected-env"
        const val SECRET_ENV = "<P>_PAYMENT_NAVERPAY_CLIENT_SECRET"
        const val CLIENT_ID_ENV = "<P>_PAYMENT_NAVERPAY_CLIENT_ID"
        const val CHAIN_ID_ENV = "<P>_PAYMENT_NAVERPAY_CHAIN_ID"
        const val RETURN_ENV = "<P>_PAYMENT_NAVERPAY_RETURN_URL"
        val LOCAL_PROFILES = setOf("local", "test")
        val MIN_APPLY_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
