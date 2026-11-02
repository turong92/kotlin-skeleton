package dev.sumin.skeleton.storage.image.imageio

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** `skeleton.storage.image.enabled=true` 일 때 기본 엔진으로 선다 - 앱이 같은 타입의 빈을 두면 그것이 쓰인다. 다른 엔진(`ImageRenderer`)과 함께 있어도 되고 `@Order` 순으로 종류를 맡는다 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "skeleton.storage.image", name = ["enabled"], havingValue = "true")
class ImageIoRendererAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun imageIoRenderer(): ImageIoRenderer = ImageIoRenderer()
}
