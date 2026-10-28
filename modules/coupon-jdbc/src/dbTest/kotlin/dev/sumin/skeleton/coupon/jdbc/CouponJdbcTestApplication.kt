package dev.sumin.skeleton.coupon.jdbc

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// 컴포넌트 스캔을 하지 않는다 - 모듈의 빈은 자동설정으로 들어온다(실제 앱과 같다)
@SpringBootConfiguration
@EnableAutoConfiguration
class CouponJdbcTestApplication
