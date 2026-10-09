package dev.sumin.skeleton.account

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 계정 수명주기 설정. 기본값은 "기능을 바꾸지 않는" 쪽이다 — 사용 방식을 정하는 값(관리자 HTTP · 소셜 가입 · 이메일 자동 병합 · 시드 계정 · 첫 관리자)은
 * 전부 꺼진 채 시작하고, 앱이 자기 yml 에서 명시적으로 켠다.
 */
@ConfigurationProperties("skeleton.account")
data class AccountProperties(
    /** 가입할 때 계정에 주는 역할 */
    val defaultRoles: Set<String> = setOf("USER"),
    val signUp: SignUp = SignUp(),
    val password: Password = Password(),
    val verification: Verification = Verification(),
    val reset: Reset = Reset(),
    val emailChange: EmailChange = EmailChange(),
    val email: Email = Email(),
    val contactEmail: ContactEmail = ContactEmail(),
    val deletion: Deletion = Deletion(),
    val cleanup: Cleanup = Cleanup(),
    val blocks: Blocks = Blocks(),
    val login: Login = Login(),
    val mail: Mail = Mail(),
    val admin: Admin = Admin(),
    val bootstrap: Bootstrap = Bootstrap(),
    val social: Social = Social(),
    val seed: Seed = Seed(),
    val displayName: DisplayName = DisplayName(),
    val loginId: LoginId = LoginId(),
    val captcha: Captcha = Captcha(),
    val audit: Audit = Audit(),
    val http: Http = Http(),
) {
    data class SignUp(
        /** false 면 가입을 닫는다 (ACCOUNT.SIGN_UP_CLOSED) — 초대 · 시드 계정만 쓰는 서비스 */
        val enabled: Boolean = true,
        /**
         * true: 이메일 확인 뒤에야 로그인한다. 가입 응답은 늘 같은 202 (계정 존재 여부를 알리지 않는다).
         * false: 확인 없이 바로 ACTIVE — 이미 있는 이메일이면 409 ACCOUNT.EMAIL_TAKEN 으로 존재가 드러난다 (앱이 감수하는 선택)
         */
        val emailVerification: Boolean = true,
        /** 같은 IP 가 창 안에 보낼 수 있는 가입 요청 수 */
        val perIp: Int = 10,
        val perIpWindow: Duration = Duration.ofHours(1),
        /** 이미 계정이 있는 주소로 가입을 요청했을 때 나가는 "이미 계정이 있어요" 메일 */
        val existingAccountMail: ExistingAccountMail = ExistingAccountMail(),
    ) {
        data class ExistingAccountMail(
            /**
             * false(기본): 메일에는 **토큰 없는 링크만** — 로그인 페이지 · 가입 수단 · 비밀번호 재설정 **요청** 페이지(`mail.forgot-path`). 남이 넣은 가입 요청이 주인의 재설정 링크를
             * 무효화하거나 재설정 · 매직 링크 한도(`reset.per-email` · `auth-magic-link.per-email`)를 바닥낼 수 없다.
             * true: 최초 가입 요청의 메일에 재설정 링크와 (`auth-magic-link` 가 있으면) 1회용 매직 링크도 싣는다 — 그 주소에 **열린 토큰이 없을 때만** 새로 만들고(열린 것을 닫지 않는다, 있으면 그 줄은 빠진다),
             * 재전송(`/account/verification/resend`)은 토큰을 다시 만들지 않는다. 한계: 주인이 요청하지 않은 링크가 주인의 메일함에 한 번은 간다 (docs/accounts.md 위협 모델)
             */
            val includeCredentialsLinks: Boolean = false,
        )
    }

    data class Password(
        val minLength: Int = 10,
        /** 비밀번호의 UTF-8 바이트 상한 (글자 수가 아니다) — bcrypt 가 72바이트까지만 읽으므로 기본 72. argon2 로 바꾸면 늘려도 된다 */
        val maxBytes: Int = 72,
        val requireLetter: Boolean = true,
        val requireDigit: Boolean = true,
        val requireSymbol: Boolean = false,
        /** 비밀번호가 이메일의 앞부분(@ 앞)을 포함하면 거부 */
        val forbidEmailLocalPart: Boolean = true,
        /** 내장된 흔한 비밀번호 몇십 개를 거부 (네트워크 호출 없음). 더 큰 목록 · HIBP 같은 외부 조회는 BreachedPasswordCheck 빈으로 */
        val denyCommon: Boolean = true,
        /** 새 해시 방식: bcrypt (추가 의존 없음) | argon2 (BouncyCastle 이 클래스패스에 있어야 한다). 옛 해시는 로그인 때 새 방식으로 올라간다 */
        val encoder: String = "bcrypt",
        val bcryptStrength: Int = 10,
    ) {
        init {
            require(minLength in 1..maxBytes) { "skeleton.account.password.min-length must be between 1 and max-bytes" }
            require(encoder == "bcrypt" || encoder == "argon2") { "skeleton.account.password.encoder must be bcrypt or argon2" }
            require(encoder != "bcrypt" || maxBytes <= 72) { "skeleton.account.password.max-bytes above 72 needs encoder=argon2 (bcrypt reads at most 72 bytes)" }
        }
    }

    data class Verification(
        /** 가입 확인 코드(6자리)의 유효 시간 — 재전송하면 새로 시작한다 */
        val codeTtl: Duration = Duration.ofMinutes(10),
        /** 코드 하나(챌린지)에 허용하는 추측 수 — 다 쓰면 그 시도는 죽는다 (다시 가입하거나 재전송) */
        val maxAttempts: Int = 5,
        /** 한 가입 시도에서 새 코드를 다시 받을 수 있는 횟수 · 사이 간격 */
        val maxResends: Int = 3,
        val resendCooldown: Duration = Duration.ofSeconds(30),
        /** 한 이메일에 보낼 수 있는 인증 코드 · "이미 계정이 있어요" 메일 수 (창 안) — 넘으면 조용히 안 보낸다 */
        val perEmail: Int = 3,
        val perEmailWindow: Duration = Duration.ofHours(1),
        /** 한 이메일 주소로 창 안에 **열 수 있는 가입 시도** 수 — 넘으면 시도를 저장하지 않는다(응답은 같다). 주소 하나에 걸린 추측의 총량을 묶는 값 (docs/accounts.md 무차별 대입 계산) */
        val signUpAttemptsPerEmail: Int = 5,
        /** 같은 IP 가 창 안에 보낼 수 있는 인증 재전송 요청 수 (넘으면 429) */
        val perIp: Int = 10,
        val perIpWindow: Duration = Duration.ofHours(1),
        /** 같은 IP 가 창 안에 보낼 수 있는 코드 입력 수 (넘으면 429) */
        val attemptsPerIp: Int = 30,
        val attemptsWindow: Duration = Duration.ofHours(1),
        /**
         * 한 이메일 주소의 코드를 창 안에 **통틀어** 몇 번까지 추측해 볼 수 있나 — 가입 시도 · 이메일 변경 챌린지 · 어느 계정에서든 같은 주소면 하나의 셈이다.
         * 기본 40 = 열 수 있는 시도 5 × 추측 5 + 재전송(메일 예산) 3 × 추측 5 (docs/accounts.md 무차별 대입 계산). 넘으면 429
         */
        val guessesPerEmail: Int = 40,
    ) {
        init { require(guessesPerEmail >= 1) { "skeleton.account.verification.guesses-per-email must be >= 1" } }
    }

    data class Reset(
        val ttl: Duration = Duration.ofMinutes(30),
        val perEmail: Int = 3,
        val perEmailWindow: Duration = Duration.ofHours(1),
        val perIp: Int = 10,
        val perIpWindow: Duration = Duration.ofHours(1),
    )

    /** 연락받을 이메일 — 로그인 수단 · 복구 수단이 아니다. 끄면(기본) API 도 `me.contactEmail` 의 값도 없다 (키는 늘 null) */
    data class ContactEmail(
        val enabled: Boolean = false,
    )

    data class Email(
        /**
         * false(기본): 이메일은 선택 — 주소 없는 계정도 서비스를 그대로 쓴다 (지금 동작).
         * true: 주소가 없는 계정은 이메일을 등록하기 전까지 허용 목록 밖의 모든 API 에서 403 `ACCOUNT.EMAIL_REQUIRED`.
         * 첫 이메일 등록(`POST /account/email/change`)은 **로그인으로 막 열린 세션**이 [firstRegistrationWindow] 안에 요청할 때 재인증이 없다.
         */
        val required: Boolean = false,
        /**
         * 주소가 한 번도 없던 계정의 **그 로그인이 연 세션**이, 열린 지 이 시간 안에 첫 주소를 등록하면 재인증(제공자 재동의)을 면제한다. 토큰 갱신은 창을 다시 열지 못하고, 다른 기기의 로그인도 이 세션의 창을 열지 못한다. 세션을 읽을 수 없는 구성(`auth-session` 없음)은 면제 없음.
         * 0 이면 면제 없음. [required] 가 true 일 때만 쓴다.
         */
        val firstRegistrationWindow: Duration = Duration.ofHours(1),
        /**
         * 주소 없이도 **더** 열어 둘 경로 (패턴만 쓰면 모든 메서드, `METHOD /패턴` 이면 그 메서드만). 더하기만 한다 — 등록 · 로그아웃 · 탈퇴 · 버리기 같은 필수 경로는 코드에 고정이라
         * 이 설정으로 계정이 갇히지 않는다. 읽지 못한 항목은 기동 실패. 막는 범위는 `/api/` 아래 전부(핸들러 종류와 무관)로 고정이다
         */
        val extraExcludePaths: List<String> = emptyList(),
        /**
         * [required] 가 true 일 때 **언제 막는가.** SIGN_IN(기본, 지금 동작) = 로그인 직후부터 허용 목록 밖 모든 API. WRITE = 읽기(`GET` · `HEAD` · `OPTIONS`)는 통과, 변경 요청에서만.
         * [required] 가 false 인데 WRITE 면 기동 실패 — 쓰기 때로 켰다고 믿는데 실제로는 꺼져 있는 사고를 막는다
         */
        val requiredWhen: RequiredWhen = RequiredWhen.SIGN_IN,
    ) {
        enum class RequiredWhen { SIGN_IN, WRITE }

        init {
            require(required || requiredWhen == RequiredWhen.SIGN_IN) {
                "skeleton.account.email.required-when=$requiredWhen needs skeleton.account.email.required=true (required-when only says when a required email is asked for)"
            }
            require(!firstRegistrationWindow.isNegative) { "skeleton.account.email.first-registration-window must not be negative" }
            extraExcludePaths.forEach(EmailRequiredRule::parse)
        }
    }

    data class EmailChange(
        /** 이메일 변경(새 주소) · 본인 재확인 6자리 코드의 유효 시간 — 가입 코드([Verification.codeTtl])와 같은 10분 (6자리 코드는 모두 같다. 링크 토큰인 [Reset.ttl] 만 30분) */
        val ttl: Duration = Duration.ofMinutes(10),
        val perAccount: Int = 5,
        val perAccountWindow: Duration = Duration.ofHours(1),
    )

    data class Deletion(
        /** 삭제 요청 뒤 이 기간이 지나야 데이터를 지운다 (그 안에는 관리자가 복구할 수 있다) */
        val grace: Duration = Duration.ofDays(30),
        /** 유예가 끝난 계정을 지우는 주기. 0 이면 주기 실행을 안 한다 (앱이 AccountPurgeService 를 직접 부를 때) */
        val purgeInterval: Duration = Duration.ofHours(1),
        val purgeBatch: Int = 50,
        /** 비밀번호가 없는 계정의 삭제 확인 6자리 코드의 유효 시간 — 다른 6자리 코드와 같은 10분 */
        val confirmationTtl: Duration = Duration.ofMinutes(10),
        /**
         * 유예가 끝난 뒤 계정을 어떻게 하나. ANONYMIZE(기본): 행(id · created_at)은 남기고 개인정보만 지운다 — 다른 표가 계정 id 를 들고 있어도 참조가 끊기지 않는다.
         * DELETE: 행까지 지운다 (앱이 계정 id 를 어디에도 남기지 않을 때)
         */
        val mode: Mode = Mode.ANONYMIZE,
        /**
         * true: 탈퇴 유예 중인 주인이 로그인(비밀번호 · 매직 링크 · 소셜)에 **성공**하면 세션 대신 `403 AUTH.ACCOUNT_DELETION_PENDING`(purgeAfter · 취소 토큰)을 받고
         * `POST /account/delete/cancel` 로 탈퇴를 취소한다. 기본 false(= 이 기능 이전과 같다): 탈퇴한 계정은 존재하지 않는 계정처럼 보이고 복구는 운영자만 한다
         */
        val selfRestore: Boolean = false,
        /** 취소 토큰의 유효 시간 — 로그인 성공 직후 "탈퇴를 취소할까요?" 화면에서 쓰는 짧은 시간 */
        val selfRestoreTtl: Duration = Duration.ofMinutes(15),
    ) {
        enum class Mode { ANONYMIZE, DELETE }
    }

    data class Blocks(
        /**
         * 운영자가 정지한 계정을 지울 때 남기는 재가입 차단(해시)을 얼마나 두나. 0(기본) = 운영자가 지울 때까지.
         * 개인정보 보존이므로 앱의 개인정보 처리방침에 적어야 한다 (docs/accounts.md)
         */
        val retention: Duration = Duration.ZERO,
        /**
         * 차단 해시(HMAC)의 서버 비밀. 비면 JWT 비밀에서 용도 접두사로 파생한다 — 그러면 JWT 비밀을 돌릴 때 차단이 조용히 풀리므로
         * 차단을 오래 둘 앱은 이 값을 따로 둔다 (바꾸면 기존 차단이 맞지 않는다)
         */
        val secret: String = "",
    ) {
        // 비밀이 로그 · 시작 요약에 찍히지 않게 (SeedAccount 처럼)
        override fun toString() = "Blocks(retention=$retention, secret=${if (secret.isEmpty()) "none" else "<redacted>"})"
    }

    data class Cleanup(
        /**
         * 만료된 한 번 쓰는 코드 · 토큰 · 가입 시도 줄을 만료 뒤 얼마나 두었다가 지우나 — 주기 정리(`deletion.purge-interval`)가 지운다.
         * 만료는 **읽을 때** 검사하므로 정리가 늦어도(또는 0 이어도) 만료된 코드가 쓰이지는 않는다. 이 값은 표가 무한히 쌓이지 않게 하는 청소 기준일 뿐이다
         */
        val expiredRetention: Duration = Duration.ofDays(1),
    )

    data class Login(
        /** 로그인 시도 제한 (실패만이 아니라 시도 전부 — 없는 계정도 같게 센다) */
        val throttleEnabled: Boolean = true,
        val perIp: Int = 30,
        val perAccount: Int = 10,
        val window: Duration = Duration.ofMinutes(10),
    )

    data class Mail(
        /** 메일 링크의 앞부분 — 프론트 주소 (예: https://app.example.com). 비면 stage · prod 의 DeployGuard 가 문제로 본다 */
        val linkBaseUrl: String = "",
        val resetPath: String = "/reset-password",
        val magicLinkPath: String = "/magic-link",
        /** "이미 계정이 있어요" 메일이 가리키는 프론트의 로그인 페이지 경로 (링크는 `link-base-url` + 이 경로, 토큰 없음) */
        val loginPath: String = "/login",
        /** "이미 계정이 있어요" 메일의 "비밀번호를 잊었다면" 이 가리키는 프론트의 재설정 **요청** 페이지 경로 (토큰 없음 — 링크 토큰이 오는 [resetPath] 와 다르다). react-skeleton 견본의 `/forgot-password` */
        val forgotPath: String = "/forgot-password",
        /** 계정 로케일이 없거나 지원하지 않을 때 쓰는 메일 언어 (ko · en 이 내장) */
        val defaultLocale: String = "en",
        /** 제목 앞에 붙는 서비스 이름 (예: [MyService]) */
        val subjectPrefix: String = "",
        /** 메일 발송 모듈이 없을 때 링크를 로그에 남길지: AUTO(보호 환경이 아니면 남김) | ON | OFF. 링크에는 토큰이 있으므로 보호 환경에서 ON 은 DeployGuard 문제 */
        val logLinks: LogLinks = LogLinks.OFF,
        /** false 면 HTML 대체 본문 없이 텍스트만 보낸다 (어떤 [dev.sumin.skeleton.account.mail.AccountMailTemplates] 든) */
        val htmlEnabled: Boolean = true,
        /** 내장 HTML 틀의 브랜드 — 설정만으로 바꾼다. 틀 자체를 바꾸려면 `AccountMailLayout` 빈 */
        val brand: Brand = Brand(),
    ) {
        enum class LogLinks { AUTO, ON, OFF }

        data class Brand(
            /** 머리글 · 바닥글에 쓰는 서비스 이름. 비면 이름 없이 그린다 */
            val serviceName: String = "",
            /** 머리글 로고 — https 주소만 쓴다 (아니면 무시). 비면 글자만 (외부 자원 없음) */
            val logoUrl: String = "",
            /** 버튼 · 머리글 띠 색 — `#rgb` / `#rrggbb` 만 (아니면 기본값) */
            val accentColor: String = "#2563eb",
            /** 바닥글의 문의 주소 (이메일 모양일 때만) */
            val supportAddress: String = "",
            /** 바닥글에 덧붙일 한 줄 (예: 회사 이름 · 주소) */
            val footer: String = "",
        )
    }

    data class Admin(
        /** true 면 관리자 엔드포인트(`/api/v1/admin/accounts` 아래 목록 · 정지 · 역할)를 연다 */
        val enabled: Boolean = false,
        /** 이 역할을 가진 호출자만 (ROLE_ 없이). 첫 관리자 부트스트랩이 주는 역할이기도 하다 */
        val role: String = "ADMIN",
    )

    data class Bootstrap(
        /** 이 이메일이 처음으로 **확인된** 로그인(또는 확인)에 성공했고 ADMIN 이 아직 한 명도 없으면 ADMIN 역할을 준다. 비밀번호 기본값은 없다 */
        val adminEmail: String = "",
    )

    data class Social(
        /** true 면 연결된 적 없는 소셜 계정이 첫 로그인에 계정을 만든다 (제공자가 이메일을 확인해 줬을 때만 이메일을 저장) */
        val signUp: Boolean = false,
        /**
         * true 면 제공자가 **확인한** 이메일(Kakao: 확인 + 유효, Naver: 없음)이 기존 계정 이메일과 글자 그대로 같을 때 그 계정에 붙인다 — 계정 주소에 알림 메일 + 이벤트.
         * 기존 계정이 미확인이면 제공자의 확인이 메일함 증명이라 가입 때의 미확인 비밀번호를 버린다. 기본은 병합 없이 409 ACCOUNT.SOCIAL_EMAIL_CONFLICT
         */
        val mergeOnVerifiedEmail: Boolean = false,
        /**
         * true(기본 — 이 설정 이전과 같다)면 제공자가 준 이름(Google 실명 · Kakao 닉네임 · OIDC `name`)을 **새 계정의 닉네임**으로 쓴다(규칙에 맞게 고쳐 쓰고, 겹치면 버린다).
         * false 면 제공자 이름을 버리고 이름 없는 계정으로 만든다 — `display-name.fallback` 규칙(GENERATED 면 자동 닉네임)은 그대로 적용되고, 닉네임은 사용자가 정한다.
         * 이미 연결된 계정의 재로그인 · 수동 연결(`IdentityService.link`)은 원래 제공자 이름을 계정에 쓰지 않는다. 이메일은 이 설정과 무관하다
         */
        val importDisplayName: Boolean = true,
    )

    /** 닉네임(`display-name`) — 모듈은 메커니즘만, 사용 방식(중복 허용 · 필수 · 자동 닉네임)은 앱이 정한다. 기본은 이 기능 이전과 같다(중복 허용 · 선택 · 자동 닉네임 없음) */
    data class DisplayName(
        /**
         * NONE(기본): 중복 허용, 꼬리표 없음. UNIQUE: 비교용 키(NFKC · 소문자)가 같은 닉네임은 하나만 — 충돌은 `409 ACCOUNT.DISPLAY_NAME_TAKEN`.
         * TAGGED: 중복 허용 + 서버가 4자리 꼬리표(`0001`..`9999`)를 붙여 `닉네임#0417` 로 구분한다 — 닉네임을 바꾸면 꼬리표를 새로 뽑는다.
         * 스키마는 방식과 무관하다 — 이미 계정이 있는 DB 에서 바꾸는 법: docs/accounts.md "닉네임"
         */
        val uniqueness: Uniqueness = Uniqueness.NONE,
        /** true 면 이메일 가입 요청에 닉네임이 없을 때 `400`(필드 `displayName`) — 계정이 있든 없든 같은 응답 */
        val requiredOnSignUp: Boolean = false,
        /** GENERATED: 닉네임 없이 만들어지는 계정(소셜 · 매직 링크 · 닉네임 없는 가입)에 `user-1a2b3c` 를 준다. 이메일 앞부분은 쓰지 않는다 */
        val fallback: Fallback = Fallback.NONE,
        /** 거절할 닉네임(운영자 · 예약어, 대소문자 · 전각 · 띄어쓰기 · `_` 무시). 운영자 역할 계정과 시드는 예외. 기본은 빈 목록 */
        val reserved: List<String> = emptyList(),
        /** 한 계정이 [changeWindow] 안에 닉네임을 **바꿀 수 있는** 횟수 (같은 값으로의 저장 · 규칙에 걸려 거절된 요청은 세지 않는다) — 넘으면 `429 ACCOUNT.RATE_LIMITED`. TAGGED 에서 꼬리표를 마음에 들 때까지 다시 뽑는 것을 막는다. 0 이면 한도 없음 */
        val changeLimit: Int = 10,
        val changeWindow: Duration = Duration.ofDays(1),
    ) {
        init { require(changeLimit >= 0) { "skeleton.account.display-name.change-limit must be >= 0 (0 = no limit)" } }

        enum class Uniqueness { NONE, UNIQUE, TAGGED }

        enum class Fallback { NONE, GENERATED }
    }

    /** 로그인 아이디(`login-id`) — 이메일이 바뀌어도 안 바뀌는, 본인과 운영자만 보는 간판. 기본은 꺼짐: 켜지 않으면 API · 응답 · 로그인이 이 기능 이전과 같다 */
    data class LoginId(
        /** true 면 가입 · 로그인 · 게이트 · 찾기 · 응답의 아이디 동작이 전부 켜진다 (반쪽 스위치는 없다) */
        val enabled: Boolean = false,
        /** 아이디 길이. `1 ≤ min ≤ max ≤ 30`(열 길이) 아니면 기동 실패 */
        val minLength: Int = 4,
        val maxLength: Int = 20,
        /** 내줄 수 없는 아이디 (기호와 대소문자를 빼고 비교). `display-name.reserved` 와 따로다. 시드 계정은 예외 */
        val reserved: List<String> = emptyList(),
        /** 실시간 중복 확인을 한 IP 가 창 안에 부를 수 있는 횟수 — 넘으면 `429 ACCOUNT.RATE_LIMITED`. 창 길이는 0 보다 커야 한다 */
        val checkPerIp: Int = 30,
        val checkPerIpWindow: Duration = Duration.ofMinutes(10),
        /** 「아이디 정하기」를 창 안에 **시도**할 수 있는 횟수 — 형식을 통과한 시도(성공 · 실패 모두)를 센다, 형식 오류는 세지 않는다. 계정별 하나와 IP 별 하나(같은 값 · 버킷만 따로) */
        val claimLimit: Int = 10,
        val claimWindow: Duration = Duration.ofHours(1),
        /** 아이디 없이도 **더** 열어 둘 경로 — `email.extra-exclude-paths` 와 같은 문법 · 같은 규칙(더하기만, 못 읽으면 기동 실패) */
        val extraExcludePaths: List<String> = emptyList(),
    ) {
        init {
            require(minLength >= 1) { "skeleton.account.login-id.min-length must be >= 1" }
            require(maxLength <= LoginIds.COLUMN_LENGTH) { "skeleton.account.login-id.max-length must be <= ${LoginIds.COLUMN_LENGTH} (the column length)" }
            require(minLength <= maxLength) { "skeleton.account.login-id.min-length must be <= skeleton.account.login-id.max-length" }
            require(checkPerIp >= 1) { "skeleton.account.login-id.check-per-ip must be >= 1" }
            require(claimLimit >= 1) { "skeleton.account.login-id.claim-limit must be >= 1" }
            require(!checkPerIpWindow.isZero && !checkPerIpWindow.isNegative) { "skeleton.account.login-id.check-per-ip-window must be > 0" }
            require(!claimWindow.isZero && !claimWindow.isNegative) { "skeleton.account.login-id.claim-window must be > 0" }
            extraExcludePaths.forEach {
                try { EmailRequiredRule.parse(it) } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("skeleton.account.login-id.extra-exclude-paths: cannot read \"$it\" — write `/path/pattern` or `METHOD /path/pattern`", e)
                }
            }
        }
    }

    data class Seed(
        /** 로컬 · e2e 용 시드 계정 — 기동 때 없으면 만든다. stage · prod 에서 하나라도 있으면 DeployGuard 문제 */
        val accounts: List<SeedAccount> = emptyList(),
    )

    data class SeedAccount(
        /** 고정 계정 id — e2e 픽스처 · 업로드 키(`uploads/<id>/…`)가 안정되도록. 비우면 무작위 id */
        val id: String? = null,
        val email: String,
        val password: String,
        val roles: Set<String> = emptySet(),
        val displayName: String? = null,
        val locale: String? = null,
        /** 로그인 아이디 — `login-id.enabled` 일 때만 쓴다. 예약어 검사 없이 기동 때 넣는다 (e2e 가 아이디 게이트에 막히지 않게) */
        val loginId: String? = null,
    ) {
        override fun toString() = "SeedAccount(id=$id, email=<redacted>, password=<redacted>, roles=$roles)"
    }

    data class Captcha(
        /** true 면 가입 · 재설정 · 인증 재전송 · 매직 링크 요청이 캡차 검증을 통과해야 한다 (검증기 `captcha-turnstile` 필요 — 없으면 요청이 실패로 닫히고 stage · prod 가드가 기동을 막는다). false(기본)면 검증기가 있어도 부르지 않는다 */
        val required: Boolean = false,
    )

    data class Audit(
        /** true 면 account-jdbc 가 모든 계정 이벤트를 `account_audit` 표에 쓴다 (IP 포함, 토큰 · 이메일 없음) */
        val enabled: Boolean = false,
    )

    data class Http(
        /** false 면 컨트롤러를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
    )
}
