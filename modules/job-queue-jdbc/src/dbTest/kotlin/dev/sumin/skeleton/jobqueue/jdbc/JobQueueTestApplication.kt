package dev.sumin.skeleton.jobqueue.jdbc

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// 컴포넌트 스캔을 하지 않는다 — 이 시험 앱은 모듈과 같은 패키지라 @SpringBootApplication 이면 모듈의 컨트롤러(JobWakeController)까지 줍는다.
// 모듈의 빈은 자동설정으로 들어온다(실제 앱과 같다)
@SpringBootConfiguration
@EnableAutoConfiguration
class JobQueueTestApplication
