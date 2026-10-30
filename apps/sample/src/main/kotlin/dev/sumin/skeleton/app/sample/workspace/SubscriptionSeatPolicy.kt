package dev.sumin.skeleton.app.sample.workspace

import dev.sumin.skeleton.payment.PaymentSubscriptions
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceSeatPolicy
import dev.sumin.skeleton.workspace.Workspaces
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 좌석 상한(멤버 + 대기 초대)은 **소유자의 구독**이 정한다 - 쓰고 있는 구독(해지 예약 포함)이 있으면 [PAID_LIMIT], 아니면 [FREE_LIMIT].
 * 구독을 읽기만 한다(결제 코드는 건드리지 않는다). 소유자 줄이 없는(닫힌) 워크스페이스는 무료 상한이다.
 */
class SubscriptionSeatPolicy(
    private val ownerOf: (workspaceId: String) -> String?,
    private val hasActiveSubscription: (accountId: String) -> Boolean,
) : WorkspaceSeatPolicy {
    override fun limitOf(workspaceId: String): Int {
        val owner = ownerOf(workspaceId)
        return if (owner != null && hasActiveSubscription(owner)) PAID_LIMIT else FREE_LIMIT
    }

    companion object {
        const val FREE_LIMIT = 3
        const val PAID_LIMIT = 10
    }
}

@Configuration(proxyBeanMethods = false)
class SeatPolicyConfiguration {
    /**
     * `Workspaces` 는 이 정책을 쓰는 서비스이기도 해서 호출 때 늦게 꺼낸다(순환 방지). 구독 서비스는 구독을 켠 앱에만 있다 -
     * 없으면(키 없는 로컬 · 시험) 모두 무료 상한이다.
     */
    @Bean
    fun workspaceSeatPolicy(workspaces: ObjectProvider<Workspaces>, subscriptions: ObjectProvider<PaymentSubscriptions>): WorkspaceSeatPolicy =
        SubscriptionSeatPolicy(
            ownerOf = { id -> workspaces.getObject().membersOf(id).firstOrNull { it.role == WorkspaceRole.OWNER }?.accountId },
            hasActiveSubscription = { account -> subscriptions.getIfAvailable()?.current(account)?.status?.isInUse == true },
        )
}
