package dev.sumin.skeleton.idempotency

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class CachedBodyHttpServletRequest(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    val cachedBody: ByteArray = request.inputStream.readAllBytes()

    init {
        setAttribute(IdempotencyAttributes.CACHED_BODY, cachedBody)
    }

    override fun getInputStream(): ServletInputStream =
        CachedBodyServletInputStream(cachedBody)

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))

    private class CachedBodyServletInputStream(
        body: ByteArray,
    ) : ServletInputStream() {
        private val input = ByteArrayInputStream(body)

        override fun isFinished(): Boolean =
            input.available() == 0

        override fun isReady(): Boolean =
            true

        override fun setReadListener(readListener: ReadListener?) {
            // Servlet async IO is not used by this skeleton wrapper.
        }

        override fun read(): Int =
            input.read()
    }
}
