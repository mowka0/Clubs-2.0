package com.clubs.common.security

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class RateLimitFilter : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(RateLimitFilter::class.java)
    // Отдельные пулы bucket'ов под отдельные лимиты — защита auth-эндпоинтов от брутфорса
    // требует более жёстких лимитов, чем общий API (security.md: 5/мин на /api/auth/*).
    private val apiBuckets = ConcurrentHashMap<String, Bucket>()
    private val authBuckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.requestURI == "/actuator/health") {
            filterChain.doFilter(request, response)
            return
        }

        val isAuthEndpoint = request.requestURI.startsWith("/api/auth/")
        val key = resolveKey(request)
        val bucket = if (isAuthEndpoint) {
            authBuckets.computeIfAbsent(key) { createAuthBucket() }
        } else {
            apiBuckets.computeIfAbsent(key) { createApiBucket() }
        }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response)
        } else {
            val limit = if (isAuthEndpoint) AUTH_LIMIT_PER_MIN else API_LIMIT_PER_MIN
            logger.warn(
                "Rate limit exceeded: key={} uri={} limit={}/min",
                key, request.requestURI, limit
            )
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Too many requests. Limit: $limit per minute."}""")
        }
    }

    /**
     * Для тестов: очищает все накопленные bucket'ы. Фильтр выполняется ДО аутентификации
     * (SecurityConfig), поэтому запросы MockMvc — все от 127.0.0.1 без principal — делят один
     * bucket `ip:127.0.0.1`. Интеграционный тест-класс, отправляющий >60 запросов за минуту,
     * иначе исчерпал бы общий API bucket и начал получать 429. В продакшене никогда не вызывается.
     */
    fun resetBuckets() {
        apiBuckets.clear()
        authBuckets.clear()
    }

    private fun resolveKey(request: HttpServletRequest): String {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth != null && auth.isAuthenticated && auth.principal is AuthenticatedUser) {
            val user = auth.principal as AuthenticatedUser
            return "user:${user.userId}"
        }
        return "ip:${getClientIp(request)}"
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return if (!forwarded.isNullOrBlank()) forwarded.split(",")[0].trim()
        else request.remoteAddr
    }

    private fun createApiBucket(): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(API_LIMIT_PER_MIN)
                .refillGreedy(API_LIMIT_PER_MIN, Duration.ofMinutes(1))
                .build()
        )
        .build()

    private fun createAuthBucket(): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(AUTH_LIMIT_PER_MIN)
                .refillGreedy(AUTH_LIMIT_PER_MIN, Duration.ofMinutes(1))
                .build()
        )
        .build()

    companion object {
        // Общий лимит на обычные API-запросы, в минуту на ключ (пользователь или IP).
        private const val API_LIMIT_PER_MIN = 60L
        // Жёсткий лимит для /api/auth/* — защита от брутфорса и подбора HMAC
        // (security.md: "агрессивно" — 5 попыток в минуту на IP/user).
        private const val AUTH_LIMIT_PER_MIN = 5L
    }
}
