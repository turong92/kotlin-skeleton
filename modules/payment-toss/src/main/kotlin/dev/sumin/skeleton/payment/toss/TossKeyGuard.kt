package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentProperties
import java.net.URI
import java.time.Duration

/**
 * 키를 잘못 끼우는 실수를 기동 때 막는다(스펙 §9.3). 메시지에는 **속성 · 환경변수 이름만** 적고 키 값이나 접두사 뒤의 글자는 싣지 않는다
 * (모드를 말할 때도 "test key" · "live key" 라고만 쓴다).
 *
 * 기동 실패(장부가 있을 때 `reconcile.first-delay` · `refund.settle-delay` 가 응답 제한보다 길지 않음 포함): 클라이언트 키와 시크릿 키의 모드 · 종류가 어긋남 / 결제위젯 키 묶음 / 로컬 계열에서 실결제 키 /
 * 보호 환경(stage · prod)에서 테스트 키(허락 스위치가 없으면) / 보호 환경에서 시크릿 키가 비어 있음(토스를 켰는데 비밀을 빠뜨린 배포가 정상으로 뜨지 않게 —
 * 스펙 §9.3 은 경고였으나 주인 결정으로 보호 환경만 기동 실패) / 장부가 있는데 보호 환경에서 클라이언트 키가 없음.
 * 경고: 시크릿 키 없음(보호 환경 밖) · 모양이 낯선 키 · 허락한 테스트 키 · 장부가 있는데 클라이언트 키 없음(보호 환경 밖) ·
 * 보호 환경에서 토스 주소가 아닌 `base-url`(주소 값은 적지 않는다) · 장부가 있는데 작업 워커가 꺼짐(환경 무관 — 시작 때 한 줄, 막지는 않는다) · 장부가 있는데 응답 제한이 10초보다 짧음.
 *
 * @param ledgerPresent 장부가 있는가(가드가 돌 때 읽는다 — 빈 생성 순서에 기대지 않는다)
 * @param jobQueueWorkerRunning `skeleton.job-queue` 의 워커가 도는가
 * @param responseTimeout 이 모듈의 외부 HTTP 클라이언트 응답 제한(`skeleton.http.clients.<client-name>.response-timeout`)
 * @param reconcileFirstDelay 승인 선점 뒤 첫 조회까지(`skeleton.payment.reconcile.first-delay`)
 * @param refundSettleDelay 환불 선점 뒤 첫 조회까지(`skeleton.payment.refund.settle-delay`)
 */
class TossKeyGuard(
    private val properties: TossPaymentProperties,
    private val ledgerPresent: () -> Boolean = { false },
    private val jobQueueWorkerRunning: () -> Boolean = { true },
    private val responseTimeout: () -> Duration = { Duration.ofSeconds(5) },
    private val reconcileFirstDelay: () -> Duration = { PaymentProperties.Reconcile().firstDelay },
    private val refundSettleDelay: () -> Duration = { PaymentProperties.Refund().settleDelay },
) : DeployGuard {
    override val name: String = "payment-toss"

    override fun problems(context: DeployContext): List<String> = buildList {
        val secretRaw = properties.secretKey.trim()
        val clientRaw = properties.clientKey.trim()
        val secret = TossApiKey.parse(secretRaw)
        val client = TossApiKey.parse(clientRaw)

        if (secret != null && secret.kind != TossApiKey.Kind.SECRET) add(wrongKind(SECRET_PROPERTY, "secret key (sk)"))
        if (client != null && client.kind != TossApiKey.Kind.CLIENT) add(wrongKind(CLIENT_PROPERTY, "client key (ck)"))
        if (secret != null && client != null && secret.mode != client.mode) {
            add("$CLIENT_PROPERTY and $SECRET_PROPERTY are keys of different modes (one test, one live): use the pair issued together")
        }

        val keys = listOfNotNull(
            secret?.let { SECRET_PROPERTY to it },
            client?.let { CLIENT_PROPERTY to it },
        )
        if (isLocalLike(context)) {
            val live = keys.filter { it.second.mode == PaymentMode.LIVE }.map { it.first }
            if (live.isNotEmpty()) {
                add("${live.joinToString(" and ")} hold a live key but this is a local or test environment: real money must not move from here, use the test key pair")
            }
        }
        if (context.protectedEnv && !properties.allowTestKeysInProtectedEnv) {
            val test = keys.filter { it.second.mode == PaymentMode.TEST }.map { it.first }
            if (test.isNotEmpty()) {
                add("${test.joinToString(" and ")} hold a test key in a protected environment (skeleton.env=stage|prod): use the live key pair, or set $ALLOW_PROPERTY=true to run a test-mode deployment on purpose")
            }
        }
        if (context.protectedEnv && secretRaw.isEmpty()) {
            add("$SECRET_PROPERTY ($SECRET_ENV) is empty but skeleton.payment-toss.enabled=true in a protected environment (skeleton.env=stage|prod): the Toss provider would not exist and payments would not work; set the key or turn the module off")
        }
        if (context.protectedEnv && ledgerPresent() && clientRaw.isEmpty()) {
            add("$CLIENT_PROPERTY ($CLIENT_ENV) is empty but the payment ledger is present: the payment window cannot open")
        }
        if (ledgerPresent()) {
            val limit = responseTimeout()
            val delay = reconcileFirstDelay()
            if (delay <= limit) {
                add("$FIRST_DELAY_PROPERTY (${delay.seconds}s) must be longer than $RESPONSE_TIMEOUT_PROPERTY (${limit.seconds}s) while the payment ledger is present: the status lookup would overlap a confirm that is still in flight")
            }
            val settle = refundSettleDelay()
            if (settle <= limit) {
                add("$SETTLE_DELAY_PROPERTY (${settle.seconds}s) must be longer than $RESPONSE_TIMEOUT_PROPERTY (${limit.seconds}s) while the payment ledger is present: the refund settle job could judge a refund 'not applied' while its request is still in flight")
            }
        }
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        val secretRaw = properties.secretKey.trim()
        val clientRaw = properties.clientKey.trim()
        val secret = TossApiKey.parse(secretRaw)
        val client = TossApiKey.parse(clientRaw)
        val ledger = ledgerPresent()

        if (secretRaw.isEmpty()) {
            // 보호 환경에서는 problems() 가 이미 기동을 막는다 — 두 번 말하지 않는다
            if (!context.protectedEnv) add("skeleton.payment-toss.enabled=true but $SECRET_PROPERTY ($SECRET_ENV) is empty: the Toss provider bean is not created and payment routing will not find it")
        } else if (secret == null) {
            add("$SECRET_PROPERTY does not look like a Toss secret key (<mode>_sk_…): check $SECRET_ENV")
        }
        if (clientRaw.isNotEmpty() && client == null) {
            add("$CLIENT_PROPERTY does not look like a Toss client key (<mode>_ck_…): check $CLIENT_ENV")
        }
        if (ledger && clientRaw.isEmpty() && !context.protectedEnv) {
            add("$CLIENT_PROPERTY ($CLIENT_ENV) is empty but the payment ledger is present: the payment window cannot open (GET /config will report it unavailable)")
        }
        if (context.protectedEnv && !isTossApiHost(properties.baseUrl)) {
            add("$BASE_URL_PROPERTY is not the Toss API host: payment requests (carrying the secret key) would go elsewhere; leave it at its default unless a proxy is intended")
        }
        if (context.protectedEnv && properties.allowTestKeysInProtectedEnv) {
            val test = listOfNotNull(
                secret?.takeIf { it.mode == PaymentMode.TEST }?.let { SECRET_PROPERTY },
                client?.takeIf { it.mode == PaymentMode.TEST }?.let { CLIENT_PROPERTY },
            )
            if (test.isNotEmpty()) {
                add("${test.joinToString(" and ")} hold a test key in a protected environment because $ALLOW_PROPERTY=true: no real money moves, and the page keeps showing the test-payment notice")
            }
        }
        if (ledger && !jobQueueWorkerRunning()) {
            add("the payment ledger is present but the job queue worker is off (skeleton.job-queue.enabled / skeleton.job-queue.worker-enabled): status lookups, fulfilment and webhook processing will not run")
        }
        if (ledger && responseTimeout() < MIN_CONFIRM_RESPONSE_TIMEOUT) {
            add("skeleton.http.clients.payment-toss.response-timeout is shorter than ${MIN_CONFIRM_RESPONSE_TIMEOUT.seconds}s while the payment ledger is present: a normal confirm would often end as 'unknown' and wait for a lookup")
        }
    }

    private fun wrongKind(property: String, expected: String): String =
        "$property must be a Toss API-individual-integration $expected issued as a pair: widget keys (gck/gsk) are not supported by this module (payment window only), and a secret key must never be set as the client key"

    /** 기본 토스 주소(`https://api.tosspayments.com`)인가 — 앞뒤 공백 · 끝의 `/` 는 봐주고, 호스트 이름이 조금이라도 다르면 아니다. */
    private fun isTossApiHost(baseUrl: String): Boolean {
        val uri = runCatching { URI.create(baseUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(TOSS_HOST, ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawPath.trim('/').isEmpty() &&
            uri.rawQuery == null
    }

    private fun isLocalLike(context: DeployContext): Boolean =
        context.env == DeployEnv.LOCAL || context.activeProfiles.any { it in LOCAL_PROFILES }

    private companion object {
        const val SECRET_PROPERTY = "skeleton.payment-toss.secret-key"
        const val CLIENT_PROPERTY = "skeleton.payment-toss.client-key"
        const val BASE_URL_PROPERTY = "skeleton.payment-toss.base-url"
        const val TOSS_HOST = "api.tosspayments.com"
        const val FIRST_DELAY_PROPERTY = "skeleton.payment.reconcile.first-delay"
        const val SETTLE_DELAY_PROPERTY = "skeleton.payment.refund.settle-delay"
        const val RESPONSE_TIMEOUT_PROPERTY = "skeleton.http.clients.payment-toss.response-timeout"
        const val ALLOW_PROPERTY = "skeleton.payment-toss.allow-test-keys-in-protected-env"
        const val SECRET_ENV = "<P>_PAYMENT_TOSS_SECRET_KEY"
        const val CLIENT_ENV = "<P>_PAYMENT_TOSS_CLIENT_KEY"
        val LOCAL_PROFILES = setOf("local", "test")
        val MIN_CONFIRM_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
