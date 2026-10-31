package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * FCM 을 켠 채 서비스 계정이 비었거나 읽을 수 없으면 보호 환경(stage · prod)이 뜨지 않게 한다. 메시지에는 속성 · 환경변수 이름만 적는다(값 없음).
 * 보호 환경 밖에서는 같은 이유를 경고로만 남기고 센더를 세우지 않는다(기기 등록이 400 으로 거절된다).
 */
class FcmDeployGuard(private val properties: FcmProperties) : DeployGuard {
    override val name: String = "notification-fcm"

    override fun problems(context: DeployContext): List<String> = if (context.protectedEnv) FcmConfigCheck.problems(properties) else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedEnv) emptyList() else FcmConfigCheck.problems(properties)
}
