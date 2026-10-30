package dev.sumin.skeleton.auth.mfa.jdbc

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// 컴포넌트 스캔을 하지 않는다 - 모듈의 빈은 자동설정으로 들어온다(실제 앱과 같다).
// auth-mfa 의 규칙 쪽 자동설정은 계정 모듈 위에서만 선다 - 여기서는 저장소만 본다
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = ["dev.sumin.skeleton.auth.mfa.MfaAutoConfiguration", "dev.sumin.skeleton.auth.mfa.web.MfaWebAutoConfiguration"])
class MfaJdbcTestApplication
