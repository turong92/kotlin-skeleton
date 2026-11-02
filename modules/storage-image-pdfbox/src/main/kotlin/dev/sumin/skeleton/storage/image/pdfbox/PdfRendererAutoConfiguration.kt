package dev.sumin.skeleton.storage.image.pdfbox

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** `skeleton.storage.image.pdf.enabled=true` 일 때만 선다(**기본 꺼짐**). 엔진이 PDF 만 맡으므로([PdfRenderer.kinds]) 다른 엔진(`imageio` · `imgproxy`)과 함께 있어도 된다 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "skeleton.storage.image.pdf", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(PdfProperties::class)
class PdfRendererAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun pdfRenderer(properties: PdfProperties): PdfRenderer = PdfRenderer(properties)
}
