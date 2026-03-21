package com.clubs.auth

import com.clubs.common.security.AuthenticatedUser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
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

    fun parseToken(token: String): Authentication? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

        val userId = UUID.fromString(claims["user_id"] as String)
        val telegramId = (claims["telegram_id"] as? Number)?.toLong() ?: return null

        val principal = AuthenticatedUser(userId, telegramId)
        UsernamePasswordAuthenticationToken(principal, null, emptyList())
    } catch (e: Exception) {
        null
    }
}
