package com.clubs.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        when {
            header == null -> {
                request.setAttribute(AUTH_REASON_ATTR, "missing_authorization_header")
            }
            !header.startsWith("Bearer ") -> {
                request.setAttribute(AUTH_REASON_ATTR, "authorization_not_bearer")
                log.warn("Non-Bearer Authorization on {} {}", request.method, request.requestURI)
            }
            else -> {
                val token = header.removePrefix("Bearer ")
                val parseResult = jwtService.parseTokenWithReason(token)
                when (parseResult) {
                    is JwtParseResult.Success -> {
                        SecurityContextHolder.getContext().authentication = parseResult.authentication
                        log.debug("JWT authenticated: {}", parseResult.authentication.name)
                    }
                    is JwtParseResult.Failure -> {
                        request.setAttribute(AUTH_REASON_ATTR, parseResult.reason)
                        request.setAttribute(AUTH_DETAIL_ATTR, parseResult.detail)
                        log.warn(
                            "JWT rejected on {} {}: {} — {}",
                            request.method, request.requestURI, parseResult.reason, parseResult.detail
                        )
                    }
                }
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val AUTH_REASON_ATTR = "clubs.auth.reason"
        const val AUTH_DETAIL_ATTR = "clubs.auth.detail"
    }
}
