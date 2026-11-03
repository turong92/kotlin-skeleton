package dev.sumin.skeleton.waitingroom

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * ```yaml
 * skeleton:
 *   waiting-room:
 *     http:
 *       enabled: true       # false 면 사용자 경로 /api/v1/waiting-rooms 를 등록하지 않는다
 *     admin:
 *       enabled: false      # true 면 운영자 경로 /api/v1/admin/waiting-rooms (권한 waiting-room.manage)
 *     token:
 *       key: ${WAITING_ROOM_TOKEN_KEY}   # 순번표 · 입장권 서명 키 (24자 이상). 환경변수 <P>_WAITING_ROOM_TOKEN_KEY
 *       previous-key:                    # 키를 돌릴 때만 - 옛 키(검증에만 쓴다). 환경변수 <P>_WAITING_ROOM_TOKEN_PREVIOUS_KEY
 * ```
 */
@ConfigurationProperties("skeleton.waiting-room")
data class WaitingRoomProperties(
    val http: Http = Http(),
    val admin: Admin = Admin(),
    val token: Token = Token(),
) {
    data class Http(val enabled: Boolean = true)

    data class Admin(val enabled: Boolean = false)

    data class Token(val key: String? = null, val previousKey: String? = null) {
        /** 비밀이 로그에 찍히지 않게 */
        override fun toString() = "Token(key=${if (key.isNullOrBlank()) "unset" else "set"}, previousKey=${if (previousKey.isNullOrBlank()) "unset" else "set"})"
    }
}

/**
 * 대기실 서버 API([WaitingRooms]). 저장소([WaitingRoomStore])는 `waiting-room-jdbc` 가 낸다 - 저장소가 없으면 서비스도 서지 않는다.
 * 기본은 **꺼짐** - 모듈을 붙여도 대기실을 만들기 전에는 아무 일도 없고, 예약 같은 쪽이 [WaitingRooms.requireEntry] 를 부를 때 비로소 문이 선다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.waitingroom.jdbc.WaitingRoomJdbcAutoConfiguration"])
@EnableConfigurationProperties(WaitingRoomProperties::class)
class WaitingRoomAutoConfiguration {
    @Bean
    @ConditionalOnBean(WaitingRoomStore::class)
    @ConditionalOnMissingBean
    fun waitingRoomTokens(properties: WaitingRoomProperties): WaitingRoomTokens =
        WaitingRoomTokens(properties.token.key, properties.token.previousKey)

    @Bean
    @ConditionalOnBean(WaitingRoomStore::class)
    @ConditionalOnMissingBean
    fun waitingRooms(store: WaitingRoomStore, tokens: WaitingRoomTokens, time: TimeProvider): WaitingRooms = WaitingRooms(store, tokens, time)

    @Bean
    @ConditionalOnBean(WaitingRoomStore::class)
    @ConditionalOnMissingBean(name = ["waitingRoomDeployGuard"])
    fun waitingRoomDeployGuard(properties: WaitingRoomProperties): DeployGuard = WaitingRoomDeployGuard(properties)

    @Bean
    @ConditionalOnMissingBean(name = ["waitingRoomPermissionDeclaration"])
    fun waitingRoomPermissionDeclaration(): PermissionDeclaration = WaitingRoomPermissions.declaration
}

/**
 * 서명 키가 없거나 약하면 보호 환경(stage · prod)에서 기동 실패, 그 밖에서는 경고다 - 깨우기 비밀과 같은 기준.
 * 키가 없으면 인스턴스마다 다른 무작위 키로 서명해 여러 대에서 토큰이 서로 통하지 않고, 약하면 입장권을 위조해 새치기한다.
 * 약함 = 24자 미만 · 서로 다른 글자 8개 미만. 메시지에는 속성 · 환경변수 이름만 적고 값은 싣지 않는다.
 */
class WaitingRoomDeployGuard(private val properties: WaitingRoomProperties) : DeployGuard {
    override val name: String = "waiting-room"

    override fun problems(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedBy(PROTECTED_PROFILES)) emptyList() else findings()

    private fun findings(): List<String> {
        val token = properties.token
        if (token.key.isNullOrBlank()) {
            return listOf("$PROPERTY ($ENV_NAME) is empty: tickets and entry tokens are signed with a random key made at startup, so they stop working on restart and on every other instance; set a random secret of at least $MIN_LENGTH characters")
        }
        return listOf(PROPERTY to token.key, PREVIOUS_PROPERTY to token.previousKey).filter { (_, secret) -> !secret.isNullOrBlank() && weak(secret) }.map { (property, _) ->
            "$property is too weak: it is the only proof of a ticket or an entry token; use at least $MIN_LENGTH characters with at least $MIN_DISTINCT different ones"
        }
    }

    private fun weak(secret: String) = secret.length < MIN_LENGTH || secret.toSet().size < MIN_DISTINCT

    private companion object {
        const val PROPERTY = "skeleton.waiting-room.token.key"
        const val PREVIOUS_PROPERTY = "skeleton.waiting-room.token.previous-key"
        const val ENV_NAME = "<P>_WAITING_ROOM_TOKEN_KEY"
        val PROTECTED_PROFILES = listOf("prod", "staging")   // 스위치(SKELETON_ENV=stage|prod)도 보호 환경으로 본다
        const val MIN_LENGTH = 24
        const val MIN_DISTINCT = 8
    }
}
