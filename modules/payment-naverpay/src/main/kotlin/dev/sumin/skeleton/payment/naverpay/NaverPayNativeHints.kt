package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.nativeimage.BindingHints
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/** 네이티브 이미지: 외부 응답 · 본문 JSON 타입을 등록한다 (docs/native-image.md). `META-INF/spring/aot.factories` 로 등록 - JVM 에서는 아무 일도 하지 않는다. */
class NaverPayNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        BindingHints.register(
            hints, classLoader,
            "dev.sumin.skeleton.payment.naverpay.NaverPayApplyResponse",
            "dev.sumin.skeleton.payment.naverpay.NaverPayApplyBody",
            "dev.sumin.skeleton.payment.naverpay.NaverPayApplyDetail",
            "dev.sumin.skeleton.payment.naverpay.NaverPayCancelResponse",
            "dev.sumin.skeleton.payment.naverpay.NaverPayCancelBody",
            "dev.sumin.skeleton.payment.naverpay.NaverPayHistoryResponse",
            "dev.sumin.skeleton.payment.naverpay.NaverPayHistoryBody",
            "dev.sumin.skeleton.payment.naverpay.NaverPayHistoryEntry",
            "dev.sumin.skeleton.payment.naverpay.NaverPayErrorMapper\$NaverPayErrorBody",
        )
    }
}
