package dev.sumin.skeleton.appcontrol

/**
 * 코드가 기능 스위치를 읽는 입구 - `featureFlags.isEnabled("new-checkout")`. 캐시된 값을 읽으므로 호출이 싸다(요청마다 불러도 된다).
 * 운영자가 한 번도 만들지 않은 키는 [default](기본 꺼짐) - 「평소엔 켜져 있고 문제 때 끄는」 기능은 `default = true` 로 부른다.
 * 컨트롤러를 통째로 막으려면 `@RequireFeature("new-checkout")`.
 */
class FeatureFlags(private val control: AppControl) {
    fun isEnabled(key: String, default: Boolean = false): Boolean = control.snapshot().flags[key]?.enabled ?: default
}
