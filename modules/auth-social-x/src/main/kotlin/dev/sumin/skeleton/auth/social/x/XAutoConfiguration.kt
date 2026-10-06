package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(XProperties::class)
class XAutoConfiguration {
    /** client id 가 있을 때만 — 빈 문자열은 "설정 안 함" 이다 (환경변수 자리만 만들어 둔 yml 이 아무것도 바꾸지 않는다) */
    @Bean("xOAuthProvider")
    @ConditionalOnExpression("!'\${skeleton.auth-social-x.client-id:}'.trim().isEmpty()")
    @ConditionalOnMissingBean(name = ["xOAuthProvider"])
    fun xOAuthProvider(httpClient: ExternalHttpClient, properties: XProperties): XOAuthProvider = XOAuthProvider(httpClient, properties)
}
