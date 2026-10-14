package dev.sumin.skeleton.common.wake

/**
 * 스스로 도는 주기 작업(계정 삭제 정리 · 세션 정리 …)을 **밖의 호출로도 깨울 수 있게** 내놓는 고리 — 인스턴스가 0대로 내려가는 배포(Cloud Run 등)에서
 * 프로세스 안의 타이머는 인스턴스가 없으면 돌지 않는다. 작업 큐의 "요청 구동" 모드(`skeleton.job-queue.mode=on-request`)의 깨우기 주소가
 * 일감을 처리하기 전에 이 빈을 모두 한 번씩 부른다 (docs/cloud-run-scale-to-zero.md).
 *
 * 빈으로 등록하면 된다. [run] 은 **멱등** 이어야 하고(타이머가 같이 돌거나 여러 번 불려도 해가 없게) 오래 걸리지 않아야 한다 — 보통은 일감을 큐에 넣거나 한 묶음만 지운다.
 * 던진 예외는 호출한 쪽이 기록하고 다음 작업으로 간다.
 */
interface OnRequestTask {
    /** 로그 · 응답에 나오는 이름 (비밀이 아닌 고정 문자열) */
    val name: String

    fun run()
}

/** 이름과 동작만으로 만드는 [OnRequestTask] */
class SimpleOnRequestTask(override val name: String, private val action: () -> Unit) : OnRequestTask {
    override fun run() = action()
}
