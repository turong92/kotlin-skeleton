package dev.sumin.skeleton.board

/**
 * 공개 영역을 맞추는 일이 계속 실패할 때 주인에게 알리는 자리 - `alert` 모듈이 있으면 [dev.sumin.skeleton.board.alert.BoardAlertAutoConfiguration] 이 `OwnerAlerts` 로 잇는다.
 * 기본은 아무것도 하지 않는다(로그 경고는 늘 남는다). 같은 항목은 알림 모듈이 간격 안에 접는다.
 */
fun interface BoardPublicSyncAlerts {
    fun stuck(what: String, attempts: Int, error: String)

    companion object {
        val NONE = BoardPublicSyncAlerts { _, _, _ -> }
    }
}
