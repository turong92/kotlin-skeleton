package dev.sumin.skeleton.account

import java.time.Instant

enum class AccountStatus {
    /** 로그인할 수 있다 */
    ACTIVE,

    /** 가입했지만 이메일을 아직 확인하지 않았다 — 비밀번호가 맞아도 로그인은 AUTH.EMAIL_NOT_VERIFIED */
    PENDING_VERIFICATION,

    /** 운영자가 막았다 — 세션이 닫히고 로그인 · 새로고침이 AUTH.ACCOUNT_SUSPENDED */
    SUSPENDED,

    /** 삭제 요청됨(유예 기간) 또는 이미 지워짐. 로그인할 수 없고 존재하지 않는 계정처럼 보인다 */
    DELETED,
}

/**
 * 계정. 로그인 수단(이메일+비밀번호 · 소셜 · 매직 링크 …)은 여기 없다 — [Identity] 행으로 따로 붙는다.
 * [email] 은 연락 · 알림용 대표 주소이고(없을 수 있다 — 이메일을 안 주는 소셜 가입), [emailVerified] 는 그 주소가 확인됐는지다.
 */
data class Account(
    val id: String,
    val email: String?,
    val emailVerified: Boolean,
    val status: AccountStatus,
    val roles: Set<String>,
    val displayName: String?,
    val locale: String?,
    val timeZone: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant? = null,
    val suspendedReason: String? = null,
    val deletedAt: Instant? = null,
    /** 삭제 유예가 끝나 지워질 시각 (DELETED 일 때) */
    val purgeAfter: Instant? = null,
)

/**
 * 계정에 붙은 로그인 수단 한 줄. [method] 는 문자열 코드(`password` · `magic_link` · `google` …) — 새 수단이 스키마 변경 없이 같은 표에 들어온다.
 * [subject] 는 그 수단 안에서의 식별자(이메일 · 제공자의 사용자 id). (method, subject) 는 전체에서 유일하다.
 * [secret] 은 수단이 필요로 할 때만(비밀번호 해시) — 다른 수단은 null. [metadata] 는 수단이 자유롭게 쓰는 짧은 JSON.
 */
data class Identity(
    val id: String,
    val accountId: String,
    val method: String,
    val subject: String,
    val verified: Boolean,
    val secret: String? = null,
    val metadata: String? = null,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
) {
    // 비밀번호 해시가 로그에 찍히지 않게
    override fun toString() = "Identity(id=$id, accountId=$accountId, method=$method, verified=$verified, secret=${if (secret == null) "none" else "<redacted>"})"
}

/** 부분 수정 — null 인 필드는 바꾸지 않는다 (지우기는 별도 플래그) */
data class AccountPatch(
    val displayName: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val status: AccountStatus? = null,
    val lastLoginAt: Instant? = null,
    val suspendedReason: String? = null,
    val clearSuspendedReason: Boolean = false,
    val deletedAt: Instant? = null,
    val purgeAfter: Instant? = null,
    val clearDeletion: Boolean = false,
)

data class AccountPage(val items: List<Account>, val total: Long)

/** [STALE]: 호출자가 본 계정의 이메일 확인 상태([AccountRepository.changeEmail] 의 `expectEmailVerified`)가 계정 행 락 안에서 이미 바뀌어 있다 — 그 사이 메일함이 증명됐다 */
enum class ChangeEmailResult { CHANGED, TAKEN, NOT_FOUND, STALE }

/** [AccountRepository.addIdentityIfEmailVerified] 의 결과 — [DUPLICATE]: (method, subject) 가 이미 있다 · [STALE]: 호출자가 본 이메일 확인 상태가 이미 바뀌었다 */
enum class AddIdentityResult { ADDED, DUPLICATE, STALE }

/**
 * [AccountRepository.proveMailbox] 에 넘기는 "메일함이 증명됐다" 의 내용.
 * [keepIdentityIds]: 계정의 이메일이 **미확인이었다면** 이 id 들 말고 모든 로그인 수단을 지운다 (증명한 수단 · 방금 입력된 비밀번호의 수단만 남는다) — 이미 확인된 계정에서는 지우지 않는다.
 * [passwordSecret]: null 이 아니면 비밀번호 수단(주체 = 계정 이메일)의 해시를 이것으로 바꾼다. 수단이 없으면 [newPasswordIdentityId] 로 만든다.
 */
data class MailboxProof(
    val keepIdentityIds: Set<String> = emptySet(),
    val passwordSecret: String? = null,
    val newPasswordIdentityId: String? = null,
) {
    override fun toString() = "MailboxProof(keep=$keepIdentityIds, passwordSecret=${if (passwordSecret == null) "none" else "<redacted>"})"
}

enum class RemoveIdentityResult { REMOVED, NOT_FOUND, LAST }

/** 마지막 관리자 보호 연산의 결과 — [DONE] 적용됨 · [LAST] 마지막 ACTIVE 보유자라 하지 않음 · [NOT_FOUND] 없는 계정(이거나 그 역할이 없음) */
enum class GuardedResult { DONE, LAST, NOT_FOUND }

/**
 * 계정 저장소 포트 — `account-jdbc` 가 PostgreSQL · MySQL 로 구현하고, 메모리 구현([InMemoryAccountRepository])이 로컬 · 시험 기본이다.
 * 구현은 아래 원자성을 지킨다 (둘 이상의 행을 건드리는 연산은 한 트랜잭션):
 *  - [insert]: 계정 + 첫 로그인 수단들. 이메일이나 (method, subject) 가 겹치면 **유니크 위반으로** false — 먼저 조회해 보고 넣는 방식으로 판정하지 않는다
 *  - [markEmailVerified]: 계정의 emailVerified, PENDING_VERIFICATION → ACTIVE, 이메일 계열 수단의 verified 를 함께
 *  - [changeEmail]: 계정 이메일과 이메일 계열 수단(password · magic_link)의 subject 를 함께. 겹치면 TAKEN
 */
interface AccountRepository {
    fun insert(account: Account, identities: List<Identity>): Boolean

    fun findById(id: String): Account?

    /** [email] 은 이미 정규화된(소문자 · 공백 제거) 값 */
    fun findByEmail(email: String): Account?

    fun findByIdentity(method: String, subject: String): Account?

    fun update(id: String, patch: AccountPatch, now: Instant): Account?

    fun markEmailVerified(id: String, now: Instant): Boolean

    /**
     * 계정의 메일함이 방금 증명됐다 — **한 트랜잭션**(계정 행 락)으로: ① 이메일이 미확인이었다면 [MailboxProof.keepIdentityIds] 밖의 로그인 수단을 모두 지운다
     * (미확인 주소에 남이 심어 둔 비밀번호 · 소셜 연결이 메일함 주인의 증명으로 살아남지 않게) ② [MailboxProof.passwordSecret] 이 있으면 비밀번호 수단을 그 해시로
     * ③ [markEmailVerified] 와 같은 확인 처리. 계정이 없거나 이메일이 없으면 false (아무것도 바꾸지 않는다).
     * 같은 계정의 동시 연산(비밀번호 변경 …)과 줄 서므로, 지워진 수단에 뒤늦게 쓰는 쪽은 갱신 행 수 0 으로 진다.
     */
    fun proveMailbox(id: String, now: Instant, proof: MailboxProof): Boolean

    /**
     * 계정 행 락 안에서 [expectEmailVerified] 가 지금의 `email_verified` 와 같을 때만 바꾼다(null 이면 검사 없음) — 다시 인증이 **그 값을 본 뒤** 메일함 증명이 끼어들었으면 [ChangeEmailResult.STALE]
     * (증명 전에 시작한 요청이 증명 뒤에 주소를 가져가지 못하게)
     */
    fun changeEmail(id: String, newEmail: String, now: Instant, expectEmailVerified: Boolean? = null): ChangeEmailResult

    fun grantRole(id: String, role: String, now: Instant): Boolean

    fun revokeRole(id: String, role: String, now: Instant): Boolean

    /** [role] 을 가진 ACTIVE 계정 수 — 마지막 관리자 보호 · 첫 관리자 부트스트랩 */
    fun countActiveWithRole(role: String): Long

    fun search(email: String?, status: AccountStatus?, page: Int, size: Int): AccountPage

    /** 삭제 유예가 [now] 까지 끝난 계정 */
    fun dueForPurge(now: Instant, limit: Int): List<Account>

    /**
     * [patch] 를 적용하되, [guardRole] 의 **마지막 ACTIVE 보유자**를 ACTIVE 밖으로 옮기는 변경이면 하지 않는다([GuardedResult.LAST]).
     * 보유자 수를 읽고 쓰는 것이 **원자적**이어야 한다 — 두 관리자가 서로를 동시에 정지 · 삭제해 관리자가 0 이 되지 않게 (보유자 행 락 · 한 트랜잭션).
     */
    fun updateUnlessLast(id: String, patch: AccountPatch, now: Instant, guardRole: String): GuardedResult

    /** [role] 을 회수하되 그 역할의 마지막 ACTIVE 보유자면 하지 않는다 — [updateUnlessLast] 와 같은 원자성 */
    fun revokeRoleUnlessLast(id: String, role: String, now: Instant): GuardedResult

    /** 삭제 유예가 **아직 안 끝난** DELETED 계정을 [status] 로 되살린다 (한 문장 조건부 갱신 — [purge] 와 동시에 둘 다 이기지 못한다). 되살렸으면 true */
    fun restore(id: String, status: AccountStatus, now: Instant): Boolean

    /** 계정과 그 수단 · 역할을 지운다 — **DELETED 이고 유예가 [now] 까지 끝난 계정만** (되살려진 계정을 지우지 않게). 지웠으면 true */
    fun purge(id: String, now: Instant): Boolean

    // ---- identities

    /** (method, subject) 가 이미 있으면 false (유니크 위반) */
    fun addIdentity(identity: Identity): Boolean

    /**
     * [addIdentity] 인데 계정 행 락 안에서 `email_verified` 가 [expectEmailVerified] 와 같을 때만 — 다시 인증을 통과한 뒤 제공자 교환을 기다리는 사이 메일함이 증명됐다면,
     * 증명이 지운 수단이 뒤늦게 되살아나지 않는다 ([AddIdentityResult.STALE])
     */
    fun addIdentityIfEmailVerified(identity: Identity, expectEmailVerified: Boolean): AddIdentityResult

    fun findIdentity(method: String, subject: String): Identity?

    fun findIdentityById(id: String): Identity?

    fun identitiesOf(accountId: String): List<Identity>

    fun removeIdentity(accountId: String, identityId: String): Boolean

    /**
     * 로그인 수단을 지우되 [credentialMethods] 에 속한 수단이 그것 하나뿐이면 지우지 않는다(LAST) — **원자적**이어야 한다:
     * 두 수단을 동시에 지우려는 두 요청이 모두 "다른 게 남아 있다" 고 보고 둘 다 지우면 계정이 잠긴다 (계정 행 락 · 한 문장 조건부 삭제).
     */
    fun removeIdentityUnlessLast(accountId: String, identityId: String, credentialMethods: Collection<String>): RemoveIdentityResult

    /** [expectedSecret] 가 null 이 아니면 저장된 값이 그것과 같을 때만 (`where id = :id and secret = :old`) — 해시 업그레이드가 뒤늦게 옛 비밀번호를 되살리지 못하게 */
    fun updateIdentitySecret(identityId: String, secret: String?, expectedSecret: String? = null): Boolean

    fun touchIdentity(identityId: String, now: Instant)
}
