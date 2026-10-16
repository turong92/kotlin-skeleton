package dev.sumin.skeleton.payment

/**
 * 결제사 설정을 읽는 길 — 결제사 모듈이 자기 properties(`skeleton.payment-<결제사>.*`)를 이걸로 묶는다.
 * 프로젝트 모드에서는 공통 값 위에 **그 프로젝트의 `config`** 를 덮어 묶고, 프로젝트가 없으면 서버 전체 값 그대로다.
 */
interface PaymentProviderSettings {
    /** 프로젝트 모드의 프로젝트 id. 프로젝트 목록이 없는 앱이면 null */
    val projectId: String?

    fun <T : Any> bind(prefix: String, type: Class<T>): T

    /** [block] 을 이 프로젝트의 문맥으로 돌린다(프로젝트가 없는 앱이면 그냥 돌린다) — 결제사 응답 스레드처럼 프로젝트 문맥이 없는 곳에서 부르는 고리(경보 …)를 게이트웨이를 만들 때 감싼다 */
    fun <R> inProject(block: () -> R): R
}

/**
 * **결제사 모듈이 프로젝트 모드를 위해 내는 한 가지 모양.** 결제사 모듈(토스 · Paddle · Stripe · 네이버페이 · 카카오페이 …)은 `@Conditional(ProjectsConfigured)` 빈으로 이걸 하나 두고,
 * 프로젝트마다 불릴 때 [PaymentProviderSettings.bind] 로 자기 properties 를 묶어 게이트웨이를 만든다. 이 프로젝트에서 안 켰거나 비밀이 비었으면 null — 그 프로젝트의 결제 화면 · 설정 응답에 나오지 않는다.
 * 프로젝트마다 게이트웨이(= 그 프로젝트의 키를 든 결제사 클라이언트)가 따로 생기고, 부를 때 지금 프로젝트의 것으로 넘어간다([PaymentProviderRouter]).
 * 프로젝트 목록이 비면 쓰이지 않는다 — 결제사 모듈의 옛 게이트웨이 빈이 그대로 선다.
 */
interface PaymentGatewaySource {
    /**
     * 이 결제사의 **비밀 값**을 담은 설정 키(`skeleton.` 아래, 예: `payment-toss.secret-key`). 라우터가 이것으로 계정을 따로 쓰게 강제한다 —
     * 이 결제사를 켠 프로젝트가 비밀을 공통 설정에서 물려받았거나 서비스 중인 두 프로젝트의 비밀이 같으면 기동 실패(프로젝트마다 결제사 계정 · 키를 따로).
     * 비밀이 없는 결제사는 비운다.
     */
    val secretKeys: List<String> get() = emptyList()

    fun create(settings: PaymentProviderSettings): PaymentGateway?

    companion object {
        /** 비밀 키 선언 없이 만드는 간단한 소스 */
        operator fun invoke(create: (PaymentProviderSettings) -> PaymentGateway?): PaymentGatewaySource = of(emptyList(), create)

        fun of(secretKeys: List<String>, create: (PaymentProviderSettings) -> PaymentGateway?): PaymentGatewaySource = object : PaymentGatewaySource {
            override val secretKeys = secretKeys
            override fun create(settings: PaymentProviderSettings): PaymentGateway? = create(settings)
        }
    }
}
