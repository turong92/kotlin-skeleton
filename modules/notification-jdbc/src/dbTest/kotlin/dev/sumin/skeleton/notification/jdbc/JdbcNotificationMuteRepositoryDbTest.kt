package dev.sumin.skeleton.notification.jdbc

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcNotificationMuteRepositoryDbTest {
    private val dataSource = DbTestDatabase.dataSource().also {
        Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate()
    }
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val repository = JdbcNotificationMuteRepository(jdbc, DbTestDatabase.dialect)

    private fun account() = "acc_${UUID.randomUUID()}"

    @Test
    fun `mute, mute again and unmute`() {
        val a = account()
        repository.setMuted(a, "NOTE_UPDATED", true)
        repository.setMuted(a, "NOTE_UPDATED", true)   // 이미 끈 것을 또 끄기 - 한 줄, 예외 없음
        repository.setMuted(a, "comment.created", true)
        assertEquals(setOf("NOTE_UPDATED", "comment.created"), repository.mutedTypes(a))

        repository.setMuted(a, "NOTE_UPDATED", false)
        repository.setMuted(a, "NOTE_UPDATED", false)   // 이미 켠 것을 또 켜기
        assertEquals(setOf("comment.created"), repository.mutedTypes(a))
        assertEquals(emptySet(), repository.mutedTypes(account()))
    }

    @Test
    fun `mutedAmong answers for many recipients of one type`() {
        val (a, b, c) = listOf(account(), account(), account())
        repository.setMuted(a, "T", true)
        repository.setMuted(b, "T", true)
        repository.setMuted(c, "OTHER", true)

        assertEquals(setOf(a, b), repository.mutedAmong("T", setOf(a, b, c)))
        assertEquals(emptySet(), repository.mutedAmong("T", emptySet()))
    }

    @Test
    fun `mutedAmong handles more recipients than one IN list chunk`() {
        val muted = account()
        repository.setMuted(muted, "T", true)
        val many = (1..1200).map { "acc_none_$it" }.toSet() + muted

        assertEquals(setOf(muted), repository.mutedAmong("T", many))
    }
}
