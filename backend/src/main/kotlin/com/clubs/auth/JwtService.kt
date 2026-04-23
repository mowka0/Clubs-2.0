package com.clubs.auth

import com.clubs.common.security.AuthenticatedUser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.expiration}") private val expirationMs: Long
) {

    private val logger = LoggerFactory.getLogger(JwtService::class.java)

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray())
    }

    fun generateToken(userId: UUID, telegramId: Long): String =
        Jwts.builder()
            .claim("user_id", userId.toString())
            .claim("telegram_id", telegramId)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun parseToken(token: String): Authentication? =
        when (val result = parseTokenWithReason(token)) {
            is JwtParseResult.Success -> result.authentication
            is JwtParseResult.Failure -> null
        }

    fun parseTokenWithReason(token: String): JwtParseResult = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

        val userIdStr = claims["user_id"] as? String
            ?: return JwtParseResult.Failure("missing_user_id_claim", "user_id claim not present")
        val userId = UUID.fromString(userIdStr)
        val telegramId = (claims["telegram_id"] as? Number)?.toLong()
            ?: return JwtParseResult.Failure("missing_telegram_id_claim", "telegram_id claim not present")

        val principal = AuthenticatedUser(userId, telegramId)
        JwtParseResult.Success(UsernamePasswordAuthenticationToken(principal, null, emptyList()))
    } catch (e: Exception) {
        val reason = when (e.javaClass.simpleName) {
            "ExpiredJwtException" -> "token_expired"
            "SignatureException" -> "invalid_signature"
            "MalformedJwtException" -> "malformed_token"
            "UnsupportedJwtException" -> "unsupported_token"
            "IllegalArgumentException" -> "empty_or_null_token"
            else -> "parse_error_${e.javaClass.simpleName}"
        }
        logger.warn("JWT parse failed: {} — {}", reason, e.message)
        JwtParseResult.Failure(reason, e.message ?: "no detail")
    }
}

sealed class JwtParseResult {
    data class Success(val authentication: Authentication) : JwtParseResult()
    data class Failure(val reason: String, val detail: String) : JwtParseResult()
}
