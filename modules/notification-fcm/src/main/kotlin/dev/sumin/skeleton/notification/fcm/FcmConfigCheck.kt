package dev.sumin.skeleton.notification.fcm

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/** 검사를 통과한 서비스 계정 - 개인 키는 [toString] 에 나오지 않는다 */
class FcmCredentials internal constructor(val clientEmail: String, val projectId: String, internal val privateKey: PrivateKey) {
    override fun toString(): String = "FcmCredentials"
}

/**
 * 서비스 계정 설정을 쓸 수 있는지 - 센더를 세울지([credentialsOf])와 기동 검사([problems])가 같은 판정을 쓴다.
 * 메시지에는 속성 · 환경변수 이름만 적고 값(JSON 조각 · 키 · 이메일)은 싣지 않는다.
 */
object FcmConfigCheck {
    const val PROPERTY = "skeleton.notification.fcm.credentials"
    const val ENV = "<P>_NOTIFICATION_FCM_CREDENTIALS"

    private val JSON = JsonMapper.builder().build()

    /** 구글 프로젝트 id 모양 - 요청 경로에 그대로 들어가므로 `/` · `?` · `..` 같은 것이 못 들어오게 좁게 연다 */
    private val PROJECT_ID = Regex("^[a-z][a-z0-9-]{3,62}$")
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+$")

    /** 센더를 세울 수 있으면 서비스 계정, 아니면 null (이유는 [problems]) */
    fun credentialsOf(properties: FcmProperties): FcmCredentials? = parse(properties.credentials).first

    /** 이 설정으로는 푸시를 못 보낸다는 이유들 - 비면 쓸 수 있다 */
    fun problems(properties: FcmProperties): List<String> = parse(properties.credentials).second?.let { listOf(it) } ?: emptyList()

    private fun parse(raw: String): Pair<FcmCredentials?, String?> {
        val text = raw.trim()
        if (text.isEmpty()) return null to "$PROPERTY ($ENV) is empty but skeleton.notification.fcm.enabled=true: pushes cannot be authorized"
        fun bad(why: String) = null to "$PROPERTY ($ENV) is unreadable: $why (expected the base64 of the Firebase service account key JSON)"
        val json = if (text.startsWith("{")) text else runCatching { String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8) }.getOrNull() ?: return bad("not base64")
        val root = runCatching { JSON.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: return bad("not a JSON object")
        if (root.path("type").asString() != "service_account") return bad("type is not service_account")
        val email = root.path("client_email").asString().trim()
        if (!EMAIL.matches(email)) return bad("client_email missing")
        val projectId = root.path("project_id").asString().trim()
        if (!PROJECT_ID.matches(projectId)) return bad("project_id missing or not a project id")
        val key = runCatching {
            val body = root.path("private_key").asString()
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").filterNot { it.isWhitespace() }
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        }.getOrNull() ?: return bad("private_key is not a PKCS#8 RSA key")
        return FcmCredentials(email, projectId, key) to null
    }
}
