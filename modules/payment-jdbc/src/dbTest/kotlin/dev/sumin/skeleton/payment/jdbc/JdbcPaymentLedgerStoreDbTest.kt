package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.testing.PaymentLedgerStoreContract

/** 진짜 DB 의 저장소가 메모리 대역과 **같은 계약**(한 트랜잭션 한 주문 · 잠그지 않은 줄 쓰기 금지 · 모든 칸 왕복 · 16 스레드 경합 · 롤백 뒤 키 해제)을 지킨다. */
class JdbcPaymentLedgerStoreDbTest : PaymentLedgerStoreContract() {
    override fun newStore(): PaymentLedgerStore {
        PaymentDb.clean()
        return PaymentDb.newStore()
    }
}
