dependencies {
    api(project(":modules:notification-push"))   // 광고 동의 정책 포트(AdvertisingConsentPolicy)를 구현하는 어댑터가 얹히는 코어 — 의존성 한 줄로 충분하게
    api(project(":modules:legal"))   // 동의 증거는 legal 의 일이다 — 어댑터가 legal 타입을 공개 API 에 낸다
}
