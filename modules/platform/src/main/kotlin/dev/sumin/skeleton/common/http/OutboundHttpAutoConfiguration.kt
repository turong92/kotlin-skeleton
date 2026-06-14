package dev.sumin.skeleton.common.http

import dev.sumin.skeleton.common.logging.RedactionAutoConfiguration
import dev.sumin.skeleton.common.logging.SensitiveValueRedactor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

@AutoConfiguration(after = [RedactionAutoConfiguration::class])
@ConditionalOnClass(WebClient::class)
@EnableConfigurationProperties(OutboundHttpProperties::class)
class OutboundHttpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun defaultExternalHttpErrorMapper(): ExternalHttpErrorMapper =
        DefaultExternalHttpErrorMapper()

    @Bean
    @ConditionalOnMissingBean
    fun defaultExternalHttpTraceExtractor(): ExternalHttpTraceExtractor =
        DefaultExternalHttpTraceExtractor()

    @Bean
    @ConditionalOnMissingBean
    fun externalHttpClient(
        webClientBuilder: ObjectProvider<WebClient.Builder>,
        properties: OutboundHttpProperties,
        defaultErrorMapper: ExternalHttpErrorMapper,
        customizers: ObjectProvider<ExternalHttpClientCustomizer>,
        traceExtractor: ExternalHttpTraceExtractor,
        redactor: ObjectProvider<SensitiveValueRedactor>,
    ): ExternalHttpClient =
        DefaultExternalHttpClient(
            webClientBuilder = webClientBuilder.getIfAvailable { WebClient.builder() },
            properties = properties,
            defaultErrorMapper = defaultErrorMapper,
            customizers = customizers.orderedStream().toList(),
            traceExtractor = traceExtractor,
            redactor = redactor.getIfAvailable { SensitiveValueRedactor() },
        )
}
