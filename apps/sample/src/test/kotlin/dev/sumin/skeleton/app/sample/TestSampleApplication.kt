package dev.sumin.skeleton.app.sample

import org.springframework.boot.fromApplication
import org.springframework.boot.with

// IDE 에서 컨테이너 DB 와 함께 앱을 띄울 때: 이 파일의 main 실행
fun main(args: Array<String>) {
    fromApplication<SampleApplication>().with(TestcontainersConfiguration::class).run(*args)
}
