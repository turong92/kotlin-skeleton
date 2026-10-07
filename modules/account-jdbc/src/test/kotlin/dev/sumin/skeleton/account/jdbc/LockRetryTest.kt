package dev.sumin.skeleton.account.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DeadlockLoserDataAccessException

/** A deadlock or lock timeout loses the whole transaction - the OUTERMOST caller repeats it a few times; an inner caller inside a transaction must not (the transaction is already gone) */
class LockRetryTest {
    @Test
    fun `a deadlock loser and a lock timeout are repeated and the third try's result is returned`() {
        var calls = 0
        val result = LockRetry.run { calls++; if (calls == 1) throw DeadlockLoserDataAccessException("deadlock", RuntimeException("db")) else if (calls == 2) throw CannotAcquireLockException("timeout") else "ok" }
        assertEquals("ok", result); assertEquals(3, calls)
    }

    @Test
    fun `after the tries are used up the failure is the caller's - not swallowed`() {
        var calls = 0
        assertFailsWith<DeadlockLoserDataAccessException> { LockRetry.run { calls++; throw DeadlockLoserDataAccessException("deadlock", RuntimeException("db")) } }
        assertEquals(LockRetry.TRIES, calls)
    }

    @Test
    fun `other failures are not repeated`() {
        var calls = 0
        assertFailsWith<IllegalStateException> { LockRetry.run { calls++; error("boom") } }
        assertEquals(1, calls)
    }

    @Test
    fun `inside an open transaction nothing is repeated - the outer owner of the transaction does that`() {
        var calls = 0
        // no real connection is needed: the helper only asks whether a transaction is active on this thread
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization()
        try {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true)
            assertFailsWith<DeadlockLoserDataAccessException> { LockRetry.run { calls++; throw DeadlockLoserDataAccessException("deadlock", RuntimeException("db")) } }
            assertEquals(1, calls)
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false)
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
