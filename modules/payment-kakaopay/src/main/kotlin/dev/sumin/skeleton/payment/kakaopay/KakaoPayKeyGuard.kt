package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.ReturnOrigins
import java.net.URI
import java.time.Duration

/**
 * 키를 잘못 끼우는 실수를 기동 때 막는다. 메시지에는 **속성 · 환경변수 이름만** 적고 키 값은 싣지 않는다(모드를 말할 때도 "test merchant code" · "live merchant code" 라고만 쓴다).
 * 카카오페이는 키 모양으로 테스트와 실결제를 가르지 않는다 - **가맹점 코드(CID)** 가 가른다([KakaoPayMerchant]: `TC0ONETIME` 등 문서의 테스트 코드 = TEST).
 *
 * 기동 실패: 로컬 계열에서 실결제 가맹점 코드 / 보호 환경(stage · prod)에서 테스트 가맹점 코드(허락 스위치가 없으면) / 보호 환경에서 시크릿 키 · 가맹점 코드가 비어 있음 /
 * 장부가 있는데 보호 환경에서 돌아올 주소(`return-url`)가 http(s) 절대 주소가 아님 / 장부가 있는데 `reconcile.first-delay` · `refund.settle-delay` 가 응답 제한보다 길지 않음.
 * 경고: 시크릿 키 · 가맹점 코드 없음(보호 환경 밖) · 허락한 테스트 가맹점 코드 · 장부가 있는데 돌아올 주소 없음(보호 환경 밖) · 보호 환경에서 카카오페이 주소가 아닌 `base-url`(주소 값은 적지 않는다) ·
 * 보호 환경에서 http 로 돌아오는 주소 · 장부가 있는데 작업 워커가 꺼짐 · 장부가 있는데 응답 제한이 10초보다 짧음.
 *
 * @param ledgerPresent 장부가 있는가(가드가 돌 때 읽는다 - 빈 생성 순서에 기대지 않는다)
 */
class KakaoPayKeyGuard(
    private val properties: KakaoPayProperties,
    private val ledgerPresent: () -> Boolean = { false },
    private val jobQueueWorkerRunning: () -> Boolean = { true },
    private val responseTimeout: () -> Duration = { Duration.ofSeconds(5) },
    private val reconcileFirstDelay: () -> Duration = { PaymentProperties.Reconcile().firstDelay },
    private val refundSettleDelay: () -> Duration = { PaymentProperties.Refund().settleDelay },
) : DeployGuard {
    override val name: String = "payment-kakaopay"

    override fun problems(context: DeployContext): List<String> = buildList {
        val secretRaw = properties.secretKey.trim()
        val cidRaw = properties.cid.trim()
        val mode = KakaoPayMerchant.modeOf(cidRaw)

        if (isLocalLike(context) && mode == PaymentMode.LIVE) {
            add("$CID_PROPERTY holds a live merchant code but this is a local or test environment: real money must not move from here, use the test merchant code (TC0ONETIME) with the Secret key (dev)")
        }
        val subscriptionRaw = properties.subscriptionCid.trim()
        if (subscriptionRaw.isNotEmpty() && cidRaw.isNotEmpty() && KakaoPayMerchant.modeOf(subscriptionRaw) != mode) {
            add("$SUBSCRIPTION_CID_PROPERTY and $CID_PROPERTY are not in the same mode (one is a test merchant code and the other a live one): a subscription would register in one world and charge in the other")
        }
        if (context.protectedEnv && !properties.allowTestKeysInProtectedEnv && mode == PaymentMode.TEST) {
            add("$CID_PROPERTY holds a test merchant code in a protected environment (skeleton.env=stage|prod): use the live merchant code with the live Secret key, or set $ALLOW_PROPERTY=true to run a test-mode deployment on purpose")
        }
        if (context.protectedEnv && secretRaw.isEmpty()) {
            add("$SECRET_PROPERTY ($SECRET_ENV) is empty but skeleton.payment-kakaopay.enabled=true in a protected environment (skeleton.env=stage|prod): the KakaoPay provider would not exist and payments would not work; set the key or turn the module off")
        }
        if (context.protectedEnv && cidRaw.isEmpty()) {
            add("$CID_PROPERTY ($CID_ENV) is empty but skeleton.payment-kakaopay.enabled=true in a protected environment (skeleton.env=stage|prod): the KakaoPay provider would not exist and payments would not work; set the merchant code or turn the module off")
        }
        if (context.protectedEnv && ledgerPresent() && !isWebUrl(properties.returnUrl)) {
            add("$RETURN_PROPERTY ($RETURN_ENV) is not an absolute http(s) address but the payment ledger is present: KakaoPay returns the buyer there, so the payment window cannot open")
        }
        if (context.protectedEnv) {
            malformedOrigins().forEach { add("$ALLOWED_PROPERTY has an entry that is not a bare origin (scheme://host[:port], no path, query or user info): $it - it would be ignored and the buyer would keep returning to the fixed address") }
            properties.allowedReturnOrigins.filter { ReturnOrigins.originOf(it)?.startsWith("https://") == false }.forEach { add("$ALLOWED_PROPERTY has a non-https origin in a protected environment (skeleton.env=stage|prod): $it - the buyer would come back with the payment token over an unencrypted connection") }
        }
        if (ledgerPresent()) {
            val limit = responseTimeout()
            val delay = reconcileFirstDelay()
            if (delay <= limit) {
                add("$FIRST_DELAY_PROPERTY (${delay.seconds}s) must be longer than $RESPONSE_TIMEOUT_PROPERTY (${limit.seconds}s) while the payment ledger is present: the status lookup would overlap an approve that is still in flight")
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
        val secretRaw = properties.secretKey.trim()
        val cidRaw = properties.cid.trim()
        val ledger = ledgerPresent()

        if (secretRaw.isEmpty() && !context.protectedEnv) {
            add("skeleton.payment-kakaopay.enabled=true but $SECRET_PROPERTY ($SECRET_ENV) is empty: the KakaoPay provider bean is not created and payment routing will not find it")
        }
        if (cidRaw.isEmpty() && !context.protectedEnv) {
            add("skeleton.payment-kakaopay.enabled=true but $CID_PROPERTY ($CID_ENV) is empty: the KakaoPay provider bean is not created and payment routing will not find it")
        }
        if (ledger && !isWebUrl(properties.returnUrl) && !context.protectedEnv) {
            add("$RETURN_PROPERTY ($RETURN_ENV) is not an absolute http(s) address but the payment ledger is present: the payment window cannot open (GET /config will report it unavailable)")
        }
        if (context.protectedEnv && !isKakaoPayApiHost(properties.baseUrl)) {
            add("$BASE_URL_PROPERTY is not the KakaoPay API host: payment requests (carrying the secret key) would go elsewhere; leave it at its default unless a proxy is intended")
        }
        if (context.protectedEnv && properties.returnUrl.isNotBlank() && !properties.returnUrl.trim().startsWith("https://", ignoreCase = true)) {
            add("$RETURN_PROPERTY is not an https address in a protected environment: the buyer comes back with the approval token over an unencrypted connection")
        }
        if (context.protectedEnv && properties.allowTestKeysInProtectedEnv && KakaoPayMerchant.modeOf(cidRaw) == PaymentMode.TEST) {
            add("$CID_PROPERTY holds a test merchant code in a protected environment because $ALLOW_PROPERTY=true: no real money moves, and the page keeps showing the test-payment notice")
        }
        if (ledger && !jobQueueWorkerRunning()) {
            add("the payment ledger is present but the job queue worker is off (skeleton.job-queue.enabled / skeleton.job-queue.worker-enabled): status lookups, fulfilment and webhook processing will not run")
        }
        if (ledger && responseTimeout() < MIN_APPROVE_RESPONSE_TIMEOUT) {
            add("$RESPONSE_TIMEOUT_PROPERTY is shorter than ${MIN_APPROVE_RESPONSE_TIMEOUT.seconds}s while the payment ledger is present: a normal approve would often end as 'unknown' and wait for a lookup")
        }
    }

    private fun malformedOrigins(): List<String> = properties.allowedReturnOrigins.filter { ReturnOrigins.originOf(it) == null }

    private fun isWebUrl(raw: String): Boolean {
        val uri = runCatching { URI.create(raw.trim()) }.getOrNull() ?: return false
        return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null
    }

    /** 기본 카카오페이 주소(`https://open-api.kakaopay.com`)인가 - 앞뒤 공백 · 끝의 `/` 는 봐주고, 호스트 이름이 조금이라도 다르면 아니다. */
    private fun isKakaoPayApiHost(baseUrl: String): Boolean {
        val uri = runCatching { URI.create(baseUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(KAKAOPAY_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawPath.trim('/').isEmpty() &&
            uri.rawQuery == null
    }

    private fun isLocalLike(context: DeployContext): Boolean =
        context.env == DeployEnv.LOCAL || context.activeProfiles.any { it in LOCAL_PROFILES }

    private companion object {
        const val SECRET_PROPERTY = "skeleton.payment-kakaopay.secret-key"
        const val CID_PROPERTY = "skeleton.payment-kakaopay.cid"
        const val SUBSCRIPTION_CID_PROPERTY = "skeleton.payment-kakaopay.subscription-cid"
        const val RETURN_PROPERTY = "skeleton.payment-kakaopay.return-url"
        const val ALLOWED_PROPERTY = "skeleton.payment-kakaopay.allowed-return-origins"
        const val BASE_URL_PROPERTY = "skeleton.payment-kakaopay.base-url"
        const val KAKAOPAY_HOST = "open-api.kakaopay.com"
        const val FIRST_DELAY_PROPERTY = "skeleton.payment.reconcile.first-delay"
        const val SETTLE_DELAY_PROPERTY = "skeleton.payment.refund.settle-delay"
        const val RESPONSE_TIMEOUT_PROPERTY = "skeleton.http.clients.payment-kakaopay.response-timeout"
        const val ALLOW_PROPERTY = "skeleton.payment-kakaopay.allow-test-keys-in-protected-env"
        const val SECRET_ENV = "<P>_PAYMENT_KAKAOPAY_SECRET_KEY"
        const val CID_ENV = "<P>_PAYMENT_KAKAOPAY_CID"
        const val RETURN_ENV = "<P>_PAYMENT_KAKAOPAY_RETURN_URL"
        val LOCAL_PROFILES = setOf("local", "test")
        val MIN_APPROVE_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
