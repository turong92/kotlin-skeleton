package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * 로컬 개발용 나라 스위치(`skeleton.payment.routing.dev-country`)가 켜진 채 보호 환경(stage · prod)에 뜨지 못하게 한다 - 켜져 있으면 방문자의 실제 나라와 상관없이
 * 결제사와 환불 규칙이 한 나라로 고정된다. 그 밖의 환경에서는 경고만. 메시지에는 속성 · 환경변수 이름만 적는다.
 */
class PaymentCountryDeployGuard(private val properties: PaymentProperties) : DeployGuard {
    override val name: String = "payment-country"

    override fun problems(context: DeployContext): List<String> = if (context.protectedEnv) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedEnv) emptyList() else findings()

    private fun findings(): List<String> =
        if (properties.routing.devCountry == null) emptyList()
        else listOf("skeleton.payment.routing.dev-country (<P>_PAYMENT_ROUTING_DEV_COUNTRY) is set: it fixes every visitor's country and so the payment provider and the refund rules; it is for local development only - remove it")
}
