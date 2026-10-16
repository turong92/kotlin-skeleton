package dev.sumin.skeleton.notification.mail

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectScoped
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender

/** `skeleton.notification-mail.enabled=true` 이고 `spring.mail.host` 가 있어 [JavaMailSender] 가 만들어졌을 때만 켜진다. */
@AutoConfiguration(after = [MailSenderAutoConfiguration::class])
@ConditionalOnProperty(prefix = "skeleton.notification-mail", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(JavaMailSender::class)
@EnableConfigurationProperties(MailProperties::class)
class NotificationMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MailSender::class)
    fun smtpMailSender(javaMailSender: JavaMailSender, properties: MailProperties, projects: ObjectProvider<ProjectRegistry>): MailSender {
        projects.getIfAvailable()?.let { registry ->
            // 프로젝트 모드: 발신자는 프로젝트마다 필수 — 공통 값으로 내려가지 않는다 (다른 서비스 이름의 주소로 메일이 나가면 안 된다). SMTP 접속(spring.mail.*)은 서버 공통
            val froms = ProjectScoped { project ->
                registry.bindOwn(project, "skeleton.notification-mail", MailProperties::class.java).from.also {
                    require(it.isNotBlank()) { "skeleton.projects.${project.id}.config.notification-mail.from must be set for project '${project.id}' when mail is enabled (it does not fall back to the shared from)." }
                }
            }
            registry.active.forEach { froms.of(it) }
            // 프로젝트 문맥이 없는 발송(서버 전체 경보 메일 …)은 공통 `from` 을 쓴다 — 그것도 없으면 어느 프로젝트의 주소로 보낼지 정할 수 없어 실패한다
            return SmtpMailSender(javaMailSender) { ProjectContext.currentOrNull()?.let(froms::of) ?: properties.from.ifBlank { throw NoProjectContextException() } }
        }
        require(properties.from.isNotBlank()) { "skeleton.notification-mail.from must be set when mail is enabled." }
        return SmtpMailSender(javaMailSender, properties.from)
    }
}
