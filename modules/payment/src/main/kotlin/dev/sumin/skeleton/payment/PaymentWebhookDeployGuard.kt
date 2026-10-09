package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * 서명 없는 결제사의 웹훅(토스의 결제 이벤트)은 주소의 비밀 조각(`skeleton.payment.webhook.path-token`)이 유일한 진위 확인이다(스펙 §9.3).
 * 그런 결제사가 있고 웹훅을 켰는데 조각이 **비었거나 약하면** 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고다.
 * 약함 = 24자 미만 · 주소에 못 쓰는 글자(영문 · 숫자 · `-` `.` `_` `~` 밖) · 서로 다른 글자 8개 미만. 서명 있는 결제사만 있거나 웹훅을 껐으면 할 말이 없다.
 *
 * 메시지에는 **속성 · 환경변수 이름만** 적고 값은 싣지 않는다.
 *
 * @param unsignedProviders 웹훅 능력이 있고 서명이 없는(켜져 있는) 결제사 id — 가드가 돌 때 읽는다(빈 생성 순서에 기대지 않는다)
 */
class PaymentWebhookDeployGuard(
    private val properties: PaymentProperties,
    private val unsignedProviders: () -> List<String>,
) : DeployGuard {
    override val name: String = "payment"

    override fun problems(context: DeployContext): List<String> = if (context.protectedEnv) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedEnv) emptyList() else findings()

    private fun findings(): List<String> {
        if (!properties.webhook.enabled || unsignedProviders().isEmpty()) return emptyList()
        val token = properties.webhook.pathToken
        return when {
            WebhookPathToken.isEmpty(token) -> listOf(
                "$PROPERTY ($ENV_NAME) is empty but a payment provider whose webhooks carry no signature is present: its webhook address would have no secret part, so the route stays closed (404) and no webhook can arrive; " +
                    "set a random secret of at least ${WebhookPathToken.MIN_LENGTH} URL-safe characters, or turn the webhook route off (skeleton.payment.webhook.enabled=false)",
            )
            WebhookPathToken.isWeak(token) -> listOf(
                "$PROPERTY ($ENV_NAME) is too weak for a payment provider whose webhooks carry no signature: the secret part of the address is its only proof; " +
                    "use at least ${WebhookPathToken.MIN_LENGTH} characters from A-Z a-z 0-9 - . _ ~ with at least ${WebhookPathToken.MIN_DISTINCT} different ones",
            )
            else -> emptyList()
        }
    }

    private companion object {
        const val PROPERTY = "skeleton.payment.webhook.path-token"
        const val ENV_NAME = "<P>_PAYMENT_WEBHOOK_PATH_TOKEN"
    }
}
