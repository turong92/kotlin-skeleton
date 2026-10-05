package dev.sumin.skeleton.account

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** 삭제 유예가 끝난 계정을 지우는 일을 `job-queue-jdbc` 의 잡 `account-purge` 로 — 재시도 · 죽은 잡 경보를 그 큐가 맡는다. 핸들러는 멱등이라 여러 인스턴스가 잡을 넣어도 안전하다 */
class AccountPurgeJobHandler(private val purge: AccountPurgeService) : JobHandler {
    override val type: String = TYPE

    override fun handle(job: Job) { purge.purgeDue() }

    companion object { const val TYPE = "account-purge" }
}

@AutoConfiguration(before = [AccountAutoConfiguration::class], afterName = ["dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.jobqueue.jdbc.JobQueue"])
@ConditionalOnBean(JobQueue::class)
class AccountPurgeJobAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountPurgeDispatch::class)
    fun accountPurgeJobDispatch(queue: JobQueue): AccountPurgeDispatch = AccountPurgeDispatch { queue.enqueue(AccountPurgeJobHandler.TYPE, "{}") }

    @Bean
    @ConditionalOnMissingBean(name = ["accountPurgeJobHandler"])
    fun accountPurgeJobHandler(purge: AccountPurgeService): JobHandler = AccountPurgeJobHandler(purge)
}
