package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentWebhookTooLargeException
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 본문을 [maxBytes] 까지만 읽는다 — 크기를 속이거나(길이 헤더) 알리지 않는(chunked) 요청도 한도를 넘는 순간 멈춘다. 넘으면 [PaymentWebhookTooLargeException]. */
internal object BoundedBody {
    fun read(request: HttpServletRequest, maxBytes: Int): ByteArray = read(request.inputStream, request.contentLengthLong, maxBytes)

    /** [declared] 가 0 이상이고 한도를 넘으면 한 바이트도 읽지 않고 거절한다. 모르면(음수) 읽다가 한도를 넘는 순간(한도 + 1 바이트) 멈춘다. */
    fun read(input: InputStream, declared: Long, maxBytes: Int): ByteArray {
        if (declared > maxBytes) throw PaymentWebhookTooLargeException()
        val out = ByteArrayOutputStream(if (declared in 0..maxBytes.toLong()) declared.toInt() else 512)
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
            if (n < 0) break
            total += n
            if (total > maxBytes) throw PaymentWebhookTooLargeException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
