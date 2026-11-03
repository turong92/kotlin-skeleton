package dev.sumin.skeleton.videoworker

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/** 서명 주소를 열 수 있는 모양으로 — [url] 은 바꾼 주소, [host] 는 서명이 걸린 원래 Host(바꾸지 않았으면 null) */
data class Resolved(val url: String, val host: String?) {
    /** ffmpeg `-headers` 한 줄 */
    val ffmpegHeaders: String? get() = host?.let { "Host: $it\r\n" }
}

/**
 * 서명 주소로 내려받기 · 올리기. 일꾼은 R2 키를 갖지 않는다 — 서버가 서명한 주소만 쓴다.
 * 로컬 compose 는 서명 주소의 출처가 게이트웨이 도메인이라 컨테이너 안에서 안 열린다 → [storageOrigin] 으로 출처만 바꾸고
 * 서명에 든 Host 를 그대로 보낸다(`jdk.httpclient.allowRestrictedHeaders=host` 필요)
 */
class Transfer(private val storageOrigin: String?, private val apiOrigin: String? = null, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
    fun resolve(url: String): Resolved {
        // 서버는 비공개 영상의 입력으로 서버 주소 없는 루트 기준 경로를 준다 → API 출처로 푼다
        if (url.startsWith("/") && apiOrigin != null) return Resolved(apiOrigin + url, null)
        if (storageOrigin == null) return Resolved(url, null)
        val original = URI.create(url)
        val authority = original.rawAuthority
        val rest = url.substring(url.indexOf(authority) + authority.length)
        return Resolved(storageOrigin + rest, authority)
    }

    /** 내려받아 [dest] 에 쓴다 — 크기가 [expectedBytes] 와 다르면 실패 */
    fun download(url: String, dest: Path, expectedBytes: Long) {
        val r = resolve(url)
        val req = HttpRequest.newBuilder(URI.create(r.url)).timeout(TRANSFER_TIMEOUT).GET().apply { r.host?.let { header("Host", it) } }.build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
        if (res.statusCode() !in 200..299) throw TransferException("내려받기 ${res.statusCode()}")
        val size = Files.size(dest)
        if (size != expectedBytes) throw TransferException("받은 크기 $size ≠ 예상 $expectedBytes")
    }

    /** 서버가 준 헤더(내용 형식 · 캐시)를 그대로 실어 PUT */
    fun upload(target: UploadUrl, file: Path) {
        val r = resolve(target.url)
        val builder = HttpRequest.newBuilder(URI.create(r.url)).timeout(TRANSFER_TIMEOUT).PUT(HttpRequest.BodyPublishers.ofFile(file))
        target.headers.forEach { (k, v) -> if (!k.equals("host", true) && !k.equals("content-length", true)) builder.header(k, v) }
        r.host?.let { builder.header("Host", it) }
        val res = http.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        if (res.statusCode() !in 200..299) throw TransferException("올리기 ${res.statusCode()}")
    }

    companion object {
        /** 전송 하나의 한도 — 연결이 조용히 끊겨도 일꾼이 영원히 임대를 쥐지 않게(임대 연장은 일 단위가 아니라 스레드가 한다) */
        private val TRANSFER_TIMEOUT: Duration = Duration.ofHours(2)

        /** 조각 폴더의 해시 — 이름 순서대로 내용을 이어 붙인 SHA-256. 키(`o-<앞 16자>/`)가 곧 내용이라 `immutable` 이다 */
        fun sha256(files: List<Path>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            for (file in files.sortedBy { it.fileName.toString() }) {
                Files.newInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

class TransferException(message: String) : RuntimeException(message)
