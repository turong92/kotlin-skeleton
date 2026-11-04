package dev.sumin.skeleton.payment.googleplay

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/** 검사를 통과한 서비스 계정 - 개인 키는 [toString] 에 나오지 않는다. */
internal class GooglePlayCredentials(val clientEmail: String, val privateKey: PrivateKey) {
    override fun toString(): String = "GooglePlayCredentials"
}

/**
 * 설정을 쓸 수 있는지 - 결제사 빈을 세울지와 기동 검사가 같은 판정을 쓴다. 메시지에는 속성 이름만 적고 값(JSON 조각 · 키 · 이메일)은 싣지 않는다.
 */
internal object GooglePlayConfigCheck {
    private const val SERVICE_ACCOUNT = "${GooglePlayProperties.PREFIX}.service-account"
    private val JSON = JsonMapper.builder().build()
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+$")

    /** 서비스 계정을 읽을 수 있으면 그것, 아니면 null (이유는 [problems]). */
    fun credentialsOf(properties: GooglePlayProperties): GooglePlayCredentials? = parse(properties.serviceAccount).first

    /** 이 설정으로는 구매를 확인할 수 없다는 이유들 - 비면 쓸 수 있다. */
    fun problems(properties: GooglePlayProperties): List<String> {
        val missing = properties.missing()
        val unreadable = if (SERVICE_ACCOUNT in missing) null else parse(properties.serviceAccount).second
        return buildList {
            if (missing.isNotEmpty()) add("${missing.joinToString(", ")} is empty")
            if (unreadable != null) add(unreadable)
        }
    }

    private fun parse(raw: String): Pair<GooglePlayCredentials?, String?> {
        val text = raw.trim()
        fun bad(why: String) = null to "$SERVICE_ACCOUNT is unreadable: $why (expected the base64 of the service account key JSON)"
        if (text.isEmpty()) return bad("empty")
        val json = if (text.startsWith("{")) text else runCatching { String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8) }.getOrNull() ?: return bad("not base64")
        val root = runCatching { JSON.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: return bad("not a JSON object")
        if (root.path("type").asString() != "service_account") return bad("type is not service_account")
        val email = root.path("client_email").asString().trim()
        if (!EMAIL.matches(email)) return bad("client_email missing")
        val key = runCatching {
            val body = root.path("private_key").asString()
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").filterNot { it.isWhitespace() }
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        }.getOrNull() ?: return bad("private_key is not a PKCS#8 RSA key")
        return GooglePlayCredentials(email, key) to null
    }
}
