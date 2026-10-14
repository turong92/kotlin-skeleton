package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.payment.PaymentTaskHandler
import dev.sumin.skeleton.payment.PaymentTaskRunner
import java.util.function.Supplier
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor

/**
 * 작업 종류마다 큐 처리기([JobHandler]) 하나를 빈으로 등록한다 — **앱이 더한 [PaymentTaskHandler] 도 따라 등록된다**.
 *
 * 왜 빈 정의를 만드는가: `job-queue-jdbc` 의 워커는 `JobHandler` 빈을 **워커가 만들어질 때 한 번** 모으고(`ObjectProvider<JobHandler>.orderedStream()`) 종류별 지도를 고정한다.
 * 그래서 종류마다 하나의 `JobHandler` 빈이 이미 정의돼 있어야 한다. 종류를 코드에 적어 두면(`payment` 의 셋) 환불 · 웹훅 · 앱이 더하는 처리기를 놓친다 —
 * 놓친 종류의 작업은 큐에서 "처리기 없음"으로 죽는다. 그래서 모든 [PaymentTaskHandler] **빈 정의**마다 하나씩 만든다(인스턴스는 워커가 만들어질 때 비로소 만든다 — 일찍 만들지 않는다).
 * 처리기는 `PaymentTaskRunner` 로 일을 넘긴다(앱이 러너를 바꿔도 그대로 따른다).
 */
internal class PaymentJobHandlerRegistrar : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val factory = registry as? ListableBeanFactory ?: return
        // 이 시점에는 모든 설정 클래스가 읽혀 빈 정의가 레지스트리에 있다(설정 클래스 후처리기가 먼저 돈다). 빈을 만들지 않고 정의의 타입만 본다
        factory.getBeanNamesForType(PaymentTaskHandler::class.java, true, false).forEach { name ->
            val definition = BeanDefinitionBuilder.genericBeanDefinition(PaymentJobHandler::class.java, Supplier {
                val beans = registry as org.springframework.beans.factory.BeanFactory
                PaymentJobHandler(beans.getBean(name, PaymentTaskHandler::class.java).type, beans.getBean(PaymentTaskRunner::class.java))
            }).beanDefinition
            registry.registerBeanDefinition("paymentJobHandler:$name", definition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}

/**
 * 시작할 때 [PaymentTaskRunner.types] 의 모든 종류에 큐 처리기가 **하나** 있는지 본다. 없거나 둘 이상이면 시작을 멈춘다(둘이면 워커가 말없이 하나만 고른다) —
 * 처리기 없는 종류의 작업은 큐에 쌓였다가 기한(`unhandled-type-grace`)이 지나면 `No handler registered` 로 죽고, 그 작업은 돈이 걸린 일(만료 · 조회 · 지급 · 환불)이다.
 * (`PaymentTaskHandler` 빈이 아닌 방식으로 러너가 종류를 더한 경우 — 그 종류의 `JobHandler` 는 앱이 직접 등록한다.)
 */
internal class PaymentJobHandlerCoverage(
    private val runner: ObjectProvider<PaymentTaskRunner>,
    private val handlers: ObjectProvider<JobHandler>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val types = runner.getIfAvailable()?.types ?: return
        val registered = handlers.orderedStream().map { it.type }.toList()
        val missing = (types - registered.toSet()).sorted()
        check(missing.isEmpty()) {
            "Payment task types without a job queue handler: $missing. A PaymentTaskHandler bean gets one automatically; for any other type register a JobHandler bean " +
                "(otherwise its jobs sit in the queue until they are dead)."
        }
        // 워커는 종류마다 처리기 하나를 고른다 — 둘이면 말없이 하나만 돈다(앱이 같은 종류의 JobHandler 를 직접 둔 경우)
        val duplicated = registered.filter { it in types }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        check(duplicated.isEmpty()) {
            "Payment task types with more than one job queue handler: $duplicated. A PaymentTaskHandler bean already gets its JobHandler automatically; " +
                "remove the extra JobHandler bean (the worker would silently pick one of them)."
        }
    }
}
