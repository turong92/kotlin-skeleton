package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.PaymentLedgerStore

/** 메모리 대역이 [PaymentLedgerStoreContract] 를 지킨다 — `payment-jdbc` 의 진짜 구현도 같은 계약을 상속한다. */
class InMemoryPaymentLedgerStoreTest : PaymentLedgerStoreContract() {
    override fun newStore(): PaymentLedgerStore = InMemoryPaymentLedgerStore()
}
