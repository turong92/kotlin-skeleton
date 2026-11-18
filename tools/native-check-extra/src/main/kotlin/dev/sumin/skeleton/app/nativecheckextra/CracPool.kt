package dev.sumin.skeleton.app.nativecheckextra

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.crac.Context
import org.crac.Core
import org.crac.Resource
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CRaC 체크포인트 시험용(`-Pcrac`, Dockerfile.crac · docs/native-image.md "CRaC") — 체크포인트 직전에 DB 연결 풀을 비우고(열린 소켓이 있으면 체크포인트가 실패한다)
 * 복원 뒤에 다시 연다. `org.crac:crac` 이 클래스패스에 있을 때만 서고, 없으면(기본 JVM · 네이티브) 아무 일도 하지 않는다.
 * CRaC 는 등록된 자원을 **약한 참조**로 쥔다 — 이 객체가 빈으로 살아 있어야 부른다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Resource::class)
class CracPoolConfiguration {
    @Bean
    fun cracPool(dataSource: DataSource): CracPool = CracPool(dataSource.unwrap(HikariDataSource::class.java)).also { Core.getGlobalContext().register(it) }
}

class CracPool(private val pool: HikariDataSource) : Resource {
    override fun beforeCheckpoint(context: Context<out Resource>?) {
        pool.hikariPoolMXBean.suspendPool()
        pool.hikariPoolMXBean.softEvictConnections()
        val until = System.nanoTime() + 5_000_000_000L   // 연결 닫기는 풀의 일꾼이 비동기로 한다 — 다 닫힐 때까지 기다린다
        while (pool.hikariPoolMXBean.totalConnections > 0 && System.nanoTime() < until) Thread.sleep(20)
    }

    override fun afterRestore(context: Context<out Resource>?) {
        pool.hikariPoolMXBean.resumePool()
    }
}
