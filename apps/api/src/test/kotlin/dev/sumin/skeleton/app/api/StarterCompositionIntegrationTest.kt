package dev.sumin.skeleton.app.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.util.ClassUtils
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 스타터는 최소 조립이 실제로 뜬다는 증거다. Redis · Kafka · S3 · 메일 · JPA 가 클래스패스에도 없는 상태로 컨텍스트가 뜬다.
 * 누가 스타터에 모듈 하나를 얹다가 이 가정을 깨면 여기서 걸린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class StarterCompositionIntegrationTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `context boots with only the starter modules on the classpath`() {
        assertTrue(context.containsBean("helloController"), "the app's own controller is registered by the app's component scan")
        assertTrue(context.containsBean("authController"), "the auth module registers itself through its AutoConfiguration")
        assertTrue(context.containsBean("traceIdFilter"), "the platform registers its filters through its AutoConfiguration")
    }

    @Test
    fun `optional integrations are absent, not merely switched off`() {
        val absent = listOf(
            "org.springframework.data.redis.core.RedisTemplate",
            "org.apache.kafka.clients.producer.KafkaProducer",
            "software.amazon.awssdk.services.s3.S3Client",
            "jakarta.mail.Session",
            "jakarta.persistence.EntityManager",
        )
        val present = absent.filter { ClassUtils.isPresent(it, javaClass.classLoader) }
        assertTrue(present.isEmpty(), "starter must not pull in: $present")
        assertFalse(context.containsBean("redisConnectionFactory"))
    }
}
