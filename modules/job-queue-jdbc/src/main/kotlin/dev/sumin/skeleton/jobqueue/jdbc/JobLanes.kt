package dev.sumin.skeleton.jobqueue.jdbc

/**
 * 차선 — 잡 줄의 `lane` 열에 적는 표시. 후보 인스턴스의 일꾼은 **자기 차선의 잡만** 집고, 기본 일꾼(차선 null)은 차선 없는 잡만 집는다.
 * 빈으로 등록하면 큐가 쓴다(요청 라우팅 모듈이 등록한다). 없으면 [NONE] — 모든 잡이 차선 없이 들어가고 모든 일꾼이 기본 일꾼이다.
 */
interface JobLanes {
    /** 지금 넣는 잡의 차선 (null = 기본) */
    fun enqueueLane(): String?

    /** 이 프로세스 일꾼의 차선 (null = 기본) */
    fun workerLane(): String?

    companion object {
        val NONE: JobLanes = object : JobLanes {
            override fun enqueueLane(): String? = null
            override fun workerLane(): String? = null
        }
    }
}
