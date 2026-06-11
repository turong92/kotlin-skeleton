package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext

class DistributedLockKeyResolver(
    private val keyPrefixer: RedisKeyPrefixer,
) {
    private val parser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()
    private val expressionCache = ConcurrentHashMap<String, Expression>()

    fun resolve(
        target: Any?,
        method: Method,
        args: Array<Any?>,
        annotation: DistributedLock,
    ): String {
        val resolvedKey = if (annotation.key.startsWith("#")) {
            evaluateExpression(target = target, method = method, args = args, expression = annotation.key)
        } else {
            annotation.key
        }
        return keyPrefixer.key("lock", annotation.keyPrefix, resolvedKey)
    }

    private fun evaluateExpression(
        target: Any?,
        method: Method,
        args: Array<Any?>,
        expression: String,
    ): String {
        val context = StandardEvaluationContext().apply {
            setVariable("target", target)
            parameterNameDiscoverer.getParameterNames(method)
                ?.forEachIndexed { index, name -> setVariable(name, args.getOrNull(index)) }
            args.forEachIndexed { index, arg ->
                setVariable("p$index", arg)
                setVariable("a$index", arg)
            }
        }

        return runCatching {
            expressionCache.computeIfAbsent(expression) { parser.parseExpression(it) }
                .getValue(context)
        }.getOrElse { error ->
            throw DistributedLockKeyException("Failed to evaluate lock key expression=$expression", error)
        }?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: throw DistributedLockKeyException("Lock key expression=$expression resolved to blank value")
    }
}
