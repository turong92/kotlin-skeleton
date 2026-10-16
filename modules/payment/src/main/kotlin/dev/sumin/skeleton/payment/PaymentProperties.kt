package dev.sumin.skeleton.payment

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment")
data class PaymentProperties(
    val defaultProvider: String? = null,
    val providers: Map<String, Provider> = emptyMap(),
    /** 접속 나라로 결제사를 정하는 규칙 - 서버가 정하고 화면은 못 바꾼다. */
    val routing: Routing = Routing(),
    /** (장부) 만든 주문으로 결제할 수 있는 시간. 지나면 만료 작업이 결제사에 묻고 닫는다. */
    val checkoutTtl: Duration = Duration.ofMinutes(30),
    /** (장부) 조회 작업. */
    val reconcile: Reconcile = Reconcile(),
    /** (장부) 환불. */
    val refund: Refund = Refund(),
    /** (장부) 웹훅 수신함. */
    val webhook: Webhook = Webhook(),
    /** (장부) HTTP — 사용자 경로와 웹훅 경로의 기준 경로. */
    val http: Http = Http(),
    /** (장부) 운영자 HTTP — 기본 꺼짐. */
    val admin: Admin = Admin(),
    /** (장부) 계정 탈퇴 때 결제 줄을 어떻게 하는가. */
    val erasure: Erasure = Erasure(),
    /** (장부) 구독 — 기본 꺼짐. 켜는 앱이 플랜([SubscriptionPlans])과 암호 키(`crypto`)를 낸다. */
    val subscription: Subscription = Subscription(),
) {
    init {
        require(checkoutTtl > Duration.ZERO) { "skeleton.payment.checkout-ttl must be positive" }
    }

    data class Provider(
        val enabled: Boolean = true,
        val currencies: Set<String> = emptySet(),
        val countries: Set<String> = emptySet(),
    )

    data class Routing(
        /** 나라(ISO-3166 두 글자 대문자) → 결제사 id. 지도에 없는 나라는 [PaymentProperties.defaultProvider], 그것도 열려 있지 않으면 열린 결제사가 하나뿐일 때 그것. 예: `KR: toss`. */
        val countryProviders: Map<String, String> = emptyMap(),
        /**
         * **로컬 개발 전용** 나라 덮어쓰기 - 정하면 접속 나라와 상관없이 모든 요청이 이 나라다(예: `US` 로 Paddle 화면을 본다). 보호 환경(stage · prod)에서 정해져 있으면 기동 실패(`PaymentCountryDeployGuard`).
         * 요청의 헤더나 값으로는 나라를 덮어쓰지 못한다.
         */
        val devCountry: String? = null,
        /**
         * **로컬 개발 전용** - 켜면 요청의 `X-Dev-Country: US` 헤더가 **그 요청의** 나라가 된다(기동을 다시 하지 않고 토스 ↔ Paddle 을 오간다). 꺼져 있으면 헤더는 완전히 무시된다.
         * 보호 환경(stage · prod)에서 켜져 있으면 기동 실패(`PaymentCountryDeployGuard`). `GET /config` 가 켜져 있음을 알린다.
         */
        val devCountrySwitch: Boolean = false,
    ) {
        init {
            countryProviders.keys.forEach { require(COUNTRY.matches(it)) { "skeleton.payment.routing.country-providers keys must be ISO-3166 alpha-2 codes (two capital letters): $it" } }
            require(devCountry == null || COUNTRY.matches(devCountry)) { "skeleton.payment.routing.dev-country must be an ISO-3166 alpha-2 code (two capital letters)" }
        }

        private companion object {
            val COUNTRY = Regex("[A-Z]{2}")
        }
    }

    data class Reconcile(
        /** 승인 선점 뒤 첫 조회까지. 결제사 응답 제한 시간보다 길어야 한다(`payment-toss` 의 가드가 본다). */
        val firstDelay: Duration = Duration.ofSeconds(60),
        /** 조회 작업의 시도 수 — 큐의 기본 백오프(30초 · 2배 · 최대 1시간)로 약 4시간. */
        val maxAttempts: Int = 12,
    ) {
        init {
            require(firstDelay > Duration.ZERO) { "skeleton.payment.reconcile.first-delay must be positive" }
            require(maxAttempts >= 1) { "skeleton.payment.reconcile.max-attempts must be at least 1" }
        }
    }

    data class Refund(
        /** 본인이 환불 **요청**을 만들 수 있다(돈은 운영자가 움직인다). */
        val requestsEnabled: Boolean = true,
        /** 환불 선점 뒤 첫 조회까지 — 결제사 응답 제한 시간보다 길어야 한다(`payment-toss` 의 가드가 장부가 있을 때 기동 때 막는다; "적용 안 됨"으로 닫은 뒤 앞선 요청이 늦게 적용되는 경우를 줄인다). */
        val settleDelay: Duration = Duration.ofMinutes(2),
        /**
         * 결제사가 "접수했다(처리 중)"고 답한 환불 줄이 조회에 여전히 안 보일 때, 운영자의 「결제사와 맞추기」가 그 줄을 `NOT_APPLIED` 로 닫을 수 있게 되는 기한(줄의 `sent_at` 부터).
         * 정산 작업(자동)은 이 기한 뒤에도 닫지 않는다 — 사람이 누른 맞추기만. 닫은 뒤 늦게 적용되면 다음 맞추기가 결제사 취소(`PROVIDER` 줄)로 잡는다.
         */
        val providerPendingTimeout: Duration = Duration.ofHours(24),
    ) {
        init {
            require(settleDelay > Duration.ZERO) { "skeleton.payment.refund.settle-delay must be positive" }
            require(providerPendingTimeout > Duration.ZERO) { "skeleton.payment.refund.provider-pending-timeout must be positive" }
        }
    }

    data class Webhook(
        /** 웹훅 경로를 연다(`PaymentWebhookGateway` 빈이 있을 때만 뜻이 있다). 수신함 서비스 · 처리 작업은 이 값과 무관하게 선다. */
        val enabled: Boolean = true,
        /**
         * 주소 끝의 비밀 조각(`<base-path>/webhooks/{provider}/{token}`). **서명 없는 결제사(토스의 결제 이벤트)는 이 값이 맞아야 닿는다** — 비어 있으면 그 결제사의 경로는 늘 404 다.
         * 보호 환경(stage · prod)에서는 비었거나 약하면(24자 미만 · 주소에 못 쓰는 글자 · 8가지 미만의 글자) 기동 실패. 비밀이라 [toString] 이 찍지 않는다.
         */
        val pathToken: String = "",
        /** 본문 크기 제한(바이트). 넘으면 413. 1 ~ [MAX_BODY_BYTES_LIMIT](1 MiB — 웹훅 본문에 충분하고, 읽기 산수가 넘치지 않는다). */
        val maxBodyBytes: Int = 65_536,
        /** 호출자(IP)마다 요청 수 한도 — 주소를 확인하기 전에 센다. */
        val rateLimit: RateLimit = RateLimit(),
        /** 주문마다 **새 이벤트 키**의 창 상한 — 전달 id 를 호출자가 정하므로 키를 바꿔 보내는 것으로 줄 · 작업 · 결제사 조회를 늘리지 못하게 한다. */
        val perOrderLimit: PerOrderLimit = PerOrderLimit(),
        /**
         * 끝나지 않은 줄(`FAILED` · `DEAD`)에 결제사가 같은 이벤트를 **다시 보낼 때** 새 작업을 넣어 주는 상한 — **넣어 준 횟수가 아니라 그 줄의 총 전달 수**(`deliveries`, 첫 전달 포함, 이번 전달을 센 값)가 이 값 **이하**일 때만 다시 넣고, 넘으면 `DUPLICATE`.
         * 그래서 기본 10이면 총 10번째 전달까지(재전송 아홉 번), 1 이하이면 재전송은 작업을 한 번도 넣지 않고, 2 면 두 번째 전달 하나다.
         * 실패한 작업은 백오프에 살아 있으므로(큐 기본 10회) 재전송마다 작업을 얹으면 줄당 작업 수가 끝없이 는다. 운영자의 「다시 처리」(`retry`)는 이 값과 무관하다. 0 이상.
         */
        val redeliveryRequeueLimit: Int = 10,
        /** 웹훅을 받은 요청 안에서 그 웹훅의 처리를 한 번 바로 해 보는 길 - 기본 꺼짐(아래 [Inline]). */
        val inline: Inline = Inline(),
    ) {
        init {
            require(maxBodyBytes in 1..MAX_BODY_BYTES_LIMIT) { "skeleton.payment.webhook.max-body-bytes must be between 1 and $MAX_BODY_BYTES_LIMIT" }
            require(redeliveryRequeueLimit >= 0) { "skeleton.payment.webhook.redelivery-requeue-limit must not be negative" }
        }

        override fun toString(): String =
            "Webhook(enabled=$enabled, pathToken=${if (pathToken.isBlank()) "<unset>" else "<redacted>"}, maxBodyBytes=$maxBodyBytes, rateLimit=$rateLimit, perOrderLimit=$perOrderLimit, redeliveryRequeueLimit=$redeliveryRequeueLimit, inline=$inline)"

        /**
         * 한 주문(그 주문의 결제)에 [window] 안에 저장되는 새 줄이 [capacity] 를 넘으면 더 저장하지 않고 받은 것으로만 답한다. 수신함 표의 줄 수로 세므로 인스턴스가 여럿이어도 같은 숫자다.
         * 토스의 정상 재전송(같은 이벤트)은 이 수에 들지 않는다.
         */
        data class PerOrderLimit(
            val capacity: Int = 30,
            val window: Duration = Duration.ofMinutes(10),
        ) {
            init {
                require(capacity >= 1) { "skeleton.payment.webhook.per-order-limit.capacity must be at least 1" }
                require(window > Duration.ZERO) { "skeleton.payment.webhook.per-order-limit.window must be positive" }
            }
        }

        companion object {
            const val MAX_BODY_BYTES_LIMIT = 1_048_576
        }

        /**
         * 웹훅 수신(줄 + 작업, 한 트랜잭션)이 **커밋된 뒤** 같은 요청 안에서 그 웹훅의 처리 작업을 [timeBudget] 안에서 한 번 돌려 본다 - 결제 직후 화면이 일꾼의 차례를 기다리지 않게.
         * 작업을 큐에서 임대해 돌리므로 일꾼과 같은 웹훅을 두 번 반영하지 않는다. 한도를 넘으면 기다리지 않고 바로 `200`(처리는 그 스레드에서 끝까지 이어진다), 실패하면 작업이 지금처럼 일꾼에게 남는다.
         * 기본 꺼짐 - 요청 스레드와 결제사 호출을 쓰는 선택이라 앱이 켠다. 작업 큐가 [PaymentTasksNow] 를 낼 때(`payment-jdbc`)만 뜻이 있다.
         */
        data class Inline(
            val enabled: Boolean = false,
            /** 요청이 처리를 기다리는 최대 시간. 결제사(토스)는 10초 안에 `200` 을 받지 못하면 다시 보내므로 그보다 훨씬 짧게. */
            val timeBudget: Duration = Duration.ofSeconds(3),
        ) {
            init {
                require(!timeBudget.isNegative && !timeBudget.isZero) { "skeleton.payment.webhook.inline.time-budget must be positive" }
            }
        }

        data class RateLimit(
            val capacity: Int = 120,
            val window: Duration = Duration.ofMinutes(1),
        ) {
            init {
                require(capacity >= 1) { "skeleton.payment.webhook.rate-limit.capacity must be at least 1" }
                require(window > Duration.ZERO) { "skeleton.payment.webhook.rate-limit.window must be positive" }
            }
        }
    }

    data class Http(
        /** 장부의 HTTP 경로들의 기준 경로. 웹훅은 그 아래 `/webhooks/{provider}[/{token}]`. */
        val basePath: String = "/api/v1/payments",
        /**
         * 사용자 경로(`config` · 주문 · 승인 · 환불 요청 …)를 연다. **웹훅 경로는 이 값과 무관하다** — 결제사가 보내는 신호는 `webhook.enabled` 로만 닫는다
         * (사용자 화면을 닫아도 이미 일어난 결제의 신호는 받아야 한다).
         */
        val enabled: Boolean = true,
        /** 주문 만들기의 요청 수 한도 — 계정마다 · 호출자 IP 마다. 계정당 주문 수가 웹훅 조회 유발의 전체 상한을 정한다. */
        val orderRateLimit: OrderRateLimit = OrderRateLimit(),
    ) {
        init {
            require(basePath.startsWith("/") && !basePath.endsWith("/")) { "skeleton.payment.http.base-path must start with / and must not end with /" }
        }

        /** [window] 안에 계정은 [accountCapacity] 번, 한 IP 는 [ipCapacity] 번까지 주문을 만든다(넘으면 429). 저장소는 플랫폼의 `RateLimitStore`(Redis 가 있으면 인스턴스끼리 공유). */
        data class OrderRateLimit(
            val accountCapacity: Int = 10,
            val ipCapacity: Int = 30,
            val window: Duration = Duration.ofMinutes(10),
        ) {
            init {
                require(accountCapacity >= 1) { "skeleton.payment.http.order-rate-limit.account-capacity must be at least 1" }
                require(ipCapacity >= 1) { "skeleton.payment.http.order-rate-limit.ip-capacity must be at least 1" }
                require(window > Duration.ZERO) { "skeleton.payment.http.order-rate-limit.window must be positive" }
            }
        }
    }

    /**
     * 운영자 HTTP(`<base-path>` 아래 주문 · 환불 · 수신함). **꺼져 있으면(기본) 경로가 없다 — 404.** 켜져 있으면 [PaymentOperatorPolicy] 를 매 호출 확인한다(거절은 403).
     * 기본 정책은 토큰의 역할([role]).
     */
    data class Admin(
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/payments",
        /** 기본 정책이 보는 역할(`ROLE_<role>`). 앱이 [PaymentOperatorPolicy] 빈을 두면 쓰이지 않는다. */
        val role: String = "ADMIN",
        /**
         * 「오래됨」의 기준 — 이보다 오래 `CONFIRMING` 인 주문(`STALE_CONFIRMING`) · `PENDING` 인 채 남은 환불이 있는 주문(`STALE_REFUND`) ·
         * 지급 · 회수가 안 끝난 채 남은 주문 · 환불이 「확인 필요」 목록에 오르고 상세에 이유로 붙는다.
         */
        val staleAfter: Duration = Duration.ofHours(1),
    ) {
        init {
            require(basePath.startsWith("/") && !basePath.endsWith("/")) { "skeleton.payment.admin.base-path must start with / and must not end with /" }
            require(role.isNotBlank()) { "skeleton.payment.admin.role must not be blank" }
            require(staleAfter > Duration.ZERO) { "skeleton.payment.admin.stale-after must be positive" }
        }
    }

    /**
     * 구독(스펙 `2026-10-09-subscription-design.md`). **모듈은 기본값을 고르지 않는다** — 꺼져 있고, 켜려면 앱이 [SubscriptionPlans] 빈, 빌링 능력을 가진 결제사, 빌링키를 암호화할 `crypto` 키를 낸다
     * (하나라도 없으면 기동 실패).
     */
    data class Subscription(
        val enabled: Boolean = false,
        /** 갱신 과금 — 거절되면 `PAST_DUE` 로 재시도하고 시도를 다 쓰면 구독이 끝난다. */
        val renewal: Renewal = Renewal(),
        /** 플랜 변경(위로만) — 꺼져 있고, 켜는 앱이 과금 방식을 명시한다. */
        val planChange: PlanChange = PlanChange(),
        /** 해지·환불 정책 — 기본은 기간 끝 해지만(중도 해지 닫힘). 나라마다 정책을 갈아 끼운다. */
        val cancel: Cancel = Cancel(),
        /** 일할 계산(플랜 변경 차액 · 중도 해지의 쓴 몫)이 날짜를 가르는 시간대 - 기본 Asia/Seoul. */
        val prorationZone: java.time.ZoneId = Proration.DEFAULT_ZONE,
    ) {
        data class Cancel(
            /** 구독을 만들 때 나라가 오지 않으면 쓰는 나라(ISO-3166 두 글자). 비우면 나라 없이 만든다(기본 정책). */
            val defaultCountry: String? = null,
            /** 나라 → 정책 이름(`korea` 가 내장). 지도에 없는 나라는 기간 끝 해지만(`period-end-only`). 예: `KR: korea`. */
            val policies: Map<String, String> = emptyMap(),
            val korea: Korea = Korea(),
        ) {
            data class Korea(
                /** 중도 해지 때 남은 금액에서 떼는 위약금 비율(%). 기본 0. */
                val penaltyPercent: Int = 0,
                /** 이번 기간 시작 후 이 일수 안이고 안 썼으면 전액 환불(청약 철회). */
                val withdrawalDays: Long = 7,
            ) {
                init {
                    require(penaltyPercent in 0..100) { "skeleton.payment.subscription.cancel.korea.penalty-percent must be between 0 and 100" }
                    require(withdrawalDays >= 0) { "skeleton.payment.subscription.cancel.korea.withdrawal-days must not be negative" }
                }
            }

            init {
                require(defaultCountry == null || COUNTRY.matches(defaultCountry)) { "skeleton.payment.subscription.cancel.default-country must be an ISO-3166 alpha-2 code (two capital letters)" }
                policies.keys.forEach { require(COUNTRY.matches(it)) { "skeleton.payment.subscription.cancel.policies keys must be ISO-3166 alpha-2 codes (two capital letters): $it" } }
            }

            companion object {
                private val COUNTRY = Regex("[A-Z]{2}")
            }
        }

        data class PlanChange(
            val enabled: Boolean = false,
            /** 켰다면 필수 — 모듈은 과금 방식을 고르지 않는다. */
            val charge: Charge? = null,
            /** [Charge.PRORATED_DIFFERENCE] 의 차액이 이 값 미만이면 과금 없이 바꾼다(최소 단위). */
            val minCharge: Long = 0,
        ) {
            enum class Charge {
                /** 전액을 결제하고 지금부터 새 주기를 시작한다(남은 기간은 버린다). */
                FULL_RESTART,

                /** 남은 기간만큼의 차액만 결제하고 기간은 그대로 둔다. */
                PRORATED_DIFFERENCE,
            }

            init {
                require(!enabled || charge != null) { "skeleton.payment.subscription.plan-change.charge must be set (FULL_RESTART or PRORATED_DIFFERENCE) when plan-change.enabled=true" }
                require(minCharge >= 0) { "skeleton.payment.subscription.plan-change.min-charge must not be negative" }
            }
        }

        data class Renewal(
            /** 한 기간의 과금 시도 수(첫 시도 포함). 다 쓰면 구독이 `PAYMENT_FAILED` 로 끝난다. */
            val maxAttempts: Int = 3,
            /** 거절된 갱신을 다시 시도하기까지. */
            val retryInterval: Duration = Duration.ofHours(24),
        ) {
            init {
                require(maxAttempts >= 1) { "skeleton.payment.subscription.renewal.max-attempts must be at least 1" }
                require(retryInterval > Duration.ZERO) { "skeleton.payment.subscription.renewal.retry-interval must be positive" }
            }
        }
    }

    /** 계정 탈퇴(스펙 §8.2). 어느 모드든 `card_issuer` · `card_last4` 는 지워지고 주문 줄은 남는다. */
    data class Erasure(
        /** [AccountIdMode.KEEP](기본) 이면 결제 줄의 계정 id 를 그대로 둔다. [AccountIdMode.TOMBSTONE] 이면 **그 계정이 산 주문의 줄에서만** 계정 id 를 바꾼다. */
        val accountId: AccountIdMode = AccountIdMode.KEEP,
    ) {
        enum class AccountIdMode { KEEP, TOMBSTONE }
    }
}
