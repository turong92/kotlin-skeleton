package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.consent.SignUpConsentGate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 가입에 약관 동의를 묶는 고리 — `legal` 없이, 가짜 고리로 `account` 쪽 규칙만 본다 */
class SignUpConsentTest {
    private val claims = listOf(ConsentClaim("terms", "v2", "ko"), ConsentClaim("privacy", "v1"), ConsentClaim("marketing", "v1", "en"))

    /** 트랜잭션 · 저장소 · 고리가 일어난 순서를 한 줄로 */
    private val trace = CopyOnWriteArrayList<String>()

    private class RecordingTx(private val trace: MutableList<String>) : AccountTransaction {
        @Volatile var active = false
        override fun <T> run(block: () -> T): T {
            trace += "begin"; active = true
            try { return block() } finally { active = false; trace += "end" }
        }
    }

    private class FakeGate(private val trace: MutableList<String>, private val tx: RecordingTx, var refuse: Boolean = false, var failOnRecord: Boolean = false) : SignUpConsentGate {
        val checks = CopyOnWriteArrayList<List<ConsentClaim>>()
        val records = CopyOnWriteArrayList<Triple<String, List<ConsentClaim>, ConsentContext>>()
        var recordedInsideTransaction: Boolean? = null

        override fun check(claims: List<ConsentClaim>) {
            checks += claims
            trace += "check"
            if (refuse) throw ApplicationException("consent required", PlatformErrorCode.VALIDATION_FAILED)
        }

        override fun record(accountId: String, claims: List<ConsentClaim>, context: ConsentContext) {
            recordedInsideTransaction = tx.active
            trace += "record"
            if (failOnRecord) error("recording failed")
            records += Triple(accountId, claims, context)
        }
    }

    private val tx = RecordingTx(trace)
    private val gate = FakeGate(trace, tx)

    /** 저장소 쓰기 순서를 trace 에 남긴다 — 하나의 메모리 저장소를 감싼다 */
    private val real = InMemoryAccountRepository()
    private val storage = object : AccountRepository by real {
        override fun insert(account: Account, identities: List<Identity>): Boolean { trace += "insert"; return real.insert(account, identities) }
        override fun proveMailbox(id: String, now: java.time.Instant, proof: MailboxProof): Boolean { trace += "prove"; return real.proveMailbox(id, now, proof) }
    }

    private fun harness(consents: SignUpConsentGate? = gate, props: AccountProperties = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
    )) = AccountHarness(props = props, storage = storage, consents = consents, atomic = tx)

    private fun AccountHarness.signUpWith(email: String = "ann@example.com", list: List<ConsentClaim> = claims, ip: String? = "203.0.113.1", ua: String? = "Mozilla/5.0 test") =
        registration.signUp(SignUpCommand(email, "tangerine-42-moon", "Ann", "ko", "Asia/Seoul", ip, null, consents = list, userAgent = ua))
            .also { o -> o.signUpId?.let { signUpIds[Emails.normalize(email)] = it } }

    @Test
    fun `the claims are checked when the attempt opens, and a refusal stops everything before a store read, a mail or an attempt`() {
        gate.refuse = true
        val h = harness()
        assertFailsWith<ApplicationException> { h.signUpWith() }
        assertEquals(listOf("check"), trace)
        assertEquals(emptyList(), h.callLog.by(Thread.currentThread()), "the account store was not even read")
        assertEquals(0, h.mailCount())
        assertNull(h.challengeStore.findOpen(dev.sumin.skeleton.account.challenge.ChallengePurposes.SIGN_UP, "ann@example.com", h.time.now()))
    }

    @Test
    fun `the refusal does not depend on the address, so it cannot be used to tell registered addresses apart`() {
        val h = harness()
        h.activeAccount("taken@example.com")   // this address has an account now
        gate.refuse = true
        trace.clear()

        val newAddress = assertFailsWith<ApplicationException> { h.signUpWith("brand-new@example.com") }
        val takenAddress = assertFailsWith<ApplicationException> { h.signUpWith("taken@example.com") }

        assertEquals(newAddress.errorCode, takenAddress.errorCode)
        assertEquals(newAddress.message, takenAddress.message)
        assertEquals(listOf("check", "check"), trace)
        assertEquals(listOf(claims, claims), gate.checks.takeLast(2))
    }

    @Test
    fun `nothing is recorded before the code is verified, abandoned attempts leave no consent`() {
        val h = harness()
        h.signUpWith()
        h.registration.resendVerification(h.signUpIds.getValue("ann@example.com"), "203.0.113.1", null)

        assertEquals(emptyList(), gate.records)
        assertTrue("record" !in trace)
    }

    @Test
    fun `verifying records what the attempt carried, for the new account, in the transaction that creates it`() {
        val h = harness()
        h.signUpWith(ip = "203.0.113.7", ua = "Mozilla/5.0 test")
        trace.clear()

        h.verify()

        val account = h.repo.findByEmail("ann@example.com")!!
        val (accountId, recorded, context) = gate.records.single()
        assertEquals(account.id, accountId)
        assertEquals(claims, recorded)
        assertEquals(ConsentContext("203.0.113.7", "Mozilla/5.0 test"), context, "the client of the sign-up request, where the boxes were ticked")
        assertEquals(true, gate.recordedInsideTransaction)
        assertEquals(listOf("begin", "insert", "record", "end"), trace)
    }

    @Test
    fun `a wrong code or an attacker's attempt on a registered address records nothing`() {
        val h = harness()
        h.signUpWith("owner@example.com")
        h.verify("owner@example.com")
        gate.records.clear()
        h.signUpWith("owner@example.com")   // someone else starts a sign-up for the registered address

        assertFailsWith<ApplicationException> { h.registration.verifyEmail(h.signUpIds.getValue("owner@example.com"), "000000", "198.51.100.1") }

        assertEquals(emptyList(), gate.records)
    }

    @Test
    fun `a recording that fails fails the verification inside the same transaction, so the account is not committed`() {
        val h = harness()
        h.signUpWith()
        gate.failOnRecord = true
        trace.clear()

        assertFailsWith<IllegalStateException> { h.verify() }

        assertEquals(listOf("begin", "insert", "record", "end"), trace, "both writes ran inside one begin/end - a real transaction rolls the insert back")
        assertEquals(emptyList(), gate.records)
    }

    @Test
    fun `a sign-up without a claim list carries an empty list, and an app without the module ignores the claims`() {
        val h = harness()
        h.signUpWith(list = emptyList())
        h.verify()
        assertEquals(emptyList(), gate.records.single().second)

        val without = harness(consents = null)
        without.signUpWith("other@example.com")
        without.verify("other@example.com")
        assertEquals(1, gate.records.size, "no gate, nothing recorded")
    }

    @Test
    fun `without email verification the consent is recorded together with the account at once`() {
        val h = harness(props = AccountProperties(
            signUp = AccountProperties.SignUp(emailVerification = false),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ))
        trace.clear()

        val outcome = h.signUpWith()

        assertEquals(SignUpStatus.CREATED, outcome.status)
        assertEquals(listOf("check", "begin", "insert", "record", "end"), trace)
        assertEquals(h.repo.findByEmail("ann@example.com")!!.id, gate.records.single().first)
    }

    @Test
    fun `a sign-up proves the mailbox of an old unverified account and records its consent in that transaction`() {
        val h = harness(props = AccountProperties(
            signUp = AccountProperties.SignUp(emailVerification = false),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ))
        h.signUpWith()   // leaves an unverified account (the app turned verification on later)
        val later = harness()
        // the second harness shares the storage: verification is on now
        later.signUpWith()
        gate.records.clear(); trace.clear()

        later.verify()

        assertEquals(listOf("begin", "prove", "record", "end"), trace)
        assertEquals(1, gate.records.size)
    }

    @Test
    fun `the claims in the stored attempt stay small enough for the attempt column even at the limits`() {
        val h = harness()
        val maximal = (1..SignUpCommand.MAX_CONSENTS).map { ConsentClaim("t".repeat(32), "v".repeat(32), "l".repeat(35)) }
        h.signUpWith(list = maximal, ua = "u".repeat(500))

        val payload = h.challengeStore.findOpen(dev.sumin.skeleton.account.challenge.ChallengePurposes.SIGN_UP, "ann@example.com", h.time.now())!!.payload!!

        assertTrue(payload.length < 2000, "payload is ${payload.length} chars; the column holds 2000")
        h.verify()
        assertEquals(maximal, gate.records.single().second)
        assertEquals(120, gate.records.single().third.userAgent!!.length)
    }
}
