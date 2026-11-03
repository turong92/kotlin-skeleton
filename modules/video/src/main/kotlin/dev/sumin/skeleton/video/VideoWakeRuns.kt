package dev.sumin.skeleton.video

import java.time.Instant

/**
 * 깨우기 기록(`video_wake_runs`) 포트 - `video-jdbc` 가 채우고 깨우기 구현(`video-cloudrun`)이 쓴다.
 * 동시 실행 상한 · 연달아 깨우기 억제 · 연속 실패 경보의 근거다. 에러 문장에는 상태 코드 · 예외 종류만 든다(토큰 · 응답 본문 없음)
 */
interface VideoWakeRuns {
    /** 깨우기 한 번을 남긴다. [ok] = 호출이 받아들여졌나 */
    fun record(at: Instant, ok: Boolean, execution: String?, error: String?)

    /** [since] 이후(그 시각 포함) 받아들여진 깨우기 수 - 「실행 중」으로 센다 */
    fun okSince(since: Instant): Int

    /** 마지막으로 받아들여진 깨우기 시각, 없으면 null */
    fun lastOkAt(): Instant?

    /** 마지막 성공 뒤의 실패 수 - 성공이 한 번도 없으면 실패 전부 */
    fun consecutiveFailures(): Int

    /** [before] 보다 오래된 기록을 지운다 - 지운 수 */
    fun deleteOlderThan(before: Instant): Int
}
