package dev.sumin.skeleton.videoworker

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/** 영상 일꾼 진입점 — `VIDEO_WORKER_MODE=once` 면 일이 없을 때까지 하고 끝낸다(Cloud Run 작업), 아니면 상주한다 */
fun main() {
    val log = LoggerFactory.getLogger("video-worker")
    val config = try {
        WorkerConfig.fromEnv(System.getenv())
    } catch (e: IllegalStateException) {
        log.error("설정 오류: {}", e.message)
        exitProcess(2)
    }
    log.info("시작 {}", config)
    val health = if (config.mode == Mode.POLL && config.healthPort > 0) HealthServer.start(config.healthPort) else null
    val runner = Runner(
        config, WorkerApi(config), Transfer(config.storageOrigin, URI.create(config.apiBase).let { "${it.scheme}://${it.rawAuthority}" }),
        Tools(config.ffmpeg, config.ffprobe, config.nice, config.ffmpegTimeout),
    )
    when (config.mode) {
        Mode.ONCE -> log.info("끝낸 일 {}건", runner.runUntilIdle())
        Mode.POLL -> {
            val stop = AtomicBoolean(false)
            Runtime.getRuntime().addShutdownHook(Thread { stop.set(true); health?.close() })
            runner.runForever(stop)
        }
    }
}
