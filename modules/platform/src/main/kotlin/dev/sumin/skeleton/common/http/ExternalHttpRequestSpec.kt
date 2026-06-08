package dev.sumin.skeleton.common.http

import java.time.Duration
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

class ExternalHttpRequestSpec {
    internal val uriVariables: MutableMap<String, Any> = linkedMapOf()
    internal val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()
    internal val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
    internal val cookies: MultiValueMap<String, String> = LinkedMultiValueMap()
    internal val attributes: MutableMap<String, Any> = linkedMapOf()
    internal var timeout: Duration? = null
    internal var errorMapper: ExternalHttpErrorMapper? = null
    internal var loggingTag: String? = null

    fun uriVariable(name: String, value: Any) {
        uriVariables[name] = value
    }

    fun queryParam(name: String, value: Any) {
        queryParams.add(name, value.toString())
    }

    fun header(name: String, value: String) {
        headers.add(name, value)
    }

    fun cookie(name: String, value: String) {
        cookies.add(name, value)
    }

    fun attribute(name: String, value: Any) {
        attributes[name] = value
    }

    fun timeout(value: Duration) {
        timeout = value
    }

    fun errorMapper(value: ExternalHttpErrorMapper) {
        errorMapper = value
    }

    fun loggingTag(value: String) {
        loggingTag = value
    }
}
