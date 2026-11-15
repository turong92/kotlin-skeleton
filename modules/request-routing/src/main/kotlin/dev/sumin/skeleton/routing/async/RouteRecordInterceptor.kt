package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.common.routing.RouteParse
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.listener.RecordInterceptor

/**
 * 메시지 머리 `baggage` 의 표시로 이 소비자가 받을 레코드를 거른다.
 * 원래 서버(`instance` 비움): 자기 서비스 몫으로 표시된 레코드는 건너뛴다(후보가 받는다). 후보: 자기 이름이 표시된 레코드만 받는다.
 * 건너뛴 레코드는 그대로 커밋된다 — 후보 그룹은 `latest` 로 시작해 후보가 떠 있는 동안의 메시지만 본다.
 * 받는 레코드는 리스너가 도는 동안 MDC `route` 를 복원하고(나가는 호출이 같은 대상으로 간다), 끝나면 지운다. 모양이 틀린 머리는 표시 없음으로 본다.
 */
class RouteRecordInterceptor(private val service: String, private val instance: String) : RecordInterceptor<Any, Any> {
    override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any>? {
        MDC.remove(RouteBaggage.MDC_KEY) // 스레드를 재사용한다 — 앞 레코드의 표시가 남지 않게
        val values = record.headers().headers(RouteBaggage.HEADER).map { String(it.value(), Charsets.UTF_8) }
        val route = (RouteBaggage.parseHeader(values) as? RouteParse.Valid)?.route
        val mine = route?.targetOf(service)
        val accepted = if (instance.isBlank()) mine == null else mine == instance
        if (!accepted) return null
        if (route != null) MDC.put(RouteBaggage.MDC_KEY, route.format())
        return record
    }

    override fun failure(record: ConsumerRecord<Any, Any>, exception: Exception, consumer: Consumer<Any, Any>) {
        MDC.remove(RouteBaggage.MDC_KEY)
    }

    override fun afterRecord(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>) {
        MDC.remove(RouteBaggage.MDC_KEY)
    }
}
