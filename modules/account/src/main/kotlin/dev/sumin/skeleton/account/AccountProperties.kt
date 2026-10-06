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
    val deletion: Deletion = Deletion(),
    val login: Login = Login(),
    val mail: Mail = Mail(),
    val admin: Admin = Admin(),
    val bootstrap: Bootstrap = Bootstrap(),
    val social: Social = Social(),
    val seed: Seed = Seed(),
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
    )

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
        }
    }

    data class Verification(
        val ttl: Duration = Duration.ofHours(24),
        /** 한 이메일에 보낼 수 있는 인증 메일 수 (창 안) — 넘으면 조용히 안 보낸다 */
        val perEmail: Int = 3,
        val perEmailWindow: Duration = Duration.ofHours(1),
        /** 같은 IP 가 창 안에 보낼 수 있는 인증 재전송 요청 수 (넘으면 429) */
        val perIp: Int = 10,
        val perIpWindow: Duration = Duration.ofHours(1),
    )

    data class Reset(
        val ttl: Duration = Duration.ofMinutes(30),
        val perEmail: Int = 3,
        val perEmailWindow: Duration = Duration.ofHours(1),
        val perIp: Int = 10,
        val perIpWindow: Duration = Duration.ofHours(1),
    )

    data class EmailChange(
        val ttl: Duration = Duration.ofMinutes(30),
        val perAccount: Int = 5,
        val perAccountWindow: Duration = Duration.ofHours(1),
    )

    data class Deletion(
        /** 삭제 요청 뒤 이 기간이 지나야 데이터를 지운다 (그 안에는 관리자가 복구할 수 있다) */
        val grace: Duration = Duration.ofDays(30),
        /** 유예가 끝난 계정을 지우는 주기. 0 이면 주기 실행을 안 한다 (앱이 AccountPurgeService 를 직접 부를 때) */
        val purgeInterval: Duration = Duration.ofHours(1),
        val purgeBatch: Int = 50,
        /** 비밀번호가 없는 계정의 삭제 확인 메일 링크 유효 시간 */
        val confirmationTtl: Duration = Duration.ofMinutes(30),
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
        val verifyPath: String = "/verify-email",
        val resetPath: String = "/reset-password",
        val emailChangePath: String = "/confirm-email-change",
        val deletePath: String = "/confirm-delete",
        val magicLinkPath: String = "/magic-link",
        /** 비밀번호 없는 계정의 민감한 일(이메일 변경 · 첫 비밀번호 · 소셜 연결) 확인 링크 */
        val reauthPath: String = "/confirm-reauth",
        /** 계정 로케일이 없거나 지원하지 않을 때 쓰는 메일 언어 (ko · en 이 내장) */
        val defaultLocale: String = "en",
        /** 제목 앞에 붙는 서비스 이름 (예: [Ovation]) */
        val subjectPrefix: String = "",
        /** 메일 발송 모듈이 없을 때 링크를 로그에 남길지: AUTO(보호 환경이 아니면 남김) | ON | OFF. 링크에는 토큰이 있으므로 보호 환경에서 ON 은 DeployGuard 문제 */
        val logLinks: LogLinks = LogLinks.OFF,
    ) {
        enum class LogLinks { AUTO, ON, OFF }
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
        /** true 면 제공자가 확인한 이메일이 **이미 확인된** 기존 계정 이메일과 같을 때 그 계정에 붙인다. 기본은 병합 없이 409 ACCOUNT.SOCIAL_EMAIL_CONFLICT */
        val mergeOnVerifiedEmail: Boolean = false,
    )

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
    )

    data class Captcha(
        /** true 면 가입 · 재설정 · 인증 재전송 · 매직 링크 요청이 캡차 검증을 통과해야 한다 (검증기 `captcha-turnstile` 필요 — 없으면 요청이 실패로 닫히고 stage · prod 가드가 기동을 막는다). false(기본)면 검증기가 있어도 부르지 않는다 */
        val required: Boolean = false,
    )

    data class Audit(
        /** true 면 account-jdbc 가 모든 계정 이벤트를 `skeleton_account_audit` 표에 쓴다 (IP 포함, 토큰 · 이메일 없음) */
        val enabled: Boolean = false,
    )

    data class Http(
        /** false 면 컨트롤러를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
    )
}
