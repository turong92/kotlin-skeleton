package dev.sumin.skeleton.app.nativecheckextra

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시험용 구글 대역 — AdMob SSV 콜백은 구글의 ECDSA 서명이라, 이 앱이 자기 키 쌍을 만들어 공개키 목록(`/admob-keys`, AdMob 모듈의 `keys-url` 이 가리킨다)을 내고
 * 서명한 콜백 쿼리(`/admob-signed`)를 만들어 준다. 시험 스크립트가 그 쿼리로 `GET /api/v1/ads/admob/ssv` 를 부른다. 제품 코드가 아니고, 이 앱은 인터넷에 내놓지 않는다.
 */
@RestController
@RequestMapping("/api/v1/native-check")
class AdmobProbe {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @GetMapping("/admob-keys")
    fun keys(): Map<String, Any> = mapOf("keys" to listOf(mapOf("keyId" to KEY_ID, "base64" to Base64.getEncoder().encodeToString(pair.public.encoded))))

    /** `ad_unit` · `reward_amount` · `transaction_id` · `user_id` 로 서명한 쿼리 문자열(`&signature=…&key_id=…` 로 끝난다) */
    @GetMapping("/admob-signed")
    fun signed(@RequestParam user: String, @RequestParam tx: String, @RequestParam(defaultValue = "5") amount: Long): Map<String, String> {
        val message = "ad_network=5450213213286189855&ad_unit=42&reward_amount=$amount&reward_item=coins&timestamp=1700000000000&transaction_id=$tx&user_id=${URLEncoder.encode(user, Charsets.UTF_8)}"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        return mapOf("query" to "$message&signature=${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}&key_id=$KEY_ID")
    }

    private companion object {
        const val KEY_ID = 4242L
    }
}

@Configuration(proxyBeanMethods = false)
class NativeCheckExtraConfiguration {
    @Bean
    fun nativeCheckExtraPublicEndpoints(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", "/api/v1/native-check/**")
        }
}
