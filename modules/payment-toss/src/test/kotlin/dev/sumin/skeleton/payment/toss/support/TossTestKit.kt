package dev.sumin.skeleton.payment.toss.support

import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.payment.toss.TossPaymentProperties
import dev.sumin.skeleton.payment.toss.TossPaymentProvider
import java.time.Duration
import org.springframework.web.reactive.function.client.WebClient

/** 부하가 큰 호스트에서도 같은 결과여야 한다 — 정상 경로의 상한은 넉넉하게, 시간 초과 시험은 서버가 응답을 붙잡는 방식으로. */
internal val GENEROUS: Duration = Duration.ofSeconds(30)

/** 진짜 [DefaultExternalHttpClient] — 가짜 서버와 실제 HTTP 로 말한다. [responseTimeout] 은 플랫폼의 클라이언트 단위 응답 제한. */
internal fun realHttpClient(responseTimeout: Duration = GENEROUS): ExternalHttpClient =
    DefaultExternalHttpClient(
        webClientBuilder = WebClient.builder(),
        properties = OutboundHttpProperties(
            defaultConnectTimeout = GENEROUS,
            defaultResponseTimeout = responseTimeout,
        ),
        defaultErrorMapper = DefaultExternalHttpErrorMapper(),
        customizers = emptyList(),
    )

internal fun tossProperties(
    server: FakeTossServer,
    clientKey: String = "test_ck_fake_client_key",
    lookupTimeout: Duration = Duration.ofSeconds(10),
): TossPaymentProperties =
    TossPaymentProperties(
        enabled = true,
        secretKey = server.secretKey,
        clientKey = clientKey,
        baseUrl = server.baseUrl,
        lookupTimeout = lookupTimeout,
    )

internal fun tossProvider(
    server: FakeTossServer,
    responseTimeout: Duration = GENEROUS,
    lookupTimeout: Duration = Duration.ofSeconds(10),
    clientKey: String = "test_ck_fake_client_key",
): TossPaymentProvider =
    TossPaymentProvider(
        httpClient = realHttpClient(responseTimeout),
        properties = tossProperties(server, clientKey, lookupTimeout),
    )
