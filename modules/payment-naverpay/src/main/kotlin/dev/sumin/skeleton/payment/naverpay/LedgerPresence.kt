package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.payment.PaymentLedgerStore
import org.springframework.beans.factory.ListableBeanFactory

/** 장부([PaymentLedgerStore] 빈)가 있는가. 배포 가드가 "장부가 있을 때만" 보는 줄에 쓴다. 형은 이름 문자열이 아니라 타입으로 가리킨다. */
internal object LedgerPresence {
    fun present(beans: ListableBeanFactory, type: Class<*> = PaymentLedgerStore::class.java): Boolean =
        beans.getBeanNamesForType(type, true, false).isNotEmpty()
}
