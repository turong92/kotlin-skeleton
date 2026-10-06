package dev.sumin.skeleton.auth.session

/** 리프레시 토큰을 어떻게 전달하는지 (`body` | `cookie`) — `auth-session` 이 빈으로 둔다. 프론트에 알리는 용도 (`GET /auth/methods`) */
interface RefreshDeliveryInfo {
    val mode: String
}
