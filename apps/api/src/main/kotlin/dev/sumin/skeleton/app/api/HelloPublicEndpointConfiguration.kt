package dev.sumin.skeleton.app.api

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// auth 의 기본 security chain 은 anyRequest().authenticated() — 공개할 경로는 PublicEndpointContributor 로 연다
@Configuration(proxyBeanMethods = false)
class HelloPublicEndpointConfiguration {
    @Bean
    fun helloPublicEndpoint(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", "/api/v1/hello")
        }
}
