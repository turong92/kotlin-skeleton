package dev.sumin.skeleton.app.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 루트 패키지의 하위(app.api)에 둔다: component scan 은 이 패키지 아래 앱 코드만 훑고, 모듈은 AutoConfiguration 으로만 붙는다.
@SpringBootApplication
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
