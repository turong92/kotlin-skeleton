dependencies {
    api(project(":modules:notification-push"))   // PushSender 를 구현해 FCM(HTTP v1)으로 보낸다 — 계약 타입이 공개 API 에 나온다
}
