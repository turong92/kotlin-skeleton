package dev.sumin.skeleton.app.sample

import java.util.concurrent.atomic.AtomicInteger

/**
 * 시험마다 다른 클라이언트 IP. 「아이디 정하기」는 계정별로도 IP 별로도 `claim-limit` 번까지라, 같은 컨텍스트(= 같은 인메모리 한도 저장소)에서
 * 소셜 계정을 여러 개 만들어 모두 `127.0.0.1` 에서 아이디를 정하면 열한 번째부터 429 가 된다. 호출마다 새 주소를 쓴다.
 */
object TestClientIps {
    private val next = AtomicInteger()

    fun fresh(): String = next.incrementAndGet().let { "198.18.${it / 250 % 250}.${it % 250 + 1}" }
}
