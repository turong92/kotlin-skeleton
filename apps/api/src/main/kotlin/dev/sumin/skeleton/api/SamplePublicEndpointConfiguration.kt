package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SamplePublicEndpointConfiguration {
    @Bean
    fun samplePublicEndpoints(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", "/api/v1/hello")
        }
}
