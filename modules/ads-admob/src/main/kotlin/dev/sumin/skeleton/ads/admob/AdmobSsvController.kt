package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.BasicResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointWallet
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AdMob 이 부르는 공개 경로 — 로그인이 없다([AdmobAutoConfiguration] 이 공개 경로로 연다). 진위는 구글의 서명이다.
 * 응답: `200`(적립 · 이미 적립 — 같은 `transaction_id` 는 구별하지 않는다) · `400`(서명 불일치 · 허용 밖 광고 단위 · 사용자 없음 · 모르는 키 …, 지급 없음) · `503`(공개키를 못 읽음 · DB 오류, 구글이 다시 보낸다).
 * 공개 OpenAPI 문서에서 숨긴다 — 앱 클라이언트의 계약이 아니라 구글이 부르는 주소다.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/ads/admob")
class AdmobSsvController(
    private val verifier: AdmobSsvVerifier,
    private val wallet: PointWallet,
    private val properties: AdmobProperties,
    private val time: TimeProvider,
) {
    @GetMapping("/ssv")
    fun receive(request: HttpServletRequest): BasicResponse {
        val reward = try {
            verifier.verify(request.queryString)
        } catch (e: AdmobRejectedException) {
            log.warn("AdMob SSV callback rejected: {}", e.reason)
            throw e
        }
        val expiresAt = properties.expiresInDays?.let { time.now().plus(it, ChronoUnit.DAYS) }
        try {
            wallet.earn(reward.userId, reward.amount, expiresAt, SOURCE_TYPE, reward.transactionId)   // 이미 있으면 null — 그대로 200
        } catch (e: ApplicationException) {
            throw e
        } catch (e: DataAccessException) {
            throw ApplicationException("Point ledger unavailable", AdmobErrorCode.LEDGER_UNAVAILABLE, cause = e)
        }
        return Response.ok()
    }

    companion object {
        const val SOURCE_TYPE = "ADMOB_SSV"
        private val log = LoggerFactory.getLogger(AdmobSsvController::class.java)
    }
}
